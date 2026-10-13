package com.shilapi.xcertplay.network

import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Uses only a firmware-advertised command for the saved hotspot; never configures a replacement AP. */
internal object CarHotspotAdbFallback {
    private const val EXIT = "DIPLAY_HOTSPOT_EXIT:"
    private const val ADB_POLL_INTERVAL_MS = 500L
    private val permissionDenied = Regex("permission denied|securityexception", RegexOption.IGNORE_CASE)
    private val advertisedForm = Regex("^\\s*(start-tethering|start)\\s+(wifi|[<\\[(][^>\\])]*\\bwifi\\b[^>\\])]*[>\\])])(?:\\s*\\[[^\\n]*])?\\s*$")
    private val exitCode = Regex("(?m)^$EXIT(\\d+)\\s*$")
    private val rejectedOutput = Regex(
        "unknown command|permission denied|securityexception|(?:^|\\n)\\s*error:|failed|can't find service|not found|not supported|unsupported",
        RegexOption.IGNORE_CASE)
    private val transitioningState = Regex("(?m)^\\s*(?:mWifiApState|mSoftApState|mTetheredSoftApState)\\s*[:=]\\s*(10|12)\\s*$")
    private val managerHeader = Regex("(?m)^\\s*(?:--)?Dump of SoftApManager(?:--| id=\\S+)?\\s*$")
    private val numericState = Regex("(?m)^\\s*(?:mWifiApState|mSoftApState|mTetheredSoftApState)\\s*[:=]\\s*(\\d+)\\s*$")
    private val managerMode = Regex("(?m)^\\s*(?:mMode|mOriginalModeConfiguration\\.targetMode):\\s*(\\d+)\\s*$")
    private val machineState = Regex("(?m)^\\s*current StateMachine mode:\\s*(\\w+)\\s*$")
    private val interfaceUp = Regex("(?m)^\\s*mIfaceIsUp:\\s*(true|false)\\s*$")
    private val workers = Executors.newCachedThreadPool { action ->
        Thread(action, "diplay-hotspot-adb").apply { isDaemon = true }
    }
    private val cancellationWatcher = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "diplay-hotspot-adb-cancel").apply { isDaemon = true }
    }

    fun start(deadline: Long, cancelled: () -> Boolean, state: () -> Boolean?,
        shell: (String, Int) -> String?, log: (String) -> Unit): Boolean {
        fun remaining() = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtMost(30_000).toInt()
        fun stopped() = cancelled() || Thread.currentThread().isInterrupted || remaining() <= 0
        fun read(command: String): String? {
            if (stopped()) return null
            return shell(command, remaining().coerceAtLeast(1))?.also {
                if (permissionDenied.containsMatchIn(it))
                    throw SecurityException("Firmware denied hotspot control")
            }
        }
        fun observed(): Boolean? = state() ?: apState(read("dumpsys wifi"))
        if (stopped()) return false
        // Hidden framework status may require ADB observation. Wait out an AP transition;
        // never interpret ENABLING/DISABLING as permission to send another start.
        var initial = state()
        if (initial == null) {
            while (!stopped()) {
                val dump = read("dumpsys wifi")
                if (!apTransitioning(dump)) { initial = apState(dump); break }
                Thread.sleep(minOf(ADB_POLL_INTERVAL_MS, remaining().coerceAtLeast(1).toLong()))
            }
        }
        if (initial == null) return false // No blind mutation when no AP state can be observed.
        if (stopped()) return false
        if (initial) return true
        var command: String? = null
        for (service in listOf("connectivity", "tethering")) {
            command = advertisedCommand(service, read("cmd $service help"))
            if (command != null) break
        }
        if (command == null || stopped()) return false
        val output = read("$command 2>&1; printf '\\n$EXIT%s\\n' \"\$?\"")
        if (stopped()) return false
        if (!accepted(output)) throw IllegalStateException("Firmware rejected hotspot start")
        log("car hotspot: firmware ADB start request accepted; waiting for AP enabled state")
        while (!stopped()) {
            if (observed() == true && !stopped()) {
                log("car hotspot: AP enabled state confirmed after local ADB request")
                return true
            }
            try {
                Thread.sleep(minOf(ADB_POLL_INTERVAL_MS, remaining().coerceAtLeast(1).toLong()))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /** The PR's command forms are vendor extensions, not generic Android commands. */
    internal fun advertisedCommand(service: String, help: String?): String? {
        if (service !in listOf("connectivity", "tethering") || help == null || rejected(help)) return null
        for (line in help.lineSequence()) {
            val form = advertisedForm.matchEntire(line) ?: continue
            val verb = form.groupValues[1]
            if (service == "connectivity" && verb != "start-tethering") continue
            return "cmd $service $verb wifi"
        }
        return null
    }

    internal fun accepted(output: String?): Boolean = output != null && !rejected(output) &&
        exitCode.findAll(output).lastOrNull()?.groupValues?.get(1) == "0"

    private fun rejected(output: String): Boolean = rejectedOutput.containsMatchIn(output)

    internal fun apTransitioning(dump: String?): Boolean = dump != null &&
        transitioningState.containsMatchIn(dump)

    /** Read current AP fields only, never a past log, station Wi-Fi state or an interface address. */
    internal fun apState(dump: String?): Boolean? {
        if (dump == null) return null
        val managers = dump.split(managerHeader).drop(1)
        if (managers.isEmpty()) {
            val numeric = numericState
                .findAll(dump).map { CarHotspotStatus.stateEnabled(it.groupValues[1].toIntOrNull()) }.toList()
            return if (numeric.any { it == null }) null else numeric.distinct().singleOrNull()
        }
        val tethered = managers.filter { block ->
            val mode = managerMode.find(block)?.groupValues?.get(1)
            mode == "1"
        }
        val states = tethered.map { block ->
            val current = machineState.find(block)?.groupValues?.get(1)
            val up = interfaceUp.find(block)?.groupValues?.get(1)
            when {
                current == "StartedState" && up == "true" -> true
                current == "IdleState" && up == "false" -> false
                else -> null
            }
        }
        return if (states.any { it == null }) null else states.distinct().singleOrNull()
    }

    /** Deadline includes connection, capability reads, writes and AP-state polling. Cancellation aborts I/O. */
    internal fun bounded(deadline: Long, cancelled: () -> Boolean, abort: () -> Unit, action: () -> Boolean): Boolean {
        if (cancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) return false
        val task = workers.submit<Boolean> { action() }
        // This watcher exists only during bounded I/O, never while the observer is asleep/idle.
        val watcher = cancellationWatcher.scheduleWithFixedDelay({
            if (cancelled()) task.cancel(true)
        }, 0, 50, TimeUnit.MILLISECONDS)
        try {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            return task.get(remaining, TimeUnit.NANOSECONDS) && !cancelled()
        } catch (_: TimeoutException) {
        } catch (_: CancellationException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: java.util.concurrent.ExecutionException) {
            when (val cause = error.cause) {
                is SecurityException -> throw cause
                is IllegalStateException -> throw cause
            }
        }
        finally {
            watcher.cancel(false)
            if (!task.isDone || task.isCancelled) {
                try { abort() } finally { task.cancel(true) }
            }
        }
        return false
    }
}
