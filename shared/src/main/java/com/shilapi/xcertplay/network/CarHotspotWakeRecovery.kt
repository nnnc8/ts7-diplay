package com.shilapi.xcertplay.network

/** One bounded wake cycle. Scheduling/deduplication belongs to the foreground observer. */
object CarHotspotWakeRecovery {
    const val WAKE_TIMEOUT_MILLIS = 60_000L
    const val ATTEMPT_TIMEOUT_MILLIS = 8_000L
    const val SETTLE_MILLIS = 3_000L
    const val RETRY_MILLIS = 5_000L
    const val MAX_ATTEMPTS = 3

    enum class Outcome {
        HOTSPOT_RESTORED, ALREADY_ENABLED, SESSION_ACTIVE, CANCELLED,
        PERMISSION_REQUIRED, HOTSPOT_UNSUPPORTED, HOTSPOT_START_FAILED,
    }

    data class State(
        val optedIn: Boolean,
        val interactive: Boolean,
        val airplaneMode: Boolean,
        val radioReady: Boolean,
        val hotspotEnabled: Boolean?,
        val sessionActive: Boolean,
        // Changes on every airplane/radio event, including a quick on/off between polls.
        val radioRevision: Long = 0,
    ) {
        fun canStart() = optedIn && interactive && !airplaneMode && radioReady && !sessionActive
    }

    /** [enable] must confirm AP ENABLED itself (including when only ADB can observe it). */
    fun recover(
        cancelled: () -> Boolean,
        state: () -> State,
        enable: (cancelled: () -> Boolean, timeoutMillis: Long) -> CarHotspotTethering.Result,
        now: () -> Long,
        pause: (Long) -> Unit,
    ): Outcome {
        val deadline = now() + WAKE_TIMEOUT_MILLIS
        var stableSince: Long? = null
        var revision: Long? = null
        var nextAttempt = 0L
        var attempts = 0
        fun stopped() = cancelled() || Thread.currentThread().isInterrupted
        try {
            while (!stopped()) {
                val current = state()
                if (stopped() || !current.optedIn || !current.interactive) return Outcome.CANCELLED
                if (!current.airplaneMode && current.hotspotEnabled == true) return Outcome.ALREADY_ENABLED
                val time = now()
                if (time >= deadline) return Outcome.HOTSPOT_START_FAILED
                val radioUsable = !current.airplaneMode && current.radioReady
                if (!radioUsable || revision != current.radioRevision) stableSince = null
                revision = current.radioRevision
                if (radioUsable) {
                    if (stableSince == null) stableSince = time
                    // A pre-sleep session flag may clear while radios recover. Decide only after
                    // settling, and still cancel immediately if a live session starts during enable.
                    if (time - stableSince >= SETTLE_MILLIS && current.sessionActive) return Outcome.SESSION_ACTIVE
                    if (time - stableSince >= SETTLE_MILLIS && time >= nextAttempt) {
                        if (attempts >= MAX_ATTEMPTS) return Outcome.HOTSPOT_START_FAILED
                        attempts++
                        val attemptRevision = current.radioRevision
                        val attemptCancelled = {
                            stopped() || now() >= deadline || state().let {
                                !it.canStart() || it.radioRevision != attemptRevision
                            }
                        }
                        val result = enable(attemptCancelled, minOf(ATTEMPT_TIMEOUT_MILLIS, deadline - time))
                        if (stopped()) return Outcome.CANCELLED
                        val after = state()
                        if (!after.optedIn || !after.interactive) return Outcome.CANCELLED
                        if (after.sessionActive) return Outcome.SESSION_ACTIVE
                        when (result) {
                            CarHotspotTethering.Result.PERMISSION_REQUIRED -> return Outcome.PERMISSION_REQUIRED
                            CarHotspotTethering.Result.UNSUPPORTED -> return Outcome.HOTSPOT_UNSUPPORTED
                            CarHotspotTethering.Result.READY -> if (!attemptCancelled() && after.hotspotEnabled != false)
                                return Outcome.HOTSPOT_RESTORED
                            else -> Unit
                        }
                        stableSince = null
                        nextAttempt = now() + RETRY_MILLIS
                    }
                }
                pause(minOf(250L, (deadline - now()).coerceAtLeast(1L)))
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: SecurityException) {
            return Outcome.PERMISSION_REQUIRED
        } catch (_: ReflectiveOperationException) {
            return Outcome.HOTSPOT_UNSUPPORTED
        } catch (_: RuntimeException) {
            return Outcome.HOTSPOT_START_FAILED
        }
        return Outcome.CANCELLED
    }
}
