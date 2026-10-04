package sovietgram.com.maxui

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBarMenuItem

/**
 * Drawing helpers shared by the screens that take MAX's look: the sizes and fills are the ones MAX's own views use
 * (read from its code), so every screen builds them the same way.
 */
object MaxUi {

    @JvmStatic
    fun dp(value: Float): Int = AndroidUtilities.dp(value)

    @JvmStatic
    fun color(token: Int): Int = MaxInterface.tokenColor(token)

    /** A rounded rectangle of a MAX token, the shape every MAX button, card and sheet is cut from. */
    @JvmStatic
    fun round(fill: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
    }

    /** A sheet: the corners are rounded on the top only. */
    @JvmStatic
    fun topRound(fill: Int, radiusDp: Float): GradientDrawable {
        val r = dp(radiusDp).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(fill)
        }
    }

    /** [fill] rounded by [radiusDp] with the grey touch ripple MAX gives its buttons. */
    @JvmStatic
    fun ripple(fill: Int, radiusDp: Float): Drawable {
        val mask = round(Color.WHITE, radiusDp)
        val pressed = color(MaxTokens.TEXT_PRIMARY) and 0x00FFFFFF or 0x14000000
        return RippleDrawable(ColorStateList.valueOf(pressed), round(fill, radiusDp), mask)
    }

    /**
     * MAX's toolbar actions: 32dp squares with 12dp corners. The chat list's "start a chat" is filled with the accent
     * and carries a white glyph; the search action is filled with the secondary button colour.
     */
    @JvmStatic
    fun toolbarSquare(item: ActionBarMenuItem?, accent: Boolean): Drawable? {
        if (item == null) {
            return null
        }
        val fill = color(if (accent) MaxTokens.BUTTON_PRIMARY else MaxTokens.BUTTON_SECONDARY)
        val background = ripple(fill, 12f)
        item.background = background
        item.setIconColor(if (accent) Color.WHITE else color(MaxTokens.ICON_PRIMARY))
        val params = LinearLayout.LayoutParams(dp(32f), dp(32f))
        params.gravity = Gravity.CENTER_VERTICAL
        params.marginStart = dp(4f)
        params.marginEnd = dp(12f)
        item.layoutParams = params
        val icon = item.iconView
        if (icon != null) {
            val inset = dp(4f)
            icon.setPadding(inset, inset, inset, inset)
        }
        return background
    }

    @JvmStatic
    fun isLight(): Boolean = !org.telegram.ui.ActionBar.Theme.isCurrentThemeDark()

    @JvmStatic
    fun show(view: View?, visible: Boolean) {
        view?.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
