// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.baseline

import android.annotation.TargetApi
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal class BaselineFailure(val code: String) : AssertionError(code)

internal fun demand(value: Boolean, code: String) {
    if (!value) throw BaselineFailure(code)
}

internal fun awaitCheck(code: String, timeout: Long = 10_000, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeout
    while (SystemClock.elapsedRealtime() < deadline) {
        if (condition()) return
        SystemClock.sleep(25)
    }
    throw BaselineFailure(code)
}

/** Platform Instrumentation only: no AndroidX runner, phone simulator or decoder substitute. */
@TargetApi(27)
class BaselineInstrumentation : Instrumentation() {
    private var sourceSha = ""
    private var activity: Activity? = null
    private val stopped = ConcurrentHashMap.newKeySet<Activity>()
    private val results = Bundle()
    private var failures = 0

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        sourceSha = arguments?.getString("source_sha").orEmpty()
        start()
    }

    override fun callActivityOnStop(activity: Activity) {
        super.callActivityOnStop(activity)
        stopped.add(activity)
    }

    override fun onStart() {
        super.onStart()
        val environment = runCheck("api27_environment") {
            demand(sourceSha.matches(Regex("[0-9a-f]{40}")), "SOURCE_SHA_REQUIRED")
            demand(Build.VERSION.SDK_INT == 27, "API27_REQUIRED")
            demand(Build.SUPPORTED_ABIS.firstOrNull() == "x86_64", "X86_64_REQUIRED")
            demand(Build.HARDWARE in setOf("ranchu", "goldfish"), "EMULATOR_REQUIRED")
            demand(targetContext.packageName == "com.shihab.diplay.ts7", "BASELINE_PACKAGE_REQUIRED")
            demand(!File(targetContext.noBackupFilesDir, "offline-mfi").exists(), "AUTH_INPUT_MUST_BE_ABSENT")
            AirPlayPersistence.saveMfiTarget(targetContext, MfiTarget.LOCAL)
        }
        val tests = listOf<Pair<String, () -> Unit>>(
            "classic_home" to ::classicHome,
            "classic_settings_connection" to ::classicSettingsConnection,
            "choose_phone_dialog" to ::choosePhoneDialog,
            "auth_blocked_no_session" to ::authBlockedNoSession,
            "activity_stop_restart" to ::activityStopRestart,
            "native_library_load" to ::nativeLibraryLoad,
            "media_surface_lifecycle" to ::mediaSurfaceLifecycle,
        )
        for ((name, test) in tests) {
            runCheck(name) {
                demand(environment, "ENVIRONMENT_CHECK_FAILED")
                test()
            }
        }
        runCatching { finishActivity() }.onFailure { failures++ }
        results.putString("ts7.api", Build.VERSION.SDK_INT.toString())
        results.putString("ts7.source_sha", sourceSha)
        results.putString("ts7.suite", if (failures == 0) "EMULATOR_PASS" else "FAIL")
        // No exception strings, UI text, codec diagnostics, names or identifiers in results.
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private fun runCheck(name: String, test: () -> Unit): Boolean {
        return try {
            test()
            results.putString("ts7.$name", "PASS")
            sendStatus(0, Bundle().apply { putString("stream", "TS7_TEST $name PASS\n") })
            true
        } catch (error: Throwable) {
            failures++
            results.putString("ts7.$name", "FAIL")
            val code = (error as? BaselineFailure)?.code ?: "PLATFORM_CHECK_FAILED"
            results.putString("ts7.failure_$name", code)
            sendStatus(0, Bundle().apply { putString("stream", "TS7_TEST $name FAIL $code\n") })
            false
        }
    }

    private fun launchHome(): Activity {
        finishActivity()
        val launched = startActivitySync(Intent(targetContext, DiPlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        activity = launched
        waitForIdleSync()
        awaitCheck("CLASSIC_ACTIVITY_NOT_RESUMED") { onMain { launched.hasWindowFocus() && !launched.isFinishing } }
        return launched
    }

    private fun finishActivity() {
        val previous = activity ?: return
        onMain { previous.finish() }
        waitForIdleSync()
        awaitCheck("ACTIVITY_DESTROY_TIMEOUT") { onMain { previous.isDestroyed } }
        activity = null
    }

    private fun classicHome() {
        val home = launchHome()
        requireText(home, text(home, "a_familiar_drive"))
        requireText(home, "AUTH_BLOCKED")
        for (name in listOf("settings", "choose_iphone", "connect_phone")) {
            demand(onMain { findButton(home, text(home, name)) != null }, "CLASSIC_HOME_CONTROL_MISSING")
        }
        demand(!onMain { findButton(home, text(home, "connect_phone"))!!.isEnabled }, "AUTH_BLOCKED_CONNECT_ENABLED")
        assertNoBackgroundSession()
    }

    private fun classicSettingsConnection() {
        val home = activity ?: launchHome()
        click(home, "settings")
        requireText(home, text(home, "your_drive_your_way"))
        click(home, "open_connection_setup")
        requireText(home, text(home, "connection_setup"))
        // The API27 UI must offer the actual upstream LOHS backend.
        val expected = text(home, "localonlyhotspot")
        demand(onMain { descendants(home.window.decorView).filterIsInstance<TextView>()
            .any { it.text.toString().contains(expected) && it.isShown } }, "API27_LOHS_OPTION_MISSING")
        assertNoBackgroundSession()
        click(home, "back")
    }

    private fun choosePhoneDialog() {
        val home = activity ?: launchHome()
        click(home, "choose_iphone")
        val titles = listOf("turn_on_bluetooth", "pair_your_iphone", "choose_your_iphone").map { text(home, it) }
        awaitCheck("CHOOSE_PHONE_DIALOG_MISSING") {
            val root = uiAutomation.rootInActiveWindow ?: return@awaitCheck false
            try { titles.any { accessibilityHasText(root, it) } } finally { root.recycle() }
        }
        // No synthetic paired phone is inserted and no device is selected.
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        waitForIdleSync()
        assertNoBackgroundSession()
    }

    private fun authBlockedNoSession() {
        val home = launchHome()
        requireText(home, "AUTH_BLOCKED")
        exerciseMissingAuthController()
        assertNoBackgroundSession()
    }

    private fun exerciseMissingAuthController() {
        val statuses = CopyOnWriteArrayList<CarPlayStatus>()
        val sessions = AtomicInteger()
        // This ephemeral AirPlay key is not an MFi identity and is never persisted or sent.
        val controller = CarPlayController(
            context = targetContext,
            config = CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                transport = CarPlayTransport.WIRELESS,
                wirelessHotspotMode = WirelessHotspotMode.LOCAL_ONLY_HOTSPOT,
                identification = Iap2IdentificationConfig("synthetic-test", "synthetic-test", "synthetic-test",
                    "synthetic-test", "1", "1", 0),
            ),
            airPlayConfig = AirPlayConfig("synthetic-test", "02:00:00:00:00:02", "02:00:00:00:00:02", "1",
                AirPlayDisplayConfig(160, 96), microphone = false),
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            listener = object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) { sessions.incrementAndGet() }
            },
            media = object : AirPlayMediaHandler {},
            reportStatus = { statuses.add(it) },
        )
        try {
            onMain { controller.start() }
            awaitCheck("AUTH_BLOCKED_STATUS_MISSING") { statuses.any { it is CarPlayStatus.AuthBlocked } }
            demand(statuses.all { it is CarPlayStatus.DiscoveringMfi || it is CarPlayStatus.AuthBlocked },
                "AUTH_BLOCKED_PATH_ADVANCED")
            demand(sessions.get() == 0 && !controller.hasActiveAirPlayAttachment(), "UNAUTHORIZED_SESSION_ACTIVE")
            demand(field(controller, "activeSession") == null, "UNAUTHORIZED_SESSION_CREATED")
        } finally {
            controller.close()
            demand(controller.awaitClosed(8_000) && controller.isClosed(), "CONTROLLER_CLEANUP_TIMEOUT")
        }
    }

    private fun activityStopRestart() {
        val previous = activity ?: launchHome()
        finishActivity()
        demand(stopped.contains(previous), "ACTIVITY_ON_STOP_NOT_OBSERVED")
        val restarted = launchHome()
        demand(restarted !== previous, "ACTIVITY_WAS_NOT_RESTARTED")
        requireText(restarted, "AUTH_BLOCKED")
        exerciseMissingAuthController()
        assertNoBackgroundSession()
    }

    private fun nativeLibraryLoad() {
        for (name in listOf("libxcertplay_i2c.so", "liblocal_hotspot_radio.so")) {
            val library = File(targetContext.applicationInfo.nativeLibraryDir, name)
            demand(library.isFile, "INSTALLED_NATIVE_LIBRARY_MISSING")
            System.load(library.absolutePath)
            demand(File("/proc/self/maps").readText().lineSequence().any { it.contains("/$name") }, "NATIVE_LIBRARY_NOT_MAPPED")
        }
        // Loading ELF only: no native I2C function, radio probe or firmware operation.
    }

    private fun mediaSurfaceLifecycle() {
        val fixture = SyntheticAvc.load(context)
        val sink = AndroidMediaSink(videoWidth = 160, videoHeight = 96)
        val probes = mutableListOf<SurfaceFrameProbe>()
        val workers = mutableListOf<Any>()
        val diagnostics = CopyOnWriteArrayList<String>()
        val states = CopyOnWriteArrayList<Boolean>()
        sink.setScreenStreamActiveChangedListener { type, active -> if (type == 110) states.add(active) }
        fun probe() = SurfaceFrameProbe().also { probes.add(it) }
        fun configure(output: SurfaceFrameProbe): Any {
            sink.setVideoDiagnosticHandler(110) { diagnostics.add(it) } // Test memory only; never emitted.
            sink.setSurface(110, output.surface)
            sink.onScreenStreamActive(110, true)
            sink.onVideoCodec(110, VideoCodec.H264)
            sink.onVideoConfig(110, fixture.config)
            val worker = decoders(sink)[110] ?: throw BaselineFailure("UPSTREAM_DECODER_NOT_CREATED")
            workers.add(worker)
            awaitCheck("MEDIACODEC_NOT_CONFIGURED") { field(worker, "decoder") != null }
            return worker
        }
        fun render(output: SurfaceFrameProbe) {
            val before = output.frames.get()
            for (frame in fixture.frames) {
                sink.onVideoFrame(110, frame)
                SystemClock.sleep(100) // Feed at fixture cadence, below the upstream 250ms queue limit.
            }
            awaitCheck("SURFACE_FRAME_OR_PIXEL_MISSING") {
                output.demandHealthy()
                output.frames.get() >= before + 3 && output.redPixels.get() >= 3 && output.timestamp.get() > 0
            }
        }
        try {
            val first = probe()
            val originalWorker = configure(first)
            render(first)
            demand(diagnostics.any { it == "first frame rendered" }, "UPSTREAM_RENDER_EVENT_MISSING")
            sink.clearSurface(110, first.surface)
            awaitCheck("DECODER_NOT_RELEASED_ON_SURFACE_DETACH") { field(originalWorker, "decoder") == null }
            first.close()
            demand(!first.releasedSurfaceIsValid(), "OLD_SURFACE_STILL_VALID")
            val second = probe()
            sink.setSurface(110, second.surface)
            awaitCheck("DECODER_NOT_RECREATED_ON_NEW_SURFACE") { field(originalWorker, "decoder") != null }
            render(second)
            sink.onScreenStreamActive(110, false)
            awaitWorkerClosed(originalWorker)
            demand(decoders(sink).isEmpty(), "DECODER_RETAINED_AFTER_STREAM_STOP")
            val restartedWorker = configure(second)
            demand(restartedWorker !== originalWorker, "DECODER_WORKER_NOT_RESTARTED")
            render(second)
            sink.close()
            awaitWorkerClosed(restartedWorker)
            demand(decoders(sink).isEmpty() && states.lastOrNull() == false, "SINK_CLOSE_INCOMPLETE")
        } finally {
            sink.close()
            workers.forEach(::awaitWorkerClosed)
            probes.forEach { it.close() }
        }
    }

    private fun awaitWorkerClosed(worker: Any) {
        awaitCheck("DECODER_CLEANUP_TIMEOUT") {
            !(field(worker, "thread") as Thread).isAlive && field(worker, "decoder") == null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decoders(sink: AndroidMediaSink) = field(sink, "videoDecoders") as Map<Int, Any>

    private fun assertNoBackgroundSession() {
        // Inspect the original owner rather than replacing it with a test singleton.
        val type = Class.forName("com.shilapi.xcertplay.CarPlayBackgroundSession")
        val owner = type.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val hasSession = type.getDeclaredMethod("hasSession").apply { isAccessible = true }.invoke(owner) as Boolean
        val active = type.getDeclaredMethod("getActive").apply { isAccessible = true }.invoke(owner) as Boolean
        demand(!hasSession && !active, "BACKGROUND_SESSION_UNEXPECTED")
    }

    private fun text(activity: Activity, name: String): String {
        val id = activity.resources.getIdentifier(name, "string", targetContext.packageName)
        demand(id != 0, "CLASSIC_STRING_RESOURCE_MISSING")
        return activity.getString(id)
    }

    private fun requireText(activity: Activity, expected: String) {
        demand(onMain { descendants(activity.window.decorView).filterIsInstance<TextView>()
            .any { it.isShown && it.text.toString() == expected } }, "CLASSIC_TEXT_MISSING")
    }

    private fun click(activity: Activity, resource: String) {
        val label = text(activity, resource)
        onMain {
            val button = findButton(activity, label) ?: throw BaselineFailure("CLASSIC_BUTTON_MISSING")
            demand(button.isEnabled, "CLASSIC_BUTTON_DISABLED")
            button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
        }
        waitForIdleSync()
        onMain {
            val button = findButton(activity, label) ?: throw BaselineFailure("CLASSIC_BUTTON_MISSING")
            demand(button.getGlobalVisibleRect(Rect()), "CLASSIC_BUTTON_NOT_VISIBLE")
            demand(button.performClick(), "CLASSIC_BUTTON_CLICK_FAILED")
        }
        waitForIdleSync()
    }

    private fun findButton(activity: Activity, label: String) = descendants(activity.window.decorView)
        .filterIsInstance<Button>().firstOrNull { it.isShown && it.text.toString() == label }

    private fun descendants(view: View): List<View> {
        val result = mutableListOf(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) result.addAll(descendants(view.getChildAt(index)))
        return result
    }

    private fun accessibilityHasText(node: AccessibilityNodeInfo, expected: String): Boolean {
        if (node.text?.toString() == expected) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (accessibilityHasText(child, expected)) return true } finally { child.recycle() }
        }
        return false
    }

    private fun <T> onMain(action: () -> T): T {
        var value: T? = null
        var failure: Throwable? = null
        runOnMainSync { try { value = action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
}
