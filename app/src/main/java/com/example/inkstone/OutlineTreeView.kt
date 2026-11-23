package com.example.inkstone

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Compact, keyboard-accessible outline rows. Folding is separate from navigation. */
class OutlineTreeView(context: Context) : LinearLayout(context) {
    var onNavigate: (Int) -> Unit = {}
    var onFold: (String) -> Unit = {}
    private var nodes: List<OutlineNode> = emptyList()
    private var collapsed: Set<String> = emptySet()
    private var activeIndex = -1
    private var signature: List<OutlineNode> = emptyList()
    private val rows = linkedMapOf<String, TreeRow>()
    private val ink get() = AppTheme.color(context, ThemeColor.INK)
    private val muted get() = AppTheme.color(context, ThemeColor.MUTED)
    init { orientation = VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(16)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun render(value: List<OutlineNode>, folded: Set<String>, current: Int) {
        nodes = value
        collapsed = folded.toSet()
        activeIndex = current
        val visible = OutlineTree.visible(nodes, collapsed)
        if (childCount == 0 || visible != signature || rows.any { (key, row) -> row.folded != (key in collapsed) }) {
            signature = visible
            removeAllViews(); rows.clear()
            if (nodes.isEmpty()) {
                addView(TextView(context).apply {
                    text = "还没有标题"; textSize = 13f; setTextColor(ink)
                    gravity = Gravity.CENTER; setPadding(0, dp(38), 0, dp(8))
                }, LayoutParams(-1, -2))
                addView(TextView(context).apply {
                    text = "为文字设置标题，即可生成大纲"; textSize = 11f; setTextColor(muted)
                    gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), dp(20))
                }, LayoutParams(-1, -2))
            }
            visible.forEach { node ->
                val row = TreeRow(node, node.key in collapsed)
                rows[node.key] = row
                addView(row, LayoutParams(-1, dp(38)).apply { bottomMargin = dp(1) })
            }
        }
        updateSelection()
    }
    fun refreshTheme() { removeAllViews(); rows.clear(); render(nodes, collapsed, activeIndex) }
    fun setActive(index: Int) { activeIndex = index; updateSelection() }
    private fun updateSelection() {
        val visibleIndex = OutlineTree.visibleActiveIndex(nodes, collapsed, activeIndex)
        rows.values.forEach { it.highlight(it.node.heading.index == visibleIndex) }
    }
    /** Relative to this outline view; caller decides whether to scroll the sidebar. */
    fun activeTop(): Int? {
        val visibleIndex = OutlineTree.visibleActiveIndex(nodes, collapsed, activeIndex)
        return rows.values.firstOrNull { it.node.heading.index == visibleIndex }?.top
    }

    private inner class TreeRow(val node: OutlineNode, val folded: Boolean) : FrameLayout(this@OutlineTreeView.context) {
        private val label: TextView
        private val indicator: View
        init {
            isClickable = true; isFocusable = true
            setWillNotDraw(false)
            foreground = RippleDrawable(ColorStateList.valueOf((AppTheme.color(context, ThemeColor.ACCENT) and 0x00ffffff) or 0x24000000), null, null)
            val indent = dp(8 + node.depth * 14)
            addView(BranchGuides(node.depth), LayoutParams(indent + dp(22), -1))
            indicator = View(context).apply { setBackgroundColor(AppTheme.color(context, ThemeColor.ACCENT)); visibility = INVISIBLE }
            addView(indicator, LayoutParams(dp(2), dp(16), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(1) })
            label = TextView(context).apply {
                text = node.heading.text.ifBlank { "无标题" }
                textSize = 13f; setTextColor(ink); gravity = Gravity.CENTER_VERTICAL
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setTypeface(null, if (node.depth == 0) Typeface.BOLD else Typeface.NORMAL)
            }
            addView(label, LayoutParams(-1, -1).apply { leftMargin = indent + dp(22); rightMargin = dp(8) })
            contentDescription = "${node.heading.level}级标题，${node.heading.text}"
            setOnClickListener { onNavigate(node.heading.index) }
            if (node.hasChildren) {
                addView(Chevron(folded).apply {
                    contentDescription = "${if (folded) "展开" else "折叠"} ${node.heading.text}"
                    isClickable = true; isFocusable = false
                    setOnClickListener { onFold(node.key) }
                }, LayoutParams(dp(26), -1).apply { leftMargin = indent - dp(2) })
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) false
                else when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (node.hasChildren && folded) onFold(node.key)
                        else rows.values.firstOrNull { it.node.parentKey == node.key }?.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (node.hasChildren && !folded) onFold(node.key)
                        else node.parentKey?.let { rows[it] }?.requestFocus()
                        true
                    }
                    else -> false
                }
            }
        }
        fun highlight(value: Boolean) {
            if (isActivated == value && background == null) return
            isActivated = value
            // Flat: no filled card, the marker bar and the text colour carry the state.
            indicator.visibility = if (value) VISIBLE else INVISIBLE
            label.setTextColor(if (value) AppTheme.color(context, ThemeColor.ACCENT) else ink)
            label.setTypeface(null, if (value || node.depth == 0) Typeface.BOLD else Typeface.NORMAL)
        }
    }
    private inner class BranchGuides(private val depth: Int) : View(this@OutlineTreeView.context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppTheme.color(context, ThemeColor.LINE); strokeWidth = resources.displayMetrics.density * 0.65f }
        override fun onDraw(canvas: Canvas) {
            for (i in 0 until depth) {
                val x = dp(18 + i * 14).toFloat()
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            }
        }
    }
    private inner class Chevron(private val folded: Boolean) : View(this@OutlineTreeView.context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AppTheme.color(context, ThemeColor.MUTED); style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.25f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            val x = width / 2f; val y = height / 2f; val size = resources.displayMetrics.density * 2.8f
            val path = Path()
            if (folded) { path.moveTo(x - size / 2, y - size); path.lineTo(x + size / 2, y); path.lineTo(x - size / 2, y + size) }
            else { path.moveTo(x - size, y - size / 2); path.lineTo(x, y + size / 2); path.lineTo(x + size, y - size / 2) }
            canvas.drawPath(path, paint)
        }
    }
}
