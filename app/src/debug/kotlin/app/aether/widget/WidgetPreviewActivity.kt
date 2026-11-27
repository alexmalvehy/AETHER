package app.aether.widget

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug builds only: the two home-screen widgets, inflated from the very views a launcher is sent, at the A059's
 * 2×2 and 4×2 sizes, over a grey like a wallpaper's. For looking at them without placing them on the home screen.
 * `adb shell am start -n app.aether.debug/app.aether.widget.WidgetPreviewActivity`
 */
class WidgetPreviewActivity : Activity() {
    private val scope: CoroutineScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(54, 70, 72))
            setPadding(0, (120 * density).toInt(), 0, 0)
        }
        setContentView(column)
        scope.launch {
            for ((w, h) in listOf(352 to 164, 164 to 164)) {
                val views = withContext(Dispatchers.Default) { MediaWidget.views(this@WidgetPreviewActivity, w, h) }
                val host = FrameLayout(this@WidgetPreviewActivity)
                column.addView(host, LinearLayout.LayoutParams((w * density).toInt(), (h * density).toInt()).apply { bottomMargin = (40 * density).toInt() })
                host.addView(views.apply(this@WidgetPreviewActivity, host))
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
