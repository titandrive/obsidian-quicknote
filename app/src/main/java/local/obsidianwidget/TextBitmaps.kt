package local.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import kotlin.math.ceil

/**
 * Draws styled text to bitmaps for the widget. Launchers only apply fonts bundled in the app,
 * so this is the only way to show the vault's own font file on the home screen.
 */
object TextBitmaps {
    /** Tallest strip one row image may be; taller text is cut at line breaks into several, to stay under the IPC size limit. */
    private const val STRIP = 480

    fun paint(ctx: Context, typeface: Typeface, sizeSp: Float, color: Int) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        this.color = color
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, ctx.resources.displayMetrics)
    }

    /** [text] wrapped to [width], as one or more strips to stack top to bottom. */
    fun block(ctx: Context, text: CharSequence, width: Int, paint: TextPaint): List<Bitmap> {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(true)
            .build()
        val strips = ArrayList<Bitmap>()
        var top = 0
        var line = 0
        while (line < layout.lineCount) {
            var end = line
            while (end + 1 < layout.lineCount && layout.getLineBottom(end + 1) - top <= STRIP) end++
            val bottom = if (end == layout.lineCount - 1) layout.height else layout.getLineBottom(end)
            strips.add(draw(ctx, layout, width, top, bottom))
            top = bottom
            line = end + 1
        }
        return strips.ifEmpty { listOf(draw(ctx, layout, width, 0, layout.height)) }
    }

    /** One line, ellipsized to [maxWidth], only as wide as it needs to be. */
    fun line(ctx: Context, text: String, maxWidth: Int, paint: TextPaint): Bitmap {
        val fitted = TextUtils.ellipsize(text, paint, maxWidth.toFloat(), TextUtils.TruncateAt.END)
        val width = ceil(paint.measureText(fitted, 0, fitted.length)).toInt().coerceIn(1, maxWidth.coerceAtLeast(1))
        val layout = StaticLayout.Builder.obtain(fitted, 0, fitted.length, paint, width).setIncludePad(false).build()
        return draw(ctx, layout, width, 0, layout.height)
    }

    private fun draw(ctx: Context, layout: StaticLayout, width: Int, top: Int, bottom: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), (bottom - top).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        // So the launcher sizes it in pixels 1:1 rather than scaling from mdpi.
        bmp.density = ctx.resources.displayMetrics.densityDpi
        val canvas = Canvas(bmp)
        canvas.translate(0f, -top.toFloat())
        layout.draw(canvas)
        return bmp
    }

    /** The widget's width on screen in pixels, as the launcher reports it. */
    fun widgetWidth(ctx: Context, id: Int): Int {
        val opts = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
        val portrait = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val dp = opts.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            .takeIf { it > 0 } ?: 250
        return (dp * ctx.resources.displayMetrics.density).toInt()
    }
}
