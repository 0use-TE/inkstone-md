package com.example.inkstone

import org.junit.Assert.*
import org.junit.Test

class DropdownPositioningTest {
    private val root = MenuBounds(0, 0, 1200, 800)
    private val anchor = MenuBounds(700, 56, 772, 104)
    @Test fun movingAFreeformWindowDoesNotChangeAnchorOffsets() {
        val full = DropdownPositioning.visibleInWindow(root, 0, 0, root)
        val floating = DropdownPositioning.visibleInWindow(MenuBounds(438, 210, 1638, 1010), 438, 210, root)
        assertEquals(full, floating)
        val first = DropdownPositioning.place(anchor, full, 250, 300, 12, 4, false)
        val second = DropdownPositioning.place(anchor, floating, 250, 300, 12, 4, false)
        assertEquals(first, second)
        assertEquals(0, second.xOffset)
        assertEquals(4, second.yOffset)
    }
    @Test fun rightEdgeKeepsTheWindowMargin() {
        val nearRight = MenuBounds(1130, 56, 1190, 104)
        val placement = DropdownPositioning.place(nearRight, root, 250, 300, 12, 4, false)
        assertEquals(1188, nearRight.left + placement.xOffset + placement.width)
    }
    @Test fun bottomAnchorFlipsUpwards() {
        val bottom = MenuBounds(400, 730, 460, 778)
        val placement = DropdownPositioning.place(bottom, root, 250, 300, 12, 4, false)
        assertEquals(bottom.top - 4, bottom.bottom + placement.yOffset + placement.height)
        assertEquals(300, placement.height)
    }
    @Test fun keyboardAndSmallWindowsConstrainTheScrollPanel() {
        val visible = DropdownPositioning.visibleInWindow(MenuBounds(438, 234, 1638, 610), 438, 210, root)
        val placement = DropdownPositioning.place(anchor, visible, 250, 900, 12, 4, false)
        assertEquals(280, placement.height)
        assertTrue(anchor.bottom + placement.yOffset + placement.height <= 388)
    }
    @Test fun rootBoundsExcludeOtherAppsAndDecorations() {
        assertEquals(root, DropdownPositioning.visibleInWindow(MenuBounds(0, 0, 3504, 2190), 438, 210, root))
        val placement = DropdownPositioning.place(anchor, MenuBounds(0, 0, 10, 10), 300, 300, 12, 4, false)
        assertTrue(placement.width in 1..10)
        assertTrue(placement.height in 1..10)
    }
}
