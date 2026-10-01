package de.binarynoise.captiveportalautologin

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import de.binarynoise.captiveportalautologin.preferences.ManualLoginFragment
import de.binarynoise.captiveportalautologin.preferences.fillInAnimation
import de.binarynoise.captiveportalautologin.gecko.RecordCaptivePortalActivity
import de.binarynoise.captiveportalautologin.preferences.MainActivity
import de.binarynoise.captiveportalautologin.preferences.SystemPortalTestUrl
import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the real release browser and HTTP client against a local, synthetic portal. */
class RecorderSmokeInstrumentation : Instrumentation() {
    private val loaded = CountDownLatch(1)
    private val portalUrl = "http://10.0.2.2:8765/login"

    override fun callActivityOnCreate(activity: Activity, state: Bundle?) {
        super.callActivityOnCreate(activity, state)
        if (activity is RecordCaptivePortalActivity) {
            activity.extensionDelegate.session.progressDelegate = object : GeckoSession.ProgressDelegate {
                private var fixture = false
                override fun onPageStart(session: GeckoSession, url: String) {
                    fixture = url.startsWith(portalUrl)
                    activity.progressDelegate.onPageStart(session, url)
                }
                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    activity.progressDelegate.onPageStop(session, success)
                    if (fixture && success) loaded.countDown()
                }
            }
        }
    }

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var activity: RecordCaptivePortalActivity? = null
        var home: Activity? = null
        val previous = ManualPortalProfiles.load()
        var resultCode = Activity.RESULT_CANCELED
        try {
            home = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("startService", false))
            val mainScreen = home as MainActivity
            runOnMainSync {
                check(!mainScreen.isFinishing) { "Main screen closed at startup" }
                verifyInsets(mainScreen)
                mainScreen.supportFragmentManager.executePendingTransactions()
                mainScreen.supportFragmentManager.commit {
                    fillInAnimation()
                    replace(R.id.fragmentContainerView, ManualLoginFragment())
                    addToBackStack("smoke-manual")
                }
                mainScreen.supportFragmentManager.executePendingTransactions()
                check(mainScreen.supportFragmentManager.backStackEntryCount == 1)
                mainScreen.onBackPressedDispatcher.onBackPressed()
                mainScreen.supportFragmentManager.executePendingTransactions()
                check(mainScreen.supportFragmentManager.backStackEntryCount == 0) { "Back did not restore the main screen" }
            }
            val manager = targetContext.getSystemService(ConnectivityManager::class.java)
            val network = awaitWifi(manager)
            val profile = ManualPortalProfile(portalUrl, "smoke-user", "smoke-password", null)
            ManualPortalProfiles.save(profile)
            check(ManualPortalProfiles.load() == profile) { "Encrypted credential round trip failed" }
            activity = startActivitySync(Intent(targetContext, RecordCaptivePortalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ConnectivityManager.EXTRA_NETWORK, network)) as RecordCaptivePortalActivity
            val recorder = activity
            check(loaded.await(120, TimeUnit.SECONDS)) { "Recorder did not load the portal page" }
            runOnMainSync {
                check(!recorder.isFinishing && !recorder.isDestroyed) { "Validated network dismissed the recorder" }
                verifyInsets(recorder)
            }
            val captured = CountDownLatch(1)
            val captureDeadline = android.os.SystemClock.uptimeMillis() + 15_000
            recorder.backgroundHandler.post(object : Runnable {
                override fun run() {
                    val har = recorder.extensionDelegate.createFinalizedHar("Smoke", SystemPortalTestUrl, allowEdits = true).second
                    if (har.log.entries.any { it.request.url.startsWith(profile.url) }) captured.countDown()
                    else if (android.os.SystemClock.uptimeMillis() < captureDeadline) recorder.backgroundHandler.postDelayed(this, 100)
                }
            })
            check(captured.await(20, TimeUnit.SECONDS)) { "Private browser extension did not capture the page request" }
            runOnMainSync { recorder.finish() }
            waitForIdleSync()
            ManualPortalLogin.submit(network, profile, "CaptivePortalSmokeTest")
            result.putString("stream", "Recorder page load, traffic capture, encrypted profile, and manual HTTP login passed.\n")
            result.putString("ui", "Back navigation, gesture-bar/cutout and keyboard inset regression checks passed.")
            resultCode = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream", "SMOKE TEST FAILED: ${android.util.Log.getStackTraceString(error)}\n")

        } finally {
            activity?.let { recorder -> runOnMainSync { if (!recorder.isFinishing) recorder.finish() } }
            home?.let { mainScreen -> runOnMainSync { if (!mainScreen.isFinishing) mainScreen.finish() } }
            if (previous == null) ManualPortalProfiles.clear() else ManualPortalProfiles.save(previous)
        }
        finish(resultCode, result)
    }

    private fun verifyInsets(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val actual = ViewCompat.getRootWindowInsets(root)
        val sample = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 32))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(12, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 240))
            .setVisible(WindowInsetsCompat.Type.ime(), true).build()
        repeat(2) {
            ViewCompat.dispatchApplyWindowInsets(root, sample)
            check(root.paddingBottom == 240 && root.paddingLeft == 12 && root.paddingTop == 24) {
                "Insets overlapped content or accumulated across dispatch"
            }
        }
        val noKeyboard = WindowInsetsCompat.Builder(sample)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
            .setVisible(WindowInsetsCompat.Type.ime(), false).build()
        ViewCompat.dispatchApplyWindowInsets(root, noKeyboard)
        check(root.paddingBottom == 32) { "Keyboard dismissal did not restore navigation spacing" }
        actual?.let { ViewCompat.dispatchApplyWindowInsets(root, it) }
        ViewCompat.requestApplyInsets(root)
    }

    private fun awaitWifi(manager: ConnectivityManager): Network {
        val ready = CountDownLatch(1)
        var selected: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    selected = network
                    ready.countDown()
                }
            }
        }
        manager.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        try {
            check(ready.await(45, TimeUnit.SECONDS)) { "Emulator Wi-Fi did not become validated" }
            return checkNotNull(selected)
        } finally { manager.unregisterNetworkCallback(callback) }
    }
}
