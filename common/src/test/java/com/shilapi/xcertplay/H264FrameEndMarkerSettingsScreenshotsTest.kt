package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Large-font captures of the experimental frame-marker choice in compact and full Settings layouts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class H264FrameEndMarkerSettingsScreenshotsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    @Config(qualifiers = "en-w320dp-h600dp-xhdpi")
    fun compactSettingsShowTheMarkerAndDescriptionAtLargeFontScale() = capture("compact-en")

    @Test
    @Config(qualifiers = "en-w960dp-h700dp-xhdpi")
    fun fullSettingsShowTheMarkerAndDescriptionAtLargeFontScale() = capture("full-en")

    @Test
    @Config(qualifiers = "ar-ldrtl-w320dp-h600dp-xhdpi")
    fun compactRtlSettingsShowTheMarkerAtLargeFontScale() = capture("compact-ar-rtl")

    private fun capture(name: String) {
        RuntimeEnvironment.setFontScale(1.5f)
        val activity = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get()
        try {
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.ADVANCED)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val decor = activity.window.decorView
            layout(decor, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            layout(root, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)

            val title = activity.getString(R.string.settings_h264_frame_end_marker)
            val row = descendants(root).filterIsInstance<TextView>().single {
                it.text.toString().startsWith("$title · ")
            }
            val description = descendants(root).filterIsInstance<TextView>().single {
                it.text.toString() == activity.getString(R.string.settings_h264_frame_end_marker_description)
            }
            val textLayout = row.layout
            assertTrue(
                "choice label must fit its large-font row",
                textLayout != null && textLayout.height <= row.height - row.paddingTop - row.paddingBottom,
            )
            val scroll = scrollToShowTogether(row, description)

            assertVisible(row, "choice row")
            assertVisible(description, "choice description")
            val viewport = Rect()
            val rowBounds = Rect()
            val descriptionBounds = Rect()
            scroll.getGlobalVisibleRect(viewport)
            row.getGlobalVisibleRect(rowBounds)
            description.getGlobalVisibleRect(descriptionBounds)
            assertTrue("choice row must be inside the captured category viewport", viewport.contains(rowBounds))
            assertTrue("choice description must be inside the captured category viewport", viewport.contains(descriptionBounds))
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(bitmap))
                val directory = File("build/reports/advanced-media-ui").apply { mkdirs() }
                FileOutputStream(File(directory, "$name.png")).use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            activity.finish()
        }
    }

    private fun assertVisible(view: View, name: String) {
        val visible = Rect()
        assertTrue("$name should be visible", view.getGlobalVisibleRect(visible))
        assertTrue("$name should not be clipped", visible.height() >= view.height / 2)
    }

    private fun scrollToShowTogether(first: View, second: View): ScrollView {
        var ancestor: View? = first
        while (ancestor != null && ancestor !is ScrollView) {
            ancestor = ancestor.parent as? View
        }
        val scroll = ancestor as? ScrollView ?: error("Settings category ScrollView not found")
        val firstRect = Rect(0, 0, first.width, first.height)
        val secondRect = Rect(0, 0, second.width, second.height)
        scroll.offsetDescendantRectToMyCoords(first, firstRect)
        scroll.offsetDescendantRectToMyCoords(second, secondRect)
        val target = Rect(firstRect).apply { union(secondRect) }
        val contentHeight = scroll.getChildAt(0).height
        val maxScroll = (contentHeight - scroll.height).coerceAtLeast(0)
        val centeredScroll = target.centerY() - scroll.height / 2
        scroll.scrollTo(0, centeredScroll.coerceIn(0, maxScroll))
        return scroll
    }

    private fun layout(root: View, width: Int, height: Int) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
