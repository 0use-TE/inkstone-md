package com.example.inkstone

data class OutlineHeading(val index: Int, val level: Int, val text: String)

data class OutlineNode(
    val heading: OutlineHeading,
    val key: String,
    val depth: Int,
    val parentKey: String?,
    val hasChildren: Boolean
)

/** Pure outline operations. Keys are opaque UI identifiers, never filesystem paths. */
object OutlineTree {
    /** Uses the closest preceding lower-level heading, without inventing missing levels. */
    fun build(headings: List<OutlineHeading>): List<OutlineNode> {
        val nodes = mutableListOf<OutlineNode>()
        val stack = mutableListOf<Int>()
        val siblingCounts = mutableMapOf<Pair<String?, String>, Int>()
        for (heading in headings) {
            while (stack.isNotEmpty() && nodes[stack.last()].heading.level >= heading.level) {
                stack.removeAt(stack.lastIndex)
            }
            val parentIndex = stack.lastOrNull()
            val parentKey = parentIndex?.let { nodes[it].key }
            val sibling = parentKey to heading.text
            val ordinal = siblingCounts[sibling] ?: 0
            siblingCounts[sibling] = ordinal + 1
            // Encode UTF-16 code units losslessly: delimiters and traversal-like labels
            // cannot collide with key structure. Index and sibling position are excluded.
            val label = heading.text.toCharArray().joinToString("") {
                it.code.toString(16).padStart(4, '0')
            }
            val key = "${parentKey.orEmpty()}/$label:$ordinal"
            if (parentIndex != null) {
                nodes[parentIndex] = nodes[parentIndex].copy(hasChildren = true)
            }
            nodes.add(OutlineNode(heading, key, stack.size, parentKey, false))
            stack.add(nodes.lastIndex)
        }
        return nodes.toList()
    }

    /** Hides descendants only; a collapsed heading itself remains visible. */
    fun visible(nodes: List<OutlineNode>, collapsed: Set<String>): List<OutlineNode> {
        var hiddenBelow: Int? = null
        return nodes.filter { node ->
            val boundary = hiddenBelow
            if (boundary != null && node.depth > boundary) {
                false
            } else {
                hiddenBelow = if (node.key in collapsed) node.depth else null
                true
            }
        }
    }

    /** Returns root-to-parent keys, excluding the heading itself; unknown indices yield empty. */
    fun ancestors(nodes: List<OutlineNode>, headingIndex: Int): List<String> {
        val byKey = nodes.associateBy { it.key }
        var parentKey = nodes.firstOrNull { it.heading.index == headingIndex }?.parentKey
        val keys = mutableListOf<String>()
        while (parentKey != null) {
            keys.add(parentKey)
            parentKey = byKey[parentKey]?.parentKey
        }
        return keys.asReversed()
    }

    /** Returns a heading.index, not a list position; unknown indices yield null. */
    fun visibleActiveIndex(
        nodes: List<OutlineNode>,
        collapsed: Set<String>,
        headingIndex: Int
    ): Int? {
        val byKey = nodes.associateBy { it.key }
        val visibleKeys = visible(nodes, collapsed).mapTo(mutableSetOf()) { it.key }
        var active = nodes.firstOrNull { it.heading.index == headingIndex }
        while (active != null) {
            if (active.key in visibleKeys) return active.heading.index
            active = active.parentKey?.let { byKey[it] }
        }
        return null
    }
}
