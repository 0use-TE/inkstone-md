package com.example.inkstone

enum class ThemePreference(val value: String, val label: String) {
    SYSTEM("system", "跟随系统"), LIGHT("light", "浅色"), DARK("dark", "深色");

    fun isDark(systemDark: Boolean) = this == DARK || (this == SYSTEM && systemDark)

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}
