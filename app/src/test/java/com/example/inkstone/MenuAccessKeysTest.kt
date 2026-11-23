package com.example.inkstone

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class MenuAccessKeysTest {
    @Test fun altLettersOpenTheCorrespondingMenu() {
        listOf(KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_V).forEachIndexed { index, key ->
            val shortcut = EditorShortcuts.resolve(key, false, false, true)!!
            assertEquals("menu", shortcut.action)
            assertEquals(MenuAccessKeys.categories[index], shortcut.menu)
            assertFalse(shortcut.requiresEditor)
        }
    }
    @Test fun altGrAndSystemChordsRemainAvailable() {
        assertNull(MenuAccessKeys.resolve(KeyEvent.KEYCODE_E, true, false, true, false))
        assertNull(MenuAccessKeys.resolve(KeyEvent.KEYCODE_E, false, true, true, false))
        assertNull(MenuAccessKeys.resolve(KeyEvent.KEYCODE_E, false, false, true, true))
        assertNull(MenuAccessKeys.resolve(KeyEvent.KEYCODE_E, false, false, false, false))
    }
    @Test fun navigationSkipsDisabledRowsAndWraps() {
        val enabled = listOf(true, false, true)
        assertEquals(0, MenuAccessKeys.nextEnabled(enabled, -1, 1))
        assertEquals(2, MenuAccessKeys.nextEnabled(enabled, 0, 1))
        assertEquals(0, MenuAccessKeys.nextEnabled(enabled, 2, 1))
        assertEquals(2, MenuAccessKeys.nextEnabled(enabled, 0, -1))
        assertNull(MenuAccessKeys.nextEnabled(listOf(false, false), -1, 1))
        assertEquals("视图", MenuAccessKeys.adjacent("文件", -1))
        assertEquals("文件", MenuAccessKeys.adjacent("视图", 1))
    }
}
