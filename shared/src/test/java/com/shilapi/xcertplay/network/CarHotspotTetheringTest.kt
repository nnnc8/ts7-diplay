package com.shilapi.xcertplay.network

import android.os.ResultReceiver
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.shilapi.xcertplay.network.CarHotspotTethering.Result

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotTetheringTest {
    @Test fun startFailuresNameTheUnderlyingCause() {
        assertEquals("NoSuchFieldException: mService", CarHotspotTethering.describe(NoSuchFieldException("mService")))
        assertEquals("InvocationTargetException caused by SecurityException: uid 10123",
            CarHotspotTethering.describe(InvocationTargetException(SecurityException("uid 10123"))))
    }

    @Test fun existingHotspotNeedsNoGrantOrStartCall() {
        assertEquals(Result.READY, enable(canWrite = false, state = { true }))
    }

    @Test fun missingPermissionDoesNotTouchTheHotspot() {
        assertEquals(Result.PERMISSION_REQUIRED, enable(canWrite = false))
    }

    @Test fun unreadableStateIsUnsupportedWithoutStartingBlindly() {
        assertEquals(Result.UNSUPPORTED, enable(state = { null }))
    }

    @Test fun successfulRequestStillWaitsForActualHotspotState() {
        var calls = 0
        assertEquals(Result.TIMED_OUT, enable(start = { calls++; it.send(0, null) }))
        assertEquals(1, calls)
    }

    @Test fun actualEnabledStateCompletesStartup() {
        var on = false
        assertEquals(Result.READY, enable(state = { on }, start = { on = true }))
    }

    @Test fun asynchronousEnabledStateIsObserved() {
        val on = AtomicBoolean(false)
        var completion: Thread? = null
        val result = CarHotspotTethering.enable(1_000, { false }, { true }, { on.get() }) {
            completion = thread { on.set(true) }
        }
        completion!!.join(1_000)
        assertEquals(Result.READY, result)
    }

    @Test fun platformRejectionIsReported() {
        assertEquals(Result.FAILED, enable(start = { it.send(1, null) }))
    }

    @Test fun missingBinderMethodIsUnsupported() {
        assertEquals(Result.UNSUPPORTED, enable(start = { throw NoSuchMethodException() }))
    }

    @Test fun binderPermissionFailureIsReportedSeparately() {
        assertEquals(Result.PERMISSION_REQUIRED,
            enable(start = { throw InvocationTargetException(SecurityException()) }))
    }

    @Test fun otherBinderFailureIsNotReportedAsPermissionReady() {
        assertEquals(Result.FAILED,
            enable(start = { throw InvocationTargetException(IllegalStateException()) }))
    }

    @Test fun missingBinderMethodFallsBackToAdb() {
        var on = false
        val result = CarHotspotTethering.enable(
            1_000, { false }, { true }, { on },
            startFallback = { on = true; true },
            start = { throw NoSuchMethodException() },
        )
        assertEquals(Result.READY, result)
    }

    @Test fun securityExceptionFallsBackToAdb() {
        var on = false
        val result = CarHotspotTethering.enable(
            1_000, { false }, { true }, { on },
            startFallback = { on = true; true },
            start = { throw InvocationTargetException(SecurityException()) },
        )
        assertEquals(Result.READY, result)
    }

    @Test fun failedAdbFallbackReportsOriginalDiagnostic() {
        val result = CarHotspotTethering.enable(
            20, { false }, { true }, { false },
            startFallback = { false },
            start = { throw NoSuchMethodException() },
        )
        assertEquals(Result.UNSUPPORTED, result)
    }

    @Test fun cancellationDuringReflectionFailureDoesNotStartTheFallback() {
        var cancelled = false
        var fallbackCalls = 0
        val result = CarHotspotTethering.enable(1_000, { cancelled }, { true }, { false },
            startFallback = { fallbackCalls++; true },
            start = { cancelled = true; throw SecurityException() })
        assertEquals(Result.CANCELLED, result)
        assertEquals(0, fallbackCalls)
    }

    @Test fun anExpiredReflectionAttemptDoesNotGetAnotherAdbBudget() {
        var fallbackCalls = 0
        val result = CarHotspotTethering.enable(20, { false }, { true }, { false },
            startFallback = { fallbackCalls++; true },
            start = { Thread.sleep(30); throw NoSuchMethodException() })
        assertEquals(Result.TIMED_OUT, result)
        assertEquals(0, fallbackCalls)
    }

    @Test fun unknownAppStateCanUseAFallbackWhichIndependentlyObservesTheAp() {
        var observed: Boolean? = null
        val result = CarHotspotTethering.enable(1_000, { false }, { true }, { observed },
            startFallback = { observed = true; true }, start = { fail("Do not invoke reflection with unknown state") })
        assertEquals(Result.READY, result)
    }

    @Test fun acceptingAFallbackRequestDoesNotInventObservableReadiness() {
        val result = CarHotspotTethering.enable(20, { false }, { true }, { null },
            startFallback = { true }, start = { fail("Do not start blindly") })
        assertEquals(Result.TIMED_OUT, result)
    }

    @Test fun anAdbEnabledObservationCannotMaskALaterPlatformOffState() {
        val observed = AtomicReference<Boolean?>()
        val state = CarHotspotTethering.stateWithAdbObservation({ false }, observed)
        val result = CarHotspotTethering.enable(20, { false }, { true }, state,
            startFallback = { observed.set(true); true }, start = { throw NoSuchMethodException() })
        assertEquals(Result.TIMED_OUT, result)
    }

    @Test fun anAdbEnabledObservationAllowsHiddenPlatformStatusToCompleteStartup() {
        val observed = AtomicReference<Boolean?>()
        val state = CarHotspotTethering.stateWithAdbObservation({ null }, observed)
        val result = CarHotspotTethering.enable(1_000, { false }, { true }, state,
            startFallback = { observed.set(true); true }, start = { fail("Do not start blindly") })
        assertEquals(Result.READY, result)
    }

    @Test fun aFallbackExceptionPreservesTheOriginalPermissionDiagnostic() {
        val result = CarHotspotTethering.enable(1_000, { false }, { true }, { false },
            startFallback = { throw IllegalStateException("ADB unavailable") }, start = { throw SecurityException() })
        assertEquals(Result.PERMISSION_REQUIRED, result)
    }

    @Test fun cancellationWhileReadingEnabledStateCannotReportReady() {
        var cancelled = false
        assertEquals(Result.CANCELLED, enable(cancelled = { cancelled }, state = {
            cancelled = true
            true
        }))
    }

    @Test fun anExpiredStatusReadCannotReportReadyOrStartAnything() {
        val result = CarHotspotTethering.enable(20, { false }, { true }, {
            Thread.sleep(30)
            true
        }, startFallback = { fail("No fallback after expiry"); false },
            start = { fail("No reflection after expiry") })
        assertEquals(Result.TIMED_OUT, result)
    }

    @Test fun anInterruptedFallbackPreservesCancellation() {
        try {
            val result = CarHotspotTethering.enable(1_000, { false }, { true }, { null },
                startFallback = { throw InterruptedException() }, start = { fail("Do not start blindly") })
            assertEquals(Result.CANCELLED, result)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun cancelledRequestDoesNotStartTheHotspot() {
        assertEquals(Result.CANCELLED, enable(cancelled = { true }))
    }

    @Test fun cancellationDuringStartupStopsWaitingWithoutAnotherRequest() {
        var cancelled = false
        var calls = 0
        assertEquals(Result.CANCELLED, enable(cancelled = { cancelled }, start = {
            calls++
            cancelled = true
        }))
        assertEquals(1, calls)
    }

    @Test fun concurrentRequestsShareOneStartAndRecheckStateAfterWaiting() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val on = AtomicBoolean(false)
        val calls = AtomicInteger(0)
        var first: Result? = null
        var second: Result? = null
        val one = thread {
            first = CarHotspotTethering.enable(1_000, { false }, { true }, { on.get() }) {
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                on.set(true)
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val two = thread {
            second = CarHotspotTethering.enable(1_000, { false }, { true }, { on.get() }) {
                calls.incrementAndGet()
            }
        }
        release.countDown()
        one.join(2_000); two.join(2_000)
        assertFalse(one.isAlive); assertFalse(two.isAlive)
        assertEquals(Result.READY, first); assertEquals(Result.READY, second)
        assertEquals(1, calls.get())
    }

    @Test fun anExistingEnablingStateWaitsWithoutStartingAgain() {
        val clock = FakeClock()
        var calls = 0
        val result = enableRaw(clock, timeoutMillis = 2_000, rawState = {
            if (clock.nowMillis >= 500L) 13 else 12
        }, start = { calls++ })

        assertEquals(Result.READY, result)
        assertEquals(0, calls)
        assertEquals(500L, clock.nowMillis)
    }

    @Test fun disablingStateWaitsForTheApToSettleBeforeStarting() {
        val clock = FakeClock()
        var calls = 0
        var apState = 10
        val result = enableRaw(clock, timeoutMillis = 3_000, rawState = { apState }, start = {
            calls++
            apState = 13
        })

        assertEquals(Result.READY, result)
        assertEquals(1, calls)
        assertEquals(2_000L, clock.nowMillis)
    }

    @Test fun noChannelFailureReturnsImmediatelyWithAnActionableResult() {
        val clock = FakeClock()
        var apState = 11
        var calls = 0
        val result = enableRaw(clock, rawState = { apState }, failureReason = { 1 }, start = {
            calls++
            apState = 14
        })

        assertEquals(Result.NO_CHANNEL, result)
        assertEquals(1, calls)
        assertEquals(0L, clock.nowMillis)
    }

    @Test fun otherApFailuresRetryTwiceThenReturnFailed() {
        val clock = FakeClock()
        var apState = 11
        var calls = 0
        val result = enableRaw(clock, rawState = {
            apState.also { if (it == 12) apState = 14 }
        }, failureReason = { 2 }, start = {
            calls++
            apState = 12
        })

        assertEquals(Result.FAILED, result)
        assertEquals(3, calls)
        // Each round: ENABLING for one poll, FAILED, then the 500 ms pause before the next request.
        assertEquals(1_750L, clock.nowMillis)
    }

    @Test fun aFailureRetryIsNotCutShortByLeftoverFractionsOfAMillisecond() {
        // Each pause and status read costs a little more than whole milliseconds, as on a real unit.
        var nanos = 0L
        var apState = 11
        var calls = 0
        val result = CarHotspotTethering.enableWithRawState(
            timeoutMillis = 10_000,
            isCancelled = { false },
            canWrite = { true },
            isEnabled = { apState == 13 },
            rawApState = { apState.also { if (it == 12) apState = 14 } },
            failureReason = { 2 },
            nowNanos = { nanos },
            pauseMillis = { nanos += it * 1_000_000L + 300_000L },
            start = {
                calls++
                apState = 12
            },
        )

        assertEquals(Result.FAILED, result)
        assertEquals(3, calls)
    }

    @Test fun aFailureLeftFromAnEarlierAttemptDoesNotDecideTheNewRequest() {
        val clock = FakeClock()
        var calls = 0
        val result = enableRaw(clock, rawState = {
            when {
                calls == 0 || clock.nowMillis < 250L -> 14
                clock.nowMillis < 500L -> 12
                else -> 13
            }
        }, failureReason = { 1 }, start = { calls++ })

        assertEquals(Result.READY, result)
        assertEquals(1, calls)
        assertEquals(500L, clock.nowMillis)
    }

    @Test fun aRepeatedFailureAfterTheGracePeriodStillReportsNoChannel() {
        val clock = FakeClock()
        var calls = 0
        val result = enableRaw(clock, timeoutMillis = 5_000, rawState = { 14 }, failureReason = { 1 }, start = {
            calls++
        })

        assertEquals(Result.NO_CHANNEL, result)
        assertEquals(1, calls)
        assertEquals(2_000L, clock.nowMillis)
    }

    @Test fun aRejectedRequestFailsWithoutRetryingAStaleFailure() {
        val clock = FakeClock()
        var calls = 0
        val result = enableRaw(clock, rawState = { 14 }, failureReason = { 2 }, start = {
            calls++
            it.send(3, null)
        })

        assertEquals(Result.FAILED, result)
        assertEquals(1, calls)
        assertEquals(0L, clock.nowMillis)
    }

    @Test fun acceptedRequestStuckAtIdleIsRetriedOnlyOnceAfterThreeSeconds() {
        val clock = FakeClock()
        var calls = 0
        val result = enableRaw(clock, timeoutMillis = 5_000, rawState = { 11 }, start = {
            calls++
            it.send(0, null)
        })

        assertEquals(Result.TIMED_OUT, result)
        assertEquals(2, calls)
        assertEquals(5_000L, clock.nowMillis)
    }

    @Test fun ts7EnablingProgressExtendsOnlyToTheConfiguredCap() {
        val clock = FakeClock()
        val result = enableRaw(clock, timeoutMillis = 500, maxTimeoutMillis = 1_000,
            extendWhileEnabling = true, rawState = { 12 }, start = { fail("Do not start while enabling") })

        assertEquals(Result.TIMED_OUT, result)
        assertEquals(1_000L, clock.nowMillis)
    }

    private fun enable(
        canWrite: Boolean = true,
        state: () -> Boolean? = { false },
        cancelled: () -> Boolean = { false },
        start: (ResultReceiver) -> Unit = { fail("Unexpected hotspot mutation") },
    ): Result = CarHotspotTethering.enable(20, cancelled, { canWrite }, state, start)

    private fun enableRaw(
        clock: FakeClock,
        timeoutMillis: Long = 2_000,
        maxTimeoutMillis: Long = timeoutMillis,
        extendWhileEnabling: Boolean = false,
        rawState: () -> Int?,
        failureReason: () -> Int? = { null },
        start: (ResultReceiver) -> Unit,
    ): Result = CarHotspotTethering.enableWithRawState(
        timeoutMillis = timeoutMillis,
        isCancelled = { false },
        canWrite = { true },
        isEnabled = { rawState()?.let { it == 13 } },
        rawApState = rawState,
        failureReason = failureReason,
        maxTimeoutMillis = maxTimeoutMillis,
        extendWhileEnabling = extendWhileEnabling,
        nowNanos = clock::nowNanos,
        pauseMillis = clock::pause,
        start = start,
    )

    private class FakeClock {
        var nowMillis: Long = 0
            private set

        fun nowNanos(): Long = nowMillis * 1_000_000L

        fun pause(millis: Long) {
            nowMillis += millis
        }
    }
}
