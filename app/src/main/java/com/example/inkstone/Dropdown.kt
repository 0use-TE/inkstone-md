package com.example.inkstone

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/**
 * Flat dropdown anchored to a view. Keeps a margin from the screen edges and
 * flips upward when there is not enough room below, so it is never clipped.
 */
object Dropdown {
    const val TEXT_SIZE_SP = 14.5f
    private var activePopup: PopupWindow? = null
    val isShowing: Boolean get() = activePopup?.isShowing == true
    fun dismiss() { activePopup?.dismiss() }
    data class Item(
        val label: String,
        val destructive: Boolean = false,
        val enabled: Boolean = true,
        val checked: Boolean? = null,
        val shortcut: String? = null,
        val onClick: () -> Unit
    )

    fun show(anchor: View, title: String?, items: List<Item>, alignEnd: Boolean,
             keyboard: Boolean = false, onCategory: ((String) -> Unit)? = null,
             onDismiss: (() -> Unit)? = null, onKeyboardMode: (() -> Unit)? = null) {
        if (items.isEmpty()) return
        activePopup?.dismiss()
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val margin = dp(12)
        val gap = dp(4)
        val root = anchor.rootView
        val rootScreen = IntArray(2).also(root::getLocationOnScreen)
        val rootWindow = IntArray(2).also(root::getLocationInWindow)
        val rootBounds = MenuBounds(rootWindow[0], rootWindow[1], rootWindow[0] + root.width, rootWindow[1] + root.height)
        val visibleScreen = Rect().also(anchor::getWindowVisibleDisplayFrame)
        val visible = DropdownPositioning.visibleInWindow(
            MenuBounds(visibleScreen.left, visibleScreen.top, visibleScreen.right, visibleScreen.bottom),
            rootScreen[0] - rootWindow[0], rootScreen[1] - rootWindow[1], rootBounds)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(AppTheme.color(context, ThemeColor.MENU))
                setStroke(dp(1), AppTheme.color(context, ThemeColor.LINE))
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        if (!title.isNullOrBlank()) {
            card.addView(TextView(context).apply {
                text = title; textSize = TEXT_SIZE_SP; setTextColor(AppTheme.color(context, ThemeColor.MUTED))
                setPadding(dp(14), dp(9), dp(14), dp(5)); includeFontPadding = false; maxLines = 1
            })
        }
        lateinit var popup: PopupWindow
        val rows = mutableListOf<TextView>()
        items.forEach { item ->
            val row = TextView(context).apply {
                val mark = if (item.checked == true) "✓  " else ""
                text = mark + item.label + (item.shortcut?.let { "    $it" } ?: ""); textSize = TEXT_SIZE_SP
                contentDescription = item.label + if (item.checked == true) "，已开启" else ""
                setTextColor(if (!item.enabled) AppTheme.color(context, ThemeColor.DISABLED) else if (item.destructive) AppTheme.color(context, ThemeColor.DANGER) else AppTheme.color(context, ThemeColor.INK))
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
                minHeight = dp(44); includeFontPadding = false
                isEnabled = item.enabled; isClickable = item.enabled; isFocusable = item.enabled
                isFocusableInTouchMode = item.enabled
                setOnFocusChangeListener { view, focused -> view.setBackgroundColor(if (focused) AppTheme.color(context, ThemeColor.SELECTED) else Color.TRANSPARENT) }
                foreground = RippleDrawable(ColorStateList.valueOf((AppTheme.color(context, ThemeColor.ACCENT) and 0x00ffffff) or 0x24000000), null, null)
                if (item.enabled) setOnClickListener { popup.dismiss(); item.onClick() }
            }
            card.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
            rows.add(row)
        }

        val scroll = ScrollView(context).apply { addView(card); isFillViewport = true; isFocusableInTouchMode = true }
        popup = PopupWindow(scroll, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isFocusable = true
            elevation = 0f
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        activePopup = popup
        popup.setOnDismissListener {
            if (activePopup === popup) activePopup = null
            onDismiss?.invoke()
        }
        val keys = View.OnKeyListener { _, keyCode, event ->
            val accessCategory = MenuAccessKeys.resolve(keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed)
            if (accessCategory != null && onCategory != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { popup.dismiss(); onCategory(accessCategory) }
                true
            } else when (keyCode) {
                KeyEvent.KEYCODE_ALT_LEFT -> {
                    if (event.action == KeyEvent.ACTION_DOWN && !event.isCtrlPressed) onKeyboardMode?.invoke()
                    true
                }
                KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN) popup.dismiss()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_TAB -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP || (keyCode == KeyEvent.KEYCODE_TAB && event.isShiftPressed)) -1 else 1
                        MenuAccessKeys.nextEnabled(items.map { it.enabled }, rows.indexOfFirst { it.hasFocus() }, direction)?.let { index ->
                            rows[index].requestFocus()
                            scroll.requestChildRectangleOnScreen(rows[index], Rect(0, 0, rows[index].width, rows[index].height), true)
                        }
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (onCategory != null && title != null && title in MenuAccessKeys.categories) {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            popup.dismiss()
                            onCategory(MenuAccessKeys.adjacent(title, if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1))
                        }
                        true
                    } else false
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) rows.firstOrNull { it.hasFocus() && it.isEnabled }?.performClick()
                    true
                }
                else -> false
            }
        }
        scroll.setOnKeyListener(keys)
        rows.forEach { it.setOnKeyListener(keys) }
        val maxWidth = (visible.width - margin * 2).coerceAtLeast(1)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST)
        card.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val panelWidth = card.measuredWidth.coerceAtLeast(dp(208)).coerceAtMost(maxWidth)
        card.measure(View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))

        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val placement = DropdownPositioning.place(
            MenuBounds(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height),
            visible, panelWidth, card.measuredHeight, margin, gap, alignEnd)
        popup.width = placement.width
        popup.height = placement.height
        // Framework anchoring handles the window's origin and follows window moves.
        // Offsets are relative to the anchor; display coordinates never reach PopupWindow.
        popup.showAsDropDown(anchor, placement.xOffset, placement.yOffset, Gravity.LEFT)
        if (keyboard) scroll.post { rows.firstOrNull { it.isEnabled }?.requestFocus() }
    }
}
