package com.shilapi.xcertplay

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ts7SettingsScreenshotsTest {
    @Test fun writesCompactAndFullSettingsScreenshotsWithLargeText() {
        val app = RuntimeEnvironment.getApplication()
        val previousConfiguration = Configuration(app.resources.configuration)
        try {
            for ((widthDp, name) in listOf(600 to "compact-600dp", 1280 to "full-1280dp")) {
                RuntimeEnvironment.setQualifiers("en-w${widthDp}dp-h720dp-mdpi")
                val configuration = Configuration(app.resources.configuration).apply {
                    fontScale = 1.3f
                    screenWidthDp = widthDp
                    screenHeightDp = 720
                }
                @Suppress("DEPRECATION")
                app.resources.updateConfiguration(configuration, app.resources.displayMetrics)
                AirPlayPersistence.saveClusterMapEnabled(app, true)
                AirPlayPersistence.saveCenterMapOverlay(app, true)
                AirPlayPersistence.saveCenterMapAutoHide(app, true)
                val activity = Robolectric.buildActivity(
                    DiPlayActivity::class.java,
                    Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"),
                ).setup().get()
                try {
                    ReflectionHelpers.setField(activity, "settingsTs7Override", true)
                    val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                    val height = (720 * activity.resources.displayMetrics.density).toInt()
                    val directory = File("build/reports/ts7-settings").apply { mkdirs() }
                    fun capture(suffix: String, configure: (DiPlayActivity) -> Unit) {
                        configure(activity)
                        val decor = activity.window.decorView
                        decor.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                        )
                        decor.layout(0, 0, width, height)
                        assertTrue("$suffix screen must render at ${widthDp}dp", decor.width > 0 && decor.height > 0)

                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            decor.draw(Canvas(bitmap))
                            val filename = if (suffix == "advanced") "$name-font-1.3.png"
                                else "$name-$suffix-font-1.3.png"
                            val screenshot = File(directory, filename)
                            screenshot.outputStream().use { output ->
                                assertTrue("PNG encoder must write ${screenshot.name}",
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }

                    capture("advanced") { screen ->
                        ReflectionHelpers.setField(screen, "page", "settings")
                        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
                        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
                            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.ADVANCED))
                    }
                    capture("home") { screen ->
                        ReflectionHelpers.setField(screen, "page", "home")
                        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
                    }
                    capture("connection") { screen ->
                        ReflectionHelpers.setField(screen, "page", "settings")
                        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
                        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSettingsCategory",
                            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.CONNECTION))
                    }
                } finally {
                    activity.finish()
                }
            }
        } finally {
            app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
            RuntimeEnvironment.setQualifiers("en")
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(previousConfiguration, app.resources.displayMetrics)
        }
    }
}
