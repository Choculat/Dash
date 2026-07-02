package sh.margot.dash.services

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/** A tappable list row with a visible ripple + comfortable touch target, so it reads as clickable. */
fun clickableRow(context: Context, text: CharSequence, onClick: () -> Unit): TextView {
    val bg = TypedValue().also { context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
    val density = context.resources.displayMetrics.density
    return TextView(context).apply {
        this.text = text
        setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        minHeight = (48 * density).toInt()
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setBackgroundResource(bg.resourceId)
        setOnClickListener { onClick() }
    }
}
