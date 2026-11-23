package com.example.inkstone

import android.view.KeyEvent

object MenuAccessKeys {
    val categories = listOf("文件", "编辑", "格式", "视图")
    private val letters = listOf("F", "E", "O", "V")
    fun letter(category: String) = letters[categories.indexOf(category)]
    fun adjacent(category: String, direction: Int): String {
        val index = categories.indexOf(category).coerceAtLeast(0)
        return categories[Math.floorMod(index + direction, categories.size)]
    }
    fun resolve(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean): String? {
        if (!alt || ctrl || shift || meta) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_F -> "文件"
            KeyEvent.KEYCODE_E -> "编辑"
            KeyEvent.KEYCODE_O -> "格式"
            KeyEvent.KEYCODE_V -> "视图"
            else -> null
        }
    }
    fun nextEnabled(enabled: List<Boolean>, focused: Int, direction: Int): Int? {
        if (enabled.isEmpty()) return null
        val start = if (focused in enabled.indices) focused else if (direction > 0) -1 else 0
        for (step in 1..enabled.size) {
            val next = Math.floorMod(start + step * direction, enabled.size)
            if (enabled[next]) return next
        }
        return null
    }
}
