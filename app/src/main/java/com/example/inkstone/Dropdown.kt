package com.example.inkstone

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Flat dropdown anchored to a view. Keeps a margin from the screen edges and
 * flips upward when there is not enough room below, so it is never clipped.
 */
object Dropdown {
    data class Item(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

    fun show(anchor: View, title: String?, items: List<Item>, alignEnd: Boolean) {
        if (items.isEmpty()) return
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val metrics = context.resources.displayMetrics
        val margin = dp(12)
        val gap = dp(4)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(253, 252, 249))
                setStroke(dp(1), Color.rgb(234, 232, 225))
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        if (!title.isNullOrBlank()) {
            card.addView(TextView(context).apply {
                text = title; textSize = 11f; setTextColor(Color.rgb(146, 154, 142))
                setPadding(dp(14), dp(9), dp(14), dp(5)); includeFontPadding = false; maxLines = 1
            })
        }
        lateinit var popup: PopupWindow
        items.forEach { item ->
            val row = TextView(context).apply {
                text = item.label; textSize = 14.5f
                setTextColor(if (item.destructive) Color.rgb(178, 48, 48) else Color.rgb(42, 52, 46))
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
                minHeight = dp(44); includeFontPadding = false
                isClickable = true; isFocusable = true
                foreground = RippleDrawable(ColorStateList.valueOf(0x142D503F), null, null)
                setOnClickListener { popup.dismiss(); item.onClick() }
            }
            card.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
        }

        popup = PopupWindow(card, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isFocusable = true
            elevation = dp(10).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        val widthSpec = View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.AT_MOST)
        card.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val panelWidth = card.measuredWidth
        val panelHeight = card.measuredHeight

        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val anchorLeft = location[0]
        val anchorRight = location[0] + anchor.width
        val anchorTop = location[1]
        val anchorBottom = location[1] + anchor.height

        val desiredX = if (alignEnd) anchorRight - panelWidth - margin else anchorLeft
        val x = desiredX.coerceIn(margin, (metrics.widthPixels - panelWidth - margin).coerceAtLeast(margin))
        val below = anchorBottom + gap
        val y = if (below + panelHeight > metrics.heightPixels - margin) {
            (anchorTop - panelHeight - gap).coerceAtLeast(margin)
        } else below
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }
}
