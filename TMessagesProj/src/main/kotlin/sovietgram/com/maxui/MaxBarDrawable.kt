package sovietgram.com.maxui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import org.telegram.ui.ActionBar.Theme

/** The bottom bar of MAX: a plain, flat, full-width rectangle in the primary surface colour. */
class MaxBarDrawable(private val colorKey: Int = Theme.key_windowBackgroundWhite) : Drawable() {

    private val paint = android.graphics.Paint()

    override fun draw(canvas: Canvas) {
        paint.color = Theme.getColor(colorKey)
        canvas.drawRect(bounds, paint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRect(bounds)
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
