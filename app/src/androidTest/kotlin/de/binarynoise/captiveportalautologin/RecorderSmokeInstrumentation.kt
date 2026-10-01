package de.binarynoise.captiveportalautologin

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import de.binarynoise.captiveportalautologin.gecko.RecordCaptivePortalActivity
import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the real release browser and HTTP client against a local, synthetic portal. */
class RecorderSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var activity: RecordCaptivePortalActivity? = null
        val previous = ManualPortalProfiles.load()
        var resultCode = Activity.RESULT_CANCELED
        try {
            val manager = targetContext.getSystemService(ConnectivityManager::class.java)
            val network = checkNotNull(manager.activeNetwork) { "Emulator has no network" }
            val profile = ManualPortalProfile("http://10.0.2.2:8765/login", "smoke-user", "smoke-password", null)
            ManualPortalProfiles.save(profile)
            check(ManualPortalProfiles.load() == profile) { "Encrypted credential round trip failed" }
            activity = startActivitySync(Intent(targetContext, RecordCaptivePortalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ConnectivityManager.EXTRA_NETWORK, network)) as RecordCaptivePortalActivity
            val recorder = activity
            val loaded = CountDownLatch(1)
            runOnMainSync {
                recorder.extensionDelegate.session.progressDelegate = object : GeckoSession.ProgressDelegate {
                    private var fixture = false
                    override fun onPageStart(session: GeckoSession, url: String) {
                        fixture = url.startsWith(profile.url)
                        recorder.progressDelegate.onPageStart(session, url)
                    }
                    override fun onPageStop(session: GeckoSession, success: Boolean) {
                        recorder.progressDelegate.onPageStop(session, success)
                        if (fixture && success) loaded.countDown()
                    }
                }
            }
            check(loaded.await(120, TimeUnit.SECONDS)) { "Recorder did not load the portal page" }
            runOnMainSync { check(!recorder.isFinishing && !recorder.isDestroyed) { "Validated network dismissed the recorder" } }
            val captured = CountDownLatch(1)
            recorder.backgroundHandler.post {
                val har = recorder.createFinalizedHar().second
                if (har.log.entries.any { it.request.url.startsWith(profile.url) }) captured.countDown()
            }
            check(captured.await(15, TimeUnit.SECONDS)) { "Private browser extension did not capture the page request" }
            runOnMainSync { recorder.finish() }
            waitForIdleSync()
            ManualPortalLogin.submit(network, profile, "CaptivePortalSmokeTest")
            result.putString("stream", "Recorder page load, traffic capture, encrypted profile, and manual HTTP login passed.\n")
            resultCode = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream", "SMOKE TEST FAILED: ${android.util.Log.getStackTraceString(error)}\n")

        } finally {
            activity?.let { recorder -> runOnMainSync { if (!recorder.isFinishing) recorder.finish() } }
            if (previous == null) ManualPortalProfiles.clear() else ManualPortalProfiles.save(previous)
        }
        finish(resultCode, result)
    }
}
