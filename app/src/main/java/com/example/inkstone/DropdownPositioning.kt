package com.example.inkstone

/** Coordinates here are always relative to the app window, never the display. */
data class MenuBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

data class MenuPlacement(val xOffset: Int, val yOffset: Int, val width: Int, val height: Int)

object DropdownPositioning {
    fun visibleInWindow(screen: MenuBounds, originX: Int, originY: Int, root: MenuBounds): MenuBounds {
        val left = maxOf(screen.left - originX, root.left)
        val top = maxOf(screen.top - originY, root.top)
        val right = minOf(screen.right - originX, root.right)
        val bottom = minOf(screen.bottom - originY, root.bottom)
        return if (right > left && bottom > top) MenuBounds(left, top, right, bottom) else root
    }

    fun place(anchor: MenuBounds, visible: MenuBounds, requestedWidth: Int, requestedHeight: Int,
              margin: Int, gap: Int, alignEnd: Boolean): MenuPlacement {
        val horizontalMargin = margin.coerceIn(0, ((visible.width - 1) / 2).coerceAtLeast(0))
        val verticalMargin = margin.coerceIn(0, ((visible.height - 1) / 2).coerceAtLeast(0))
        val left = visible.left + horizontalMargin
        val right = visible.right - horizontalMargin
        val top = visible.top + verticalMargin
        val bottom = visible.bottom - verticalMargin
        val width = requestedWidth.coerceIn(1, (right - left).coerceAtLeast(1))
        val x = (if (alignEnd) anchor.right - width else anchor.left).coerceIn(left, (right - width).coerceAtLeast(left))
        val below = (anchor.bottom + gap).coerceIn(top, bottom.coerceAtLeast(top))
        val spaceBelow = (bottom - below).coerceAtLeast(0)
        val spaceAbove = (anchor.top - gap - top).coerceIn(0, (bottom - top).coerceAtLeast(0))
        val useAbove = requestedHeight > spaceBelow && spaceAbove > spaceBelow
        val height = requestedHeight.coerceIn(1, (if (useAbove) spaceAbove else spaceBelow).coerceAtLeast(1))
        val y = (if (useAbove) anchor.top - gap - height else below).coerceIn(top, (bottom - height).coerceAtLeast(top))
        return MenuPlacement(x - anchor.left, y - anchor.bottom, width, height)
    }
}
