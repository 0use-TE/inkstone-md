package com.example.inkstone

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class EditorShortcutsTest {
    private fun resolve(code: Int, ctrl: Boolean = true, shift: Boolean = false, alt: Boolean = false, meta: Boolean = false) =
        EditorShortcuts.resolve(code, ctrl, shift, alt, meta)

    @Test fun numberKeysMapToHeadingLevels() {
        for (level in 1..6) {
            val shortcut = resolve(KeyEvent.KEYCODE_1 + level - 1)
            assertNotNull(shortcut)
            assertEquals("heading", shortcut!!.action)
            assertEquals(level, shortcut.level)
            assertTrue(shortcut.requiresEditor)
        }
        val zero = resolve(KeyEvent.KEYCODE_0)
        assertEquals("paragraph", zero!!.action)
    }
    @Test fun formattingKeysMapToEditorCommands() {
        assertEquals("bold", resolve(KeyEvent.KEYCODE_B)!!.action)
        assertEquals("italic", resolve(KeyEvent.KEYCODE_I)!!.action)
        assertEquals("strike", resolve(KeyEvent.KEYCODE_X, shift = true)!!.action)
        assertEquals("codeblock", resolve(KeyEvent.KEYCODE_C, shift = true)!!.action)
        assertEquals("quote", resolve(KeyEvent.KEYCODE_Q)!!.action)
        assertEquals("undo", resolve(KeyEvent.KEYCODE_Z)!!.action)
        assertEquals("redo", resolve(KeyEvent.KEYCODE_Z, shift = true)!!.action)
        assertEquals("redo", resolve(KeyEvent.KEYCODE_Y)!!.action)
    }
    @Test fun imageAndModeShortcutsAreDistinct() {
        assertEquals("image", resolve(KeyEvent.KEYCODE_I, shift = true)!!.action)
        assertEquals("cycleMode", resolve(KeyEvent.KEYCODE_M, shift = true)!!.action)
        assertEquals("toggleSource", resolve(KeyEvent.KEYCODE_SLASH)!!.action)
    }
    @Test fun fileShortcutsAreMapped() {
        assertEquals("save", resolve(KeyEvent.KEYCODE_S)!!.action)
        assertEquals("saveAs", resolve(KeyEvent.KEYCODE_S, shift = true)!!.action)
        assertEquals("open", resolve(KeyEvent.KEYCODE_O)!!.action)
        assertEquals("new", resolve(KeyEvent.KEYCODE_N)!!.action)
    }
    @Test fun searchAndLinkShortcutsAreAvailableOutsideTheEditor() {
        assertEquals("find", resolve(KeyEvent.KEYCODE_F)!!.action)
        assertEquals("replace", resolve(KeyEvent.KEYCODE_H)!!.action)
        assertEquals("link", resolve(KeyEvent.KEYCODE_K)!!.action)
        assertNull(resolve(KeyEvent.KEYCODE_F, shift = true))
        assertNull(resolve(KeyEvent.KEYCODE_H, alt = true))
    }
    @Test fun writingOptionsHaveUnmodifiedFunctionKeys() {
        assertEquals("focusMode", resolve(KeyEvent.KEYCODE_F8, ctrl = false)!!.action)
        assertEquals("typewriterMode", resolve(KeyEvent.KEYCODE_F9, ctrl = false)!!.action)
        assertNull(resolve(KeyEvent.KEYCODE_F8))
        assertNull(resolve(KeyEvent.KEYCODE_F9, ctrl = false, alt = true))
    }
    @Test fun plainKeysWithoutControlAreIgnored() {
        assertNull(resolve(KeyEvent.KEYCODE_B, ctrl = false))
        assertNull(resolve(KeyEvent.KEYCODE_1, ctrl = false))
    }
    @Test fun altGrAndMetaCombinationsAreNotHijacked() {
        assertNull(resolve(KeyEvent.KEYCODE_B, alt = true))
        assertNull(resolve(KeyEvent.KEYCODE_S, meta = true))
    }
    @Test fun shiftNumberDoesNotBecomeHeading() {
        assertNull(resolve(KeyEvent.KEYCODE_1, shift = true))
    }
    @Test fun unmodifiedLetterKeysAreNotConsumed() {
        assertNull(resolve(KeyEvent.KEYCODE_M, shift = false))
        assertNull(resolve(KeyEvent.KEYCODE_SLASH, shift = true))
    }
}
