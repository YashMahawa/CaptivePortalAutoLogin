package de.binarynoise.captiveportalautologin

import java.util.Collections
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.function.Consumer
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.properties.Delegates
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_MIN
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_CANCEL_CURRENT
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.GuardedBy
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.binarynoise.captiveportalautologin.ConnectivityChangeListenerService.Companion.networkState
import de.binarynoise.captiveportalautologin.api.Api.Liberator.Error
import de.binarynoise.captiveportalautologin.api.Api.Liberator.Success
import de.binarynoise.captiveportalautologin.gecko.RecordCaptivePortalActivity
import de.binarynoise.captiveportalautologin.preferences.SharedPreferences
import de.binarynoise.captiveportalautologin.util.BackgroundHandler
import de.binarynoise.captiveportalautologin.util.applicationContext
import de.binarynoise.captiveportalautologin.util.mainHandler
import de.binarynoise.captiveportalautologin.util.startService
import de.binarynoise.liberator.Liberator
import de.binarynoise.liberator.PortalTestURL
import de.binarynoise.liberator.cast
import de.binarynoise.liberator.tryOrDefault
import de.binarynoise.liberator.tryOrNull
import de.binarynoise.logger.Logger.log

class ConnectivityChangeListenerService : Service() {
    
    val backgroundHandler = BackgroundHandler("ConnectivityChangeListenerService")
    
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var notification: Notification? = null
    private val notificationId = 1
    private val channelId = "ConnectivityChangeListenerService"
    private var networkCallbackRegistered = false
    private var settingsObserverRegistered = false
    private val attemptLimiter = NetworkAttemptLimiter()
    @Volatile private var retryWhenNetworkAvailable = false
    private val retryPolicy = PortalRetryPolicy()
    private var pendingRetry: Runnable? = null
    private var retryNetwork: Network? = null
    @Volatile private var stopping = false

    private fun automaticEnabled(ssid: String): Boolean =
        SharedPreferences.liberator_automatically_liberate.get() || ManualPortalProfiles.load()?.automaticSsid == ssid

    @Synchronized private fun cancelAutomaticRetry(reset: Boolean = true) {
        pendingRetry?.let(backgroundHandler::removeCallbacks)
        pendingRetry = null
        retryNetwork = null
        if (reset) retryPolicy.reset()
    }

    @Synchronized private fun scheduleAutomaticRetry(network: Network, delay: Long? = null) {
        if (stopping || pendingRetry != null) return
        val state = networkStateLock.read { networkState } ?: return
        if (state.network != network || !state.hasPortal || !automaticEnabled(state.ssid)) return
        val wait = delay ?: retryPolicy.nextDelayMillis()
        val task = Runnable {
            synchronized(this) { pendingRetry = null; retryNetwork = null }
            tryLiberate(expectedNetwork = network)
        }
        pendingRetry = task
        retryNetwork = network
        backgroundHandler.postDelayed(task, wait)
        LoginStatus.retryScheduled(wait)
    }
    
    @RequiresApi(Build.VERSION_CODES.Q)
    private val nonPersistentMacRandomizationSettingsObserver = object : ContentObserver(backgroundHandler) {
        override fun onChange(selfChange: Boolean) {
            if (!SharedPreferences.network_suggestions_mac_randomization.get()) updateNetworkSuggestions()
        }
    }
    
    private fun bindNetworkToProcess(oldState: NetworkState?, newState: NetworkState?) {
        if (oldState?.network == newState?.network) return
        
        val success = connectivityManager.bindProcessToNetwork(newState?.network)
        log(buildString {
            append(if (newState?.network != null) "bound to" else "unbound from")
            append(" network ")
            append(newState?.network ?: oldState?.network)
            append(": ")
            append(if (success) "success" else "failed")
        })
    }
    
    @Suppress("unused")
    @SuppressLint("MissingPermission")
    private fun updateNotification(oldState: NetworkState?, newState: NetworkState?) {
        val oldNotification = notification ?: return
        val text = newState?.toString().orEmpty()
        log("updateNotification: $text")
        val newNotification = NotificationCompat.Builder(this, oldNotification).apply {
            setContentText(text)
            clearActions()
            addNotificationActions(this, newState)
        }.build()
        NotificationManagerCompat.from(this).notify(notificationId, newNotification)
        notification = newNotification
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    @MainThread
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("onStartCommand")
        
        if (intent != null && intent.getBooleanExtra("stop", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        
        serviceStateLock.write {
            if (serviceState.running) {
                if (intent != null) when {
                    intent.getBooleanExtra("restart", false) -> {
                        serviceState = ServiceState(running = true, restart = true)
                        stopSelf()
                    }
                    intent.getBooleanExtra("retry", false) -> {
                        retryLiberate()
                    }
                }
                return START_STICKY
            }
            serviceState = ServiceState(running = true, restart = false)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(channelId, "Persistent Notification", IMPORTANCE_MIN)
            notificationManager.createNotificationChannel(serviceChannel)
        }
        notification = NotificationCompat.Builder(this, channelId).let { builder ->
            builder.setContentTitle(getString(R.string.notification_service_title))
            builder.setContentText(getString(R.string.notification_service_description))
            builder.setSmallIcon(R.drawable.wifi_lock_open)
            builder.setStyle(NotificationCompat.BigTextStyle())
            
            // start HomeActivity on click
            val launchHomeIntent =
                Intent().apply { component = ComponentName.createRelative(application.packageName, ".HomeActivity") }
            launchHomeIntent.addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK)
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this, 0, launchHomeIntent, FLAG_CANCEL_CURRENT or FLAG_IMMUTABLE
                )
            )
            
            addNotificationActions(builder, networkStateLock.read { networkState })
            
            builder.setOnlyAlertOnce(true)
        }.build()
        
        try {
            ServiceCompat.startForeground(
                this, notificationId, notification!!,
                if (Build.VERSION.SDK_INT >= 29) FOREGROUND_SERVICE_TYPE_MANIFEST else 0,
            )
        } catch (e: IllegalStateException) {
            log("Failed to start $this as foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        
        retryWhenNetworkAvailable = intent?.getBooleanExtra("retry", false) == true
        networkListeners.add(::bindNetworkToProcess)
        networkListeners.add(::updateNotification)
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        networkCallbackRegistered = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = Settings.Global.getUriFor(SETTINGS_NON_PERSISTENT_MAC_RANDOMIZATION_FORCE_ENABLED_KEY)
            contentResolver.registerContentObserver(uri, false, nonPersistentMacRandomizationSettingsObserver)
            settingsObserverRegistered = true
            updateNetworkSuggestions(onAppDisallowedCallback = { SharedPreferences.network_suggestions.set(false) })
        }
        
        log("started")
        return START_STICKY
    }
    
    fun addNotificationActions(builder: NotificationCompat.Builder, networkState: NetworkState?) {
        val hasPortal = networkState?.hasPortal ?: false
        val liberated = networkState?.liberated ?: false
        val autoLiberation = SharedPreferences.liberator_automatically_liberate.get()
        
        if (hasPortal && (liberated || !autoLiberation)) {
            // add button to try liberating again
            val retryIntent = Intent(this, this::class.java)
            retryIntent.putExtra("retry", true)
            val pendingRetryIntent =
                PendingIntent.getService(this, 0, retryIntent, FLAG_CANCEL_CURRENT or FLAG_IMMUTABLE)
            builder.addAction(
                NotificationCompat.Action.Builder(
                    null, getString(R.string.liberate_now), pendingRetryIntent
                ).build()
            )
        }
        
        if (hasPortal) {
            val captureIntent = Intent(this, RecordCaptivePortalActivity::class.java)
            captureIntent.putExtra(ConnectivityManager.EXTRA_NETWORK, networkState.network)
            val pendingCaptureIntent =
                PendingIntent.getActivity(this, 0, captureIntent, FLAG_CANCEL_CURRENT or FLAG_IMMUTABLE)
            builder.addAction(
                NotificationCompat.Action.Builder(
                    null, getString(R.string.capture_captive_portal_short), pendingCaptureIntent
                ).build()
            )
        }
    }
    
    override fun onDestroy() {
        stopping = true
        cancelAutomaticRetry()
        super.onDestroy()
        log("onDestroy")
        
        serviceStateLock.write {
            serviceState = serviceState.copy(running = false)
            
            if (networkCallbackRegistered) {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                networkCallbackRegistered = false
            }
            networkStateLock.write { networkState = null }
            networkListeners.remove(::bindNetworkToProcess)
            networkListeners.remove(::updateNotification)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            
            val n = notification
            if (n != null) {
                n.actions?.forEach { it.actionIntent?.cancel() }
                NotificationManagerCompat.from(this).cancel(notificationId)
                notification = null
            }
            
            backgroundHandler.looper.quit()
            backgroundHandler.looper.thread.interrupt()
            
            log("stopped")
            
            if (serviceState.restart) {
                mainHandler.post {
                    ContextCompat.startForegroundService(
                        applicationContext,
                        Intent(applicationContext, ConnectivityChangeListenerService::class.java),
                    )
                }
                log("scheduled restart")
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            removeNetworkSuggestions()
            if (settingsObserverRegistered) {
                contentResolver.unregisterContentObserver(nonPersistentMacRandomizationSettingsObserver)
                settingsObserverRegistered = false
            }
        }
    }
    
    private val networkCallback = createNetworkCallback()
    
    private fun createNetworkCallback(): ConnectivityManager.NetworkCallback {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log("onAvailable: $network")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    // Starting with Build.VERSION_CODES.O onCapabilitiesChanged is guaranteed to be called immediately after onAvailable.
                    
                    val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return
                    updateNetworkState(network, networkCapabilities)
                }
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                updateNetworkState(network, networkCapabilities)
            }
            
            override fun onLost(network: Network) {
                networkStateLock.write {
                    log("onUnavailable: $network")
                    val oldState = networkState
                    if (oldState?.network == network) networkState = null
                }
                backgroundHandler.post { if (retryNetwork == network) cancelAutomaticRetry() }
            }
        }
        
        return when {
            Build.VERSION.SDK_INT < 31 -> callback
            else -> NetworkCallback31(callback)
        }
    }
    
    fun updateNetworkState(network: Network, networkCapabilities: NetworkCapabilities) {
        val hasPortal = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        val network = tryOrDefault(network) { disablePrivateDns(network) }
        
        val ssid = SsidCompat.getSsid(network, networkCapabilities)
        val resetRetry = networkStateLock.write {
            val oldState = networkState
            networkState = if (oldState == null || oldState.network != network) {
                NetworkState(network, ssid ?: SsidCompat.UNKNOWN_SSID, hasPortal, false, false)
            } else {
                oldState.copy(
                    ssid = ssid ?: oldState.ssid,
                    hasPortal = hasPortal,
                    liberated = if (hasPortal && !oldState.hasPortal) false else oldState.liberated,
                )
            }
            oldState == null || oldState.network != network || (hasPortal && !oldState.hasPortal)
        }
        if (resetRetry || !hasPortal) backgroundHandler.post { cancelAutomaticRetry() }
        if (retryWhenNetworkAvailable) {
            retryWhenNetworkAvailable = false
            backgroundHandler.post { tryLiberate(force = true, expectedNetwork = network) }
            return
        }
        if (!hasPortal) return
        
        if (!automaticEnabled(ssid ?: SsidCompat.UNKNOWN_SSID)) {
            log("not liberating automatically")
            LoginStatus.record("Portal detected, but automatic login is off. Enable automatic login or saved details for this Wi-Fi.")
            return
        }
        
        backgroundHandler.post { tryLiberate(expectedNetwork = network) }
    }
    
    private val setPrivateDnsBypassMethodP by lazy {
        check(Build.VERSION.SDK_INT == Build.VERSION_CODES.P)
        Network::class.java.declaredMethods.single { it.name == "setPrivateDnsBypass" }
    }
    
    private val getPrivateDnsBypassingCopyMethodQ by lazy {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        Network::class.java.declaredMethods.single { it.name == "getPrivateDnsBypassingCopy" }
    }
    
    @Throws(ReflectiveOperationException::class, IllegalArgumentException::class, NoSuchElementException::class)
    fun disablePrivateDns(network: Network): Network {
        when {
            Build.VERSION.SDK_INT == Build.VERSION_CODES.P -> {
                setPrivateDnsBypassMethodP(network, true)
                return network
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                return getPrivateDnsBypassingCopyMethodQ(network) as Network
            }
            else -> return network
        }
    }
    
    @Throws(ReflectiveOperationException::class, IllegalArgumentException::class, NoSuchElementException::class)
    fun getPrivateDnsBypass(network: Network): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) throw NoSuchElementException("private dns bypass doesn't exist on ${Build.VERSION.SDK_INT}")
        return network::class.java.declaredFields.single { it.name == "mPrivateDnsBypass" }.get(network) as Boolean
    }
    
    @WorkerThread
    fun tryLiberate(force: Boolean = false, expectedNetwork: Network? = null) {
        if (stopping) return
        if (captureNetwork != null) {
            LoginStatus.record("Automatic login paused while recording. Complete the login in the browser, or close it to resume.")
            val current = networkStateLock.read { networkState?.network }
            if (current != null) scheduleAutomaticRetry(current, 60_000)
            return
        }
        val (network, ssid) = networkStateLock.write {
            val state = networkState
            if (state == null) {
                log("no network")
                Toast.makeText(applicationContext, R.string.not_connected, Toast.LENGTH_SHORT).show()
                return
            }
            if (expectedNetwork != null && state.network != expectedNetwork) return
            if (!force && !state.hasPortal) {
                log("no portal")
                Toast.makeText(applicationContext, R.string.not_in_portal, Toast.LENGTH_SHORT).show()
                return
            }
            if (!force && state.liberated) {
                log("already liberated")
                return
            }
            if (state.liberating) {
                log("already liberating")
                Toast.makeText(applicationContext, R.string.already_liberating, Toast.LENGTH_SHORT).show()
                return
            }
            if (!attemptLimiter.acquire(state.network, SystemClock.elapsedRealtime(), manual = force)) {
                backgroundHandler.post { scheduleAutomaticRetry(state.network, 60_000) }
                return
            }
            networkState = state.copy(liberating = true, liberated = false)
            state.network to state.ssid
        }
        
        val t = Toast.makeText(applicationContext, R.string.liberating, Toast.LENGTH_SHORT)
        t.show()
        
        var succeeded = false
        LoginStatus.record(if (force) "Trying manual login on Wi-Fi…" else "Trying automatic login on Wi-Fi…")
        try {
            val userAgent: String by SharedPreferences.liberator_user_agent
            val portalTestUrl: PortalTestURL by SharedPreferences.liberator_captive_test_url
            
            val manualProfile = ManualPortalProfiles.load()
            if (manualProfile != null && (force || manualProfile.automaticSsid == ssid)) {
                if (WifiInternetCheck.isOnline(network, userAgent)) {
                    succeeded = true
                    t.cancel()
                    LoginStatus.record("Internet is already working on this Wi-Fi. No login was submitted.")
                    reportNetworkConnectivity(network, true)
                    return
                }
                ManualPortalLogin.submit(network, manualProfile, userAgent)
                reportNetworkConnectivity(network, true)
            }

            val liberationResult = Liberator(
                { okhttpClient ->
                    okhttpClient.socketFactory(network.socketFactory)
                    okhttpClient.dns { hostname -> network.getAllByName(hostname).toList() }
                },
                portalTestUrl,
                userAgent,
                ssid,
                experimental = SharedPreferences.liberator_experimental_enabled,
                appVersion = BuildConfig.VERSION_NAME,
                liberatorVersion = "",
                requestSystemReevaluation = { reportNetworkConnectivity(network, true) },
                isSystemLiberated = {
                    connectivityManager.getNetworkCapabilities(network)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                },
            ).liberate()
            
            t.cancel()
            
            when (liberationResult) {
                Liberator.LiberationResult.NotCaught -> {
                    succeeded = true
                    LoginStatus.record("Internet access verified on Wi-Fi.")
                    log("not caught in portal")
                    Toast.makeText(applicationContext, R.string.liberate_failed_no_portal, Toast.LENGTH_SHORT).show()
                    reportNetworkConnectivity(network, true)
                    // no report
                }
                is Liberator.LiberationResult.Success -> {
                    succeeded = true
                    LoginStatus.record("Login succeeded. Android internet re-evaluation requested.")
                    log("broke out of the portal")
                    Toast.makeText(applicationContext, R.string.liberate_success, Toast.LENGTH_SHORT).show()
                    reportNetworkConnectivity(network, true)
                    ScheduledApiClient.liberator.reportSuccess(
                        Success(
                            version = BuildConfig.VERSION_NAME,
                            timestamp = System.currentTimeMillis(),
                            ssid = ssid,
                            url = liberationResult.url,
                            solver = liberationResult.solvers,
                        )
                    )
                }
                is Liberator.LiberationResult.Error -> {
                    log("failed to liberate: ${liberationResult.message}", liberationResult.exception)
                    LoginStatus.record("Login failed: ${liberationResult.message}")
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.liberate_failed) + "${liberationResult.exception::class.simpleName} - ${liberationResult.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    reportNetworkConnectivity(network, false)
                    liberationResult.har.comment = ssid
                    ScheduledApiClient.liberator.reportError(
                        Error(
                            version = BuildConfig.VERSION_NAME,
                            timestamp = System.currentTimeMillis(),
                            ssid = ssid,
                            url = liberationResult.url,
                            message = liberationResult.message,
                            solver = liberationResult.solvers,
                            stackTrace = liberationResult.exception.stackTraceToString(),
                            har = liberationResult.har,
                        )
                    )
                }
                is Liberator.LiberationResult.Timeout -> {
                    log("failed to liberate: timeout")
                    LoginStatus.record("Login timed out. Check the portal URL and Wi-Fi connection.")
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.liberate_failed) + getString(R.string.timeout),
                        Toast.LENGTH_SHORT
                    ).show()
                    reportNetworkConnectivity(network, false)
                    // no timeout report
                }
                is Liberator.LiberationResult.UnknownPortal -> {
                    log("failed to liberate: unknown portal: ${liberationResult.url}")
                    LoginStatus.record("This portal has no supported automatic handler. Configure its exact login URL and credentials, or use the browser to inspect it.")
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.liberate_failed) + getString(R.string.unknown_portal) + " " + liberationResult.url,
                        Toast.LENGTH_SHORT,
                    ).show()
                    reportNetworkConnectivity(network, false)
                    ScheduledApiClient.liberator.reportError(
                        Error(
                            version = BuildConfig.VERSION_NAME,
                            timestamp = System.currentTimeMillis(),
                            ssid = ssid,
                            url = liberationResult.url,
                            message = "unknown portal",
                            solver = null,
                            stackTrace = null,
                            har = null,
                        )
                    )
                }
                is Liberator.LiberationResult.StillCaptured -> {
                    log("failed to liberate: still captured")
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.liberate_failed) + getString(R.string.still_captured) + " " + liberationResult.url,
                        Toast.LENGTH_SHORT,
                    ).show()
                    reportNetworkConnectivity(network, false)
                    ScheduledApiClient.liberator.reportError(
                        Error(
                            version = BuildConfig.VERSION_NAME,
                            timestamp = System.currentTimeMillis(),
                            ssid = ssid,
                            url = liberationResult.url,
                            message = "still captured",
                            solver = liberationResult.solvers,
                            stackTrace = null,
                            har = liberationResult.har,
                        )
                    )
                }
                is Liberator.LiberationResult.UnsupportedPortal -> {
                    log("Failed to liberate: Unsupported Portal")
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.liberate_failed) + getString(R.string.liberate_result_unsupported_portal) + " " + liberationResult.url,
                        Toast.LENGTH_SHORT,
                    ).show()
                    reportNetworkConnectivity(network, false)
                    // no report
                }
            }
        } catch (e: Exception) {
            t.cancel()
            log("failed to liberate", e)
            val message = e.localizedMessage ?: e.message ?: getString(R.string.no_error_message)
            LoginStatus.record("Login failed: $message")
            Toast.makeText(
                applicationContext,
                getString(R.string.liberate_failed) + "${e::class.simpleName} - $message",
                Toast.LENGTH_LONG,
            ).show()
            reportNetworkConnectivity(network, false)
            ScheduledApiClient.liberator.reportError(
                Error(
                    version = BuildConfig.VERSION_NAME,
                    timestamp = System.currentTimeMillis(),
                    ssid = ssid,
                    url = null,
                    message = message,
                    solver = null,
                    stackTrace = e.stackTraceToString(),
                    har = null,
                )
            )
        } finally {
            networkStateLock.write {
                val state = networkState
                if (state?.network == network) {
                    networkState = state.copy(liberating = false, liberated = succeeded)
                }
            }
            if (succeeded) cancelAutomaticRetry() else scheduleAutomaticRetry(network)
        }
    }
    
    
    private fun retryLiberate() {
        val network = networkStateLock.read { networkState?.network }
        if (network != null) {
            networkStateLock.write {
                networkState = networkState?.copy(liberated = false)
            }
            backgroundHandler.post { tryLiberate(force = true, expectedNetwork = network) }
        } else {
            Toast.makeText(this, R.string.not_connected_to_network, Toast.LENGTH_SHORT).show()
        }
    }
    
    // FLAG_INCLUDE_LOCATION_INFO not available pre API 31
    @RequiresApi(31)
    class NetworkCallback31(val wrapped: ConnectivityManager.NetworkCallback) :
        ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        //<editor-fold defaultstate="collapsed" desc="delegates">
        override fun onAvailable(network: Network) {
            wrapped.onAvailable(network)
        }
        
        override fun onLosing(network: Network, maxMsToLive: Int) {
            wrapped.onLosing(network, maxMsToLive)
        }
        
        override fun onLost(network: Network) {
            wrapped.onLost(network)
        }
        
        override fun onUnavailable() {
            wrapped.onUnavailable()
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            wrapped.onCapabilitiesChanged(network, networkCapabilities)
        }
        
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            wrapped.onLinkPropertiesChanged(network, linkProperties)
        }
        
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            wrapped.onBlockedStatusChanged(network, blocked)
        }
        //</editor-fold>
    }
    
    object SsidCompat {
        val wifiManager by lazy { ContextCompat.getSystemService(applicationContext, WifiManager::class.java)!! }
        
        const val UNKNOWN_SSID = "<unknown ssid>"
        
        fun getSsid(network: Network, networkCapabilities: NetworkCapabilities?): String? {
            var ssid: String?
            
            // pre API 29, but try anyway
            ssid = tryOrNull {
                @Suppress("DEPRECATION") //
                wifiManager.connectionInfo.ssid.takeIf { it != UNKNOWN_SSID }?.let(::decodeSsid)
            }
            if (ssid != null) {
                log("got ssid from wifiManager.connectionInfo.ssid: $ssid")
                return ssid
            }
            
            // API 29+, doesn't always receive ssid on first try
            if (Build.VERSION.SDK_INT >= 29) {
                ssid = tryOrNull {
                    val capabilities = networkCapabilities ?: connectivityManager.getNetworkCapabilities(network)
                    val wifiInfo = capabilities?.transportInfo?.cast<WifiInfo?>()
                    wifiInfo?.ssid?.takeIf { it != UNKNOWN_SSID }?.let(::decodeSsid)
                }
                if (ssid != null) {
                    log("got ssid from networkCapabilities: $ssid")
                    return ssid
                }
            }
            
            return null
        }
        
        fun decodeSsid(ssid: String): String {
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                return ssid.substring(1, ssid.length - 1)
            }
            return "0x$ssid"
        }
    }
    
    companion object {
        val connectivityManager by lazy {
            ContextCompat.getSystemService(applicationContext, ConnectivityManager::class.java)!!
        }
        val serviceListeners: MutableSet<(oldState: ServiceState, newState: ServiceState) -> Unit> = SynchronizedSet()
        val serviceStateLock = ReentrantReadWriteLock(true)
        
        @delegate:GuardedBy("serviceStateLock")
        var serviceState: ServiceState by Delegates.observable(
            ServiceState(running = false, restart = false)
        ) { _, oldState, newState ->
            if (oldState != newState) serviceStateLock.read {
                log("notifying ${serviceListeners.size} serviceListeners...")
                serviceListeners.javaForEach { it(oldState, newState) }
            }
        }
            private set
        
        val networkListeners: MutableSet<(oldState: NetworkState?, newState: NetworkState?) -> Unit> = SynchronizedSet()
        val networkStateLock = ReentrantReadWriteLock(true)
        
        @delegate:GuardedBy("networkStateLock")
        var networkState: NetworkState? by Delegates.observable(null) { _, oldState, newState ->
            if (oldState != newState) {
                log("notifying ${networkListeners.size} networkListeners...")
                networkListeners.javaForEach { it(oldState, newState) }
            }
        }
        
        val networkRequest: NetworkRequest = NetworkRequest.Builder().apply {
            addTransportType(TRANSPORT_WIFI)
            if (Build.VERSION.SDK_INT >= 31) {
                setIncludeOtherUidNetworks(true)
            }
        }.build()
        @Volatile var captureNetwork: Network? = null
        
        fun start(silent: Boolean = false): Unit = serviceStateLock.read {
            if (serviceState.running || serviceState.restart) return
            val missingPermissions = Permissions.filterNot { it.granted(applicationContext) }
                .map { permission -> applicationContext.getString(permission.nameRes) }
            if (missingPermissions.isNotEmpty()) {
                if (!silent) {
                    Toast.makeText(
                        applicationContext,
                        applicationContext.getString(R.string.service_start_missing_permissions) + missingPermissions.joinToString(),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return
            }
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, ConnectivityChangeListenerService::class.java),
            )
        }
        
        fun stop() {
            applicationContext.startService<ConnectivityChangeListenerService> {
                putExtra("stop", true)
            }
        }
        
        fun restart() = serviceStateLock.read {
            if (serviceState.running) {
                applicationContext.startService<ConnectivityChangeListenerService> {
                    putExtra("restart", true)
                }
            } else start()
        }
        
        fun retry() {
            applicationContext.startService<ConnectivityChangeListenerService> {
                putExtra("retry", true)
            }
        }
        
        /**
         * Report the connectivity state of [network] to the [ConnectivityManager].
         *
         * Please set both parameters if possible.
         *
         * @param network the network which the report is about, read from [networkState] if `null`
         * @param hasConnectivity whether the [network] has connectivity or not, defaults to the opposite of the current state
         */
        fun reportNetworkConnectivity(
            network: Network? = null,
            hasConnectivity: Boolean? = null,
        ) {
            val state = networkStateLock.read { networkState }
            val network = network ?: state?.network ?: return
            val hasConnectivity = hasConnectivity ?: state?.hasPortal ?: true
            connectivityManager.reportNetworkConnectivity(network, hasConnectivity)
            log("sent network report for $network hasConnectivity=$hasConnectivity")
            Toast.makeText(applicationContext, R.string.requested_reevaluation, Toast.LENGTH_SHORT).show()
        }
        
        init {
            networkListeners.add(::logNetwork)
            serviceListeners.add(::logService)
        }
        
        private fun logNetwork(oldState: NetworkState?, newState: NetworkState?) {
            log("Network changed: $oldState -> $newState")
        }
        
        private fun logService(oldState: ServiceState, newState: ServiceState) {
            log("Service changed: $oldState -> $newState")
        }
    }
    
    data class ServiceState(
        val running: Boolean, val restart: Boolean,
    ) {
        override fun toString(): String = buildString {
            append("Service is currently ")
            if (running) {
                append("running")
            } else {
                append("not running")
            }
            if (restart) {
                append(" and will be restarted")
            }
        }
    }
    
    data class NetworkState(
        val network: Network,
        val ssid: String,
        val hasPortal: Boolean,
        val liberating: Boolean,
        val liberated: Boolean,
    ) {
        override fun toString(): String = buildString {
            append("Network $network with SSID $ssid")
            
            append(" is ")
            if (!hasPortal) {
                append("not ")
            }
            append("caught in Portal")
            append(" and is currently ")
            if (liberating) {
                append("liberating")
            } else if (liberated) {
                append("liberated")
            } else {
                append("not liberating")
            }
        }
    }
}

fun <T> SynchronizedSet(): MutableSet<T> = Collections.synchronizedSet<T>(mutableSetOf<T>())

fun <T> Set<T>.javaForEach(consumer: Consumer<T>) = forEach(consumer)
