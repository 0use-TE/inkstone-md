package com.example.inkstone

import org.junit.Assert.*
import org.junit.Test

class OutlineTreeTest {
    private fun heading(index: Int, level: Int, text: String) = OutlineHeading(index, level, text)

    @Test fun skippedLevelsUseActualDepthAndClosestLowerPredecessor() {
        val headings = listOf(
            heading(10, 1, "Root"),
            heading(20, 3, "Gap"),
            heading(30, 5, "Deep"),
            heading(40, 2, "Back"),
            heading(50, 4, "Child"),
            heading(60, 1, "Next")
        )
        val nodes = OutlineTree.build(headings)
        assertEquals(headings, nodes.map { it.heading })
        assertEquals(listOf(0, 1, 2, 1, 2, 0), nodes.map { it.depth })
        assertEquals(
            listOf(null, nodes[0].key, nodes[1].key, nodes[0].key, nodes[3].key, null),
            nodes.map { it.parentKey }
        )
        assertEquals(listOf(true, true, false, true, false, false), nodes.map { it.hasChildren })
        assertEquals(listOf(nodes[0].key, nodes[1].key), OutlineTree.ancestors(nodes, 30))
        assertTrue(OutlineTree.ancestors(nodes, 10).isEmpty())
    }

    @Test fun firstHeadingCanStartAtAnyLevel() {
        val nodes = OutlineTree.build(listOf(
            heading(0, 4, "First"), heading(1, 6, "Child"), heading(2, 2, "New root")
        ))
        assertEquals(listOf(0, 1, 0), nodes.map { it.depth })
        assertNull(nodes[0].parentKey)
        assertNull(nodes[2].parentKey)
    }

    @Test fun duplicateLabelsHaveUniqueKeysWithinAndAcrossParents() {
        val headings = listOf(
            heading(0, 1, "Same"), heading(1, 3, "Same"), heading(2, 3, "Same"),
            heading(3, 1, "Same"), heading(4, 3, "Same"), heading(5, 1, ""),
            heading(6, 1, "../a/:0"), heading(7, 1, "中文 👋")
        )
        val nodes = OutlineTree.build(headings)
        assertEquals(nodes.size, nodes.map { it.key }.toSet().size)
        assertEquals(headings.map { it.text }, nodes.map { it.heading.text })
        assertEquals(nodes, OutlineTree.build(headings))
        assertFalse(nodes[6].key.contains(".."))
    }

    @Test fun unrelatedSiblingInsertionDoesNotChangeExistingKeys() {
        val before = listOf(
            heading(0, 1, "Root"), heading(1, 2, "A"), heading(2, 2, "A"),
            heading(3, 3, "Deep"), heading(4, 1, "Other")
        )
        // Also shift all heading indices, as an editor extraction would after insertion.
        val after = listOf(
            heading(0, 1, "Inserted root"), heading(1, 1, "Root"),
            heading(2, 2, "Inserted child"), heading(3, 2, "A"),
            heading(4, 2, "A"), heading(5, 3, "Deep"), heading(6, 1, "Other")
        )
        val originalKeys = OutlineTree.build(before).map { it.key }
        val remainingKeys = OutlineTree.build(after)
            .filterNot { it.heading.text.startsWith("Inserted") }.map { it.key }
        assertEquals(originalKeys, remainingKeys)
    }

    @Test fun expandingParentPreservesNestedCollapseFlags() {
        val nodes = OutlineTree.build(listOf(
            heading(10, 1, "Root"), heading(20, 3, "Nested"),
            heading(30, 5, "Leaf"), heading(40, 2, "Sibling"), heading(50, 1, "Other")
        ))
        val collapsed = setOf(nodes[0].key, nodes[1].key)
        assertEquals(listOf(10, 50), OutlineTree.visible(nodes, collapsed).map { it.heading.index })
        val expandedParent = collapsed - nodes[0].key
        val partiallyVisible = OutlineTree.visible(nodes, expandedParent)
        assertEquals(listOf(10, 20, 40, 50), partiallyVisible.map { it.heading.index })
        assertTrue(partiallyVisible[1].hasChildren)
        assertEquals(setOf(nodes[0].key, nodes[1].key), collapsed)
        assertEquals(nodes, OutlineTree.visible(nodes, emptySet()))
        assertEquals(nodes, OutlineTree.visible(nodes, setOf("unknown", nodes[2].key)))
    }

    @Test fun hiddenActiveHeadingFallsBackToNearestVisibleAncestor() {
        val nodes = OutlineTree.build(listOf(
            heading(10, 1, "Root"), heading(20, 3, "Nested"),
            heading(30, 5, "Leaf"), heading(40, 1, "Other")
        ))
        assertEquals(30, OutlineTree.visibleActiveIndex(nodes, emptySet(), 30))
        assertEquals(20, OutlineTree.visibleActiveIndex(nodes, setOf(nodes[1].key), 30))
        assertEquals(10, OutlineTree.visibleActiveIndex(nodes, setOf(nodes[0].key, nodes[1].key), 30))
        assertEquals(20, OutlineTree.visibleActiveIndex(nodes, setOf(nodes[1].key), 20))
        assertEquals(40, OutlineTree.visibleActiveIndex(nodes, setOf(nodes[0].key), 40))
        assertNull(OutlineTree.visibleActiveIndex(nodes, emptySet(), 999))
        assertTrue(OutlineTree.ancestors(nodes, 999).isEmpty())
    }

    @Test fun emptyOutlineHasNoVisibleOrActiveHeading() {
        val nodes = OutlineTree.build(emptyList())
        assertTrue(nodes.isEmpty())
        assertTrue(OutlineTree.visible(nodes, setOf("stale")).isEmpty())
        assertTrue(OutlineTree.ancestors(nodes, 0).isEmpty())
        assertNull(OutlineTree.visibleActiveIndex(nodes, setOf("stale"), 0))
    }
}
