package de.binarynoise.captiveportalautologin

import java.util.concurrent.TimeUnit.SECONDS
import kotlin.concurrent.thread
import kotlin.jvm.optionals.getOrNull
import kotlin.system.exitProcess
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import de.binarynoise.liberator.Liberator
import de.binarynoise.liberator.PortalDetection
import de.binarynoise.logger.Logger.log
import okhttp3.ConnectionPool
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture

fun main(args: Array<String>) = CaptivePortalAutoLoginLinux().main(args)

class CaptivePortalAutoLoginLinux : CliktCommand() {
    val service by option().flag().help { "Keep monitoring NetworkManager (the default)" }
    val oneshot by option().flag().help { "Run as a service (false) or only once (true)" }
    val force by option().flag()
        .help { "Force liberation without connectivity check by NetworkManager (implies --oneshot)" }
    val experimental by option().flag().help { "enable experimental and incomplete Portals" }
    val restartNetworking by option().flag()
        .help { "Restart networking on start. Also available as keyboard shortcut 'r' while running as service" }
    
    val iitj by option().flag().help { "Use the saved IITJ credential profile" }
    val configureIitj by option().flag().help { "Save IITJ details with hidden password entry in a terminal" }
    val profile by option().help { "Private profile file (default ~/.config/captiveportalautologin/iitj.properties)" }
    val connectionUuid by option().help { "Campus NetworkManager connection UUID to save during configuration" }
    private val profilePath get() = profile?.let { Path.of(it) } ?: IitjLogin.defaultPath
    private val retries = Executors.newSingleThreadScheduledExecutor()
    private var retryTask: ScheduledFuture<*>? = null
    private var retrySeconds = 60L
    private var iitjProfile: IitjLogin.Profile? = null

    override fun run() {
        require(!(service && oneshot)) { "Use either --service or --oneshot" }
        if (configureIitj) {
            require(!iitj && !service && !oneshot && !force) { "Configure separately from login mode" }
            IitjLogin.configure(profilePath, connectionUuid)
            return
        }
        if (iitj) {
            iitjProfile = IitjLogin.load(profilePath)
            if (oneshot || force) {
                val ok = runCatching { IitjLogin.login(iitjProfile!!) }.getOrElse {
                    System.err.println("IITJ login failed: ${it.message}"); false
                }
                retries.shutdownNow()
                exitProcess(if (ok) 0 else 1)
            }
            require(iitjProfile!!.connection != null) { "Automatic IITJ mode requires a saved connection UUID. Configure with --connection-uuid, or use --iitj --oneshot." }
        }
        log("CaptivePortalAutoLogin for Linux")
        log("https://github.com/binarynoise/CaptivePortalAutoLogin")
        
        if (restartNetworking) {
            restartNetworking()
            Thread.sleep(1000)
        }
        
        if (force) {
            onConnectivityChanged("portal", oneshot = true)
            // TODO: find out why this doesn't allow the program to exit
            return
        }
        
        thread(block = ::startupCheck)
        
        if (!oneshot) {
            thread(block = ::backgroundService)
            thread(block = ::keyboardInput)
        }
    }
    
    
    /**
     * Builds a [ProcessBuilder] with `LC_ALL=C` set in the environment.
     * This is necessary to produce consistent `nmcli` output across different languages.
     */
    val processBuilder: ProcessBuilder
        get() = ProcessBuilder().apply { environment()["LC_ALL"] = "C" }
    
    
    /**
     * Monitors the connectivity state using the `nmcli monitor` command.
     *
     * Reads the output of the `nmcli monitor` command and parses it to determine the current connectivity state.
     * When the connectivity state changes, the [onConnectivityChanged] function is called with the new state.
     *
     * The output of the `nmcli monitor` command and any errors that occur are logged.
     */
    private fun backgroundService() {
        val process = processBuilder.command("nmcli", "monitor").start()
        val regex = "^Connectivity is now '(\\w+)'$".toRegex()
        process.inputReader().useLines { lines ->
            lines.forEach { line ->
                log(line)
                val result = regex.matchEntire(line)
                val connectivity = result?.groups?.get(1)?.value
                if (connectivity != null) {
                    onConnectivityChanged(connectivity)
                }
            }
        }
        process.errorReader().useLines { it.forEach { log(it) } }
        log("process ${process.info().command().getOrNull()} finished")
    }
    
    
    /**
     * Retrieves the current connectivity state using the `nmcli networking connectivity` command.
     *
     * Runs the `nmcli networking connectivity` command and parses the output
     * to determine the current connectivity state.
     * The [onConnectivityChanged] function is called with the new state.
     */
    private fun startupCheck() {
        val process = processBuilder.command("nmcli", "networking", "connectivity").start()
        process.inputReader().useLines { line -> line.forEach { log(it); onConnectivityChanged(it, oneshot = true) } }
        process.errorReader().useLines { it.forEach { line -> log(line) } }
        log("process ${process.info().command().getOrNull()} finished")
    }
    
    
    /**
     * Reads the input from the console and executes actions based on the input.
     *
     * Supports keyboard shortcuts:
     * - `r`: restart networking
     * - `q`: quit the application
     */
    private fun keyboardInput() {
        while (true) {
            val char = System.`in`.read()
            if (char == -1) break
            
            when (char.toChar()) {
                'r' -> {
                    restartNetworking()
                }
                'q' -> {
                    exitProcess(0)
                }
            }
        }
    }
    
    
    /**
     * Restarts the networking by turning it off and on again.
     *
     * This function runs the `nmcli networking off` and `nmcli networking on` commands to restart the networking.
     */
    private fun restartNetworking() {
        processBuilder.command("nmcli", "networking", "off").start().waitFor()
        processBuilder.command("nmcli", "networking", "on").start().waitFor()
        log("restarted networking")
    }
    
    
    /**
     * Queries the SSID of the currently active Wi-Fi connection.
     * 
     * Uses `nmcli` to get the list of Wi-Fi networks and returns the SSID of the first network
     * that is marked as active. If no active Wi-Fi connection is found, returns null.
     */
    private fun querySSID(): String? {
        val process =
            processBuilder.command("nmcli -t -e no -f active,ssid dev wifi list --rescan no".split(" ")).start()
        val input = process.inputReader().readLines()
        val ssid = input.firstOrNull { it.startsWith("yes") }?.removePrefix("yes:")
        log("ssid: $ssid")
        return ssid
    }
    
    
    /**
     * Handles the event of connectivity state change.
     * 
     * Parses the [connectivity] state and attempts to liberate the user if the state is "portal".
     */
    @Synchronized
    fun onConnectivityChanged(connectivity: String, oneshot: Boolean = false) {
        if (iitj) {
            retryTask?.cancel(false)
            retryTask = null
            if (connectivity == "full" || connectivity == "none") { retrySeconds = 60; return }
            val saved = iitjProfile ?: return
            val matching = runCatching { saved.connection in IitjLogin.activeConnections() }.getOrDefault(false)
            if (!matching) { retrySeconds = 60; return }
            if (connectivity != "portal" && connectivity != "limited" && connectivity != "unknown") return
            val succeeded = runCatching { IitjLogin.login(saved) }.getOrElse {
                System.err.println("IITJ login failed: ${it.message}"); false
            }
            if (succeeded) retrySeconds = 60 else {
                println("Retry in $retrySeconds seconds while the saved campus connection remains active.")
                retryTask = retries.schedule({ onConnectivityChanged("portal") }, retrySeconds, TimeUnit.SECONDS)
                retrySeconds = (retrySeconds * 2).coerceAtMost(900)
            }
            return
        }
        try {
            log("onConnectivityChanged: $connectivity")
            if (connectivity == "portal") {
                val ssid = querySSID()
                
                val liberationResult = Liberator(
                    { okhttpClient -> if (oneshot) okhttpClient.connectionPool(ConnectionPool(0, 1, SECONDS)) },
                    PortalDetection.defaultBackend,
                    PortalDetection.defaultUserAgent,
                    ssid,
                    experimental = experimental,
                ).liberate()
                
                when (liberationResult) {
                    is Liberator.LiberationResult.Success -> log("broke out of the portal")
                    is Liberator.LiberationResult.Error -> log(
                        "Failed to liberate: ${liberationResult.message}", liberationResult.exception
                    )
                    Liberator.LiberationResult.NotCaught -> log("not caught in portal")
                    is Liberator.LiberationResult.StillCaptured -> log("Failed to liberate: still in portal: ${liberationResult.url}")
                    is Liberator.LiberationResult.Timeout -> log("Failed to liberate: timeout")
                    is Liberator.LiberationResult.UnknownPortal -> log("Failed to liberate: unknown portal: ${liberationResult.url}")
                    is Liberator.LiberationResult.UnsupportedPortal -> log("Failed to liberate: Portal will not be supported: ${liberationResult.url}")
                }
            }
        } catch (e: Exception) {
            log("failed to liberate", e)
        }
    }
}
