package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.Settings
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Uses the car's saved hotspot configuration. This never stops a running hotspot and never writes
 *  the saved configuration; writes happen once from the settings screen, after the driver confirms. */
object CarHotspotTethering {
    private val startupLock = ReentrantLock()
    enum class Result(val diagnostic: String) {
        READY("Car hotspot is on"),
        PERMISSION_REQUIRED("Hotspot control permission is missing"),
        UNSUPPORTED("This firmware does not support automatic hotspot startup"),
        FAILED("The car could not start its hotspot"),
        NO_CHANNEL("The car hotspot cannot use its saved band; set it to 2.4 GHz in car settings"),
        TIMED_OUT("Timed out waiting for the car hotspot"),
        CANCELLED("Hotspot startup was cancelled"),
    }

    fun permitted(context: Context): Boolean = Settings.System.canWrite(context)

    /** Whether this Android version lets an app with [permitted] access start tethering itself, without ADB. */
    fun appStartSupported(): Boolean = PlatformTetheringStart.supported()

    /** Blocking; serialize startup and connection requests, checking cancellation after acquiring the lock. */
    fun enable(
        context: Context,
        isCancelled: () -> Boolean,
        timeoutMillis: Long = WirelessStartupPolicy.HOTSPOT_READY_MILLIS,
        maxTimeoutMillis: Long = timeoutMillis,
        extendWhileEnabling: Boolean = false,
        log: (String) -> Unit,
    ): Result {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val observedAdbState = AtomicReference<Boolean?>()
        val startReflection: (ResultReceiver) -> Unit = { receiver ->
            try {
                PlatformTetheringStart.start(context, receiver, log)
            } catch (error: Exception) {
                // #616: the report did not say which start path failed.
                log("car hotspot: Android start request failed ${describe(error)}")
                throw error
            }
        }
        val startAdb: () -> Boolean = {
            val client = AtomicReference<LocalAdb?>()
            CarHotspotAdbFallback.bounded(deadline, isCancelled,
                abort = { client.get()?.cancelPendingOperations() }) {
                val adb = LocalAdb(AdbKeys.load(context))
                client.set(adb)
                adb.use {
                    val access = if (isCancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) null
                        else adb.connect(mayAsk = false)
                    if (access != null && access != LocalAdb.Access.READY) log("car hotspot: ADB access=$access")
                    if (access != LocalAdb.Access.READY) false
                    else CarHotspotAdbFallback.start(deadline, isCancelled,
                        state = { CarHotspotStatus.isEnabled(context) }, shell = adb::shell, log = log,
                        helper = shellTetheringHelper(context))
                        .also { if (it) observedAdbState.set(true) }
                }
            }
        }
        return enableWithRawState(
            timeoutMillis,
            isCancelled,
            { permitted(context) },
            stateWithAdbObservation({ CarHotspotStatus.isEnabled(context) }, observedAdbState),
            rawApState = { CarHotspotStatus.apState(context) },
            failureReason = { CarHotspotStatus.apFailureReason(context) },
            startFallback = startAdb,
            maxTimeoutMillis = maxTimeoutMillis,
            extendWhileEnabling = extendWhileEnabling,
            start = startReflection,
        ).also {
            if (it != Result.READY && it != Result.CANCELLED)
                log("car hotspot: Android state=${CarHotspotStatus.isEnabled(context) ?: "unknown"} writeSettings=${permitted(context)}")
            log("car hotspot auto-enable: ${it.diagnostic}")
        }
    }

    /** The shell's own tethering call ([CarHotspotStartMain]), for firmware without a hotspot command. */
    private fun shellTetheringHelper(context: Context): String = CarHotspotStartMain.command(context.applicationInfo.sourceDir)

    internal fun describe(error: Throwable): String {
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        val reason = cause.javaClass.simpleName + (cause.message?.let { ": ${it.take(100)}" } ?: "")
        return if (cause === error) reason else "${error.javaClass.simpleName} caused by $reason"
    }

    /** An ADB observation supplements hidden platform status, but never overrides a current off state. */
    internal fun stateWithAdbObservation(
        platformState: () -> Boolean?,
        observation: AtomicReference<Boolean?>,
    ): () -> Boolean? = { platformState() ?: observation.get() }

    internal fun enable(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        start: (ResultReceiver) -> Unit,
    ): Result = enable(timeoutMillis, isCancelled, canWrite, isEnabled, null, start)

    internal fun enable(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        startFallback: (() -> Boolean)?,
        start: (ResultReceiver) -> Unit,
    ): Result = enableUntil(
        timeoutMillis = timeoutMillis,
        isCancelled = isCancelled,
        canWrite = canWrite,
        isEnabled = isEnabled,
        rawApState = null,
        failureReason = null,
        startFallback = startFallback,
        maxTimeoutMillis = timeoutMillis,
        extendWhileEnabling = false,
        nowNanos = System::nanoTime,
        pauseMillis = { Thread.sleep(it) },
        start = start,
    )

    /** Injectable AP-state path for callers and deterministic state-machine tests. */
    internal fun enableWithRawState(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        rawApState: () -> Int?,
        failureReason: () -> Int?,
        startFallback: (() -> Boolean)? = null,
        maxTimeoutMillis: Long = timeoutMillis,
        extendWhileEnabling: Boolean = false,
        nowNanos: () -> Long = System::nanoTime,
        pauseMillis: (Long) -> Unit = { Thread.sleep(it) },
        start: (ResultReceiver) -> Unit,
    ): Result = enableUntil(
        timeoutMillis = timeoutMillis,
        isCancelled = isCancelled,
        canWrite = canWrite,
        isEnabled = isEnabled,
        rawApState = rawApState,
        failureReason = failureReason,
        startFallback = startFallback,
        maxTimeoutMillis = maxTimeoutMillis,
        extendWhileEnabling = extendWhileEnabling,
        nowNanos = nowNanos,
        pauseMillis = pauseMillis,
        start = start,
    )

    private fun enableUntil(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        rawApState: (() -> Int?)?,
        failureReason: (() -> Int?)?,
        startFallback: (() -> Boolean)?,
        maxTimeoutMillis: Long,
        extendWhileEnabling: Boolean,
        nowNanos: () -> Long,
        pauseMillis: (Long) -> Unit,
        start: (ResultReceiver) -> Unit,
    ): Result {
        val startedAt = nowNanos()
        var deadline = startedAt + timeoutMillis.coerceAtLeast(1) * 1_000_000L
        val maxDeadline = startedAt + maxOf(timeoutMillis, maxTimeoutMillis).coerceAtLeast(1) * 1_000_000L
        fun stopped(): Result? = when {
            isCancelled() || Thread.currentThread().isInterrupted -> Result.CANCELLED
            nowNanos() >= deadline -> Result.TIMED_OUT
            else -> null
        }
        while (true) {
            stopped()?.let { return it }
            val remaining = (deadline - nowNanos()) / 1_000_000L
            if (remaining <= 0) return Result.TIMED_OUT
            try {
                if (startupLock.tryLock(minOf(250L, remaining), TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.CANCELLED
            }
        }
        try {
            stopped()?.let { return it }
            fun enabledState(): Boolean? = runCatching(isEnabled).getOrNull()
            fun apState(): Int? = rawApState?.let { runCatching(it).getOrNull()?.takeIf { state -> state in 10..14 } }
            fun failure(): Int? = failureReason?.let { runCatching(it).getOrNull() }
            fun observed(state: Int?): Int? {
                if (state == 12) deadline = WirelessStartupPolicy.extendTetheringDeadline(
                    deadline, maxDeadline, extendWhileEnabling,
                )
                return state
            }
            fun pause(limit: Long = deadline): Boolean {
                stopped()?.let { return false }
                val remaining = (limit - nowNanos()) / 1_000_000L
                if (remaining <= 0) return false
                try {
                    pauseMillis(minOf(250L, remaining).coerceAtLeast(1L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                return stopped() == null
            }
            fun waitForFailureRetry(): Result? {
                val retryAt = nowNanos() + FAILURE_RETRY_DELAY_MILLIS * 1_000_000L
                while (nowNanos() < retryAt) {
                    if (!pause(minOf(deadline, retryAt))) return stopped() ?: Result.TIMED_OUT
                    val state = observed(apState())
                    if (state == 13 || (state == null && enabledState() == true)) return Result.READY
                    if (state == 14 && failure() == NO_CHANNEL_FAILURE_REASON) return Result.NO_CHANNEL
                }
                return null
            }
            var initialApState = observed(apState())
            val initialState = enabledState()
            stopped()?.let { return it }
            if (initialApState == 13 || initialState == true) return Result.READY
            val permission = canWrite()
            stopped()?.let { return it }
            if (!permission) return Result.PERMISSION_REQUIRED
            fun fallback(): Boolean {
                stopped()?.let { return false }
                return try { startFallback?.invoke() == true } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    false
                }
            }

            // Do not issue a duplicate request while another component is already bringing the AP up.
            if (initialApState == 12) {
                while (initialApState == 12) {
                    if (!pause()) return stopped() ?: Result.TIMED_OUT
                    initialApState = observed(apState())
                    if (initialApState == null) {
                        when (enabledState()) {
                            true -> return Result.READY
                            false -> break
                            null -> break
                        }
                    }
                }
                if (initialApState == 13) return Result.READY
                if (initialApState == 14 && failure() == NO_CHANNEL_FAILURE_REASON) return Result.NO_CHANNEL
            }

            // An AP being disabled can still be turning off; give the platform a short chance to settle.
            if (initialApState == 10) {
                val disablingDeadline = minOf(deadline, nowNanos() + DISABLING_SETTLE_MILLIS * 1_000_000L)
                while (initialApState == 10 && nowNanos() < disablingDeadline) {
                    if (!pause(disablingDeadline)) break
                    initialApState = observed(apState())
                    if (initialApState == 12) {
                        while (initialApState == 12) {
                            if (!pause()) return stopped() ?: Result.TIMED_OUT
                            initialApState = observed(apState())
                        }
                    }
                    if (initialApState == 13) return Result.READY
                    if (initialApState == null && enabledState() == true) return Result.READY
                }
            }

            val unknown = initialApState == null && initialState == null
            if (unknown && !fallback()) return stopped() ?: Result.UNSUPPORTED

            var response = AtomicInteger(-1)
            var requestedAt = nowNanos()
            var failureMayBeStale = false
            fun requestStart(stateBefore: Int?): Result? {
                response = AtomicInteger(-1)
                requestedAt = nowNanos()
                failureMayBeStale = stateBefore == 14
                try {
                    stopped()?.let { return it }
                    start(object : ResultReceiver(null) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                            response.set(resultCode)
                        }
                    })
                    return null
                } catch (error: Exception) {
                    val adbRecovered = ((error is ReflectiveOperationException && error !is InvocationTargetException) ||
                        error is SecurityException ||
                        (error is InvocationTargetException && error.targetException is SecurityException)) && fallback()
                    if (adbRecovered) return null
                    stopped()?.let { return it }
                    return when {
                        error is InvocationTargetException -> if (error.targetException is SecurityException) Result.PERMISSION_REQUIRED else Result.FAILED
                        error is ReflectiveOperationException -> Result.UNSUPPORTED
                        error is SecurityException -> Result.PERMISSION_REQUIRED
                        else -> Result.FAILED
                    }
                }
            }
            if (!unknown) requestStart(initialApState)?.let { return it }

            var failureRetries = 0
            var state11Since: Long? = null
            var longWaitRetried = false
            while (true) {
                stopped()?.let { return it }
                val state = observed(apState())
                val enabled = enabledState()
                stopped()?.let { return it }
                if (state != null && state != 14) failureMayBeStale = false
                if (state == 13 || (state == null && enabled == true)) return Result.READY
                if (response.get() > 0) return Result.FAILED
                // A FAILED state (and its reason) left from an earlier attempt is not this request's answer:
                // wait for the AP to leave it, or for the grace period, before judging this request.
                val staleFailure = state == 14 && failureMayBeStale &&
                    nowNanos() - requestedAt < STALE_FAILURE_GRACE_MILLIS * 1_000_000L
                if (state == 14 && !staleFailure) {
                    if (failure() == NO_CHANNEL_FAILURE_REASON) return Result.NO_CHANNEL
                    if (failureRetries >= MAX_FAILURE_RETRIES) return Result.FAILED
                    waitForFailureRetry()?.let { return it }
                    failureRetries++
                    requestStart(14)?.let { return it }
                    state11Since = null
                    continue
                }
                if (state == 11 && response.get() == 0) {
                    val now = nowNanos()
                    val since = state11Since ?: now.also { state11Since = it }
                    if (!longWaitRetried && now - since >= ACCEPTED_START_STUCK_MILLIS * 1_000_000L) {
                        longWaitRetried = true
                        requestStart(state)?.let { return it }
                    }
                } else {
                    state11Since = null
                }
                if (!pause()) return stopped() ?: Result.TIMED_OUT
            }
        } finally {
            startupLock.unlock()
        }
    }

    private const val NO_CHANNEL_FAILURE_REASON = 1
    private const val DISABLING_SETTLE_MILLIS = 2_000L
    private const val ACCEPTED_START_STUCK_MILLIS = 3_000L
    private const val FAILURE_RETRY_DELAY_MILLIS = 500L
    private const val MAX_FAILURE_RETRIES = 2
    private const val STALE_FAILURE_GRACE_MILLIS = 2_000L
}
