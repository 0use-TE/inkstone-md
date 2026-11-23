package com.example.inkstone

import android.view.KeyEvent

data class EditorShortcut(val action: String, val level: Int = 0, val requiresEditor: Boolean = false, val menu: String? = null)

/** Pure mapping; dispatch consumes a recognized key once, before WebView's own keymap. */
object EditorShortcuts {
    fun resolve(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean = false): EditorShortcut? {
        MenuAccessKeys.resolve(keyCode, ctrl, shift, alt, meta)?.let { return EditorShortcut("menu", menu = it) }
        if (!ctrl && !shift && !alt && !meta) {
            if (keyCode == KeyEvent.KEYCODE_F8) return EditorShortcut("focusMode")
            if (keyCode == KeyEvent.KEYCODE_F9) return EditorShortcut("typewriterMode")
        }
        if (!ctrl || alt || meta) return null // Do not hijack AltGr or system/meta chords.
        if (!shift) {
            val level = when (keyCode) {
                in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_6 -> keyCode - KeyEvent.KEYCODE_1 + 1
                in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_6 -> keyCode - KeyEvent.KEYCODE_NUMPAD_1 + 1
                else -> -1
            }
            if (level in 1..6) return EditorShortcut("heading", level, true)
            if (keyCode == KeyEvent.KEYCODE_0 || keyCode == KeyEvent.KEYCODE_NUMPAD_0) return EditorShortcut("paragraph", requiresEditor = true)
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_S -> EditorShortcut(if (shift) "saveAs" else "save")
            KeyEvent.KEYCODE_O -> if (!shift) EditorShortcut("open") else null
            KeyEvent.KEYCODE_N -> if (!shift) EditorShortcut("new") else null
            KeyEvent.KEYCODE_F -> if (!shift) EditorShortcut("find") else null
            KeyEvent.KEYCODE_H -> if (!shift) EditorShortcut("replace") else null
            KeyEvent.KEYCODE_K -> if (!shift) EditorShortcut("link") else null
            KeyEvent.KEYCODE_B -> if (!shift) EditorShortcut("bold", requiresEditor = true) else null
            KeyEvent.KEYCODE_I -> if (shift) EditorShortcut("image") else EditorShortcut("italic", requiresEditor = true)
            KeyEvent.KEYCODE_X -> if (shift) EditorShortcut("strike", requiresEditor = true) else null
            KeyEvent.KEYCODE_C -> if (shift) EditorShortcut("codeblock", requiresEditor = true) else null
            KeyEvent.KEYCODE_Q -> if (!shift) EditorShortcut("quote", requiresEditor = true) else null
            KeyEvent.KEYCODE_GRAVE -> if (!shift) EditorShortcut("code", requiresEditor = true) else null
            KeyEvent.KEYCODE_Z -> EditorShortcut(if (shift) "redo" else "undo", requiresEditor = true)
            KeyEvent.KEYCODE_Y -> if (!shift) EditorShortcut("redo", requiresEditor = true) else null
            KeyEvent.KEYCODE_M -> if (shift) EditorShortcut("cycleMode") else null
            KeyEvent.KEYCODE_SLASH -> if (!shift) EditorShortcut("toggleSource") else null
            else -> null
        }
    }
}
