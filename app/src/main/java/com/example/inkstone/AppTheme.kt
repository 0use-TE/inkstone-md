package com.example.inkstone

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import java.util.WeakHashMap

enum class ThemeColor { PAPER, SIDEBAR, INK, MUTED, ACCENT, LINE, SELECTED, MENU, DISABLED, DANGER }

/** Theme roles stay on the views so recoloring never rebuilds the editor or native controls. */
object AppTheme {
    private val dialogs = WeakHashMap<AlertDialog, Unit>()
    fun preference(context: Context) = ThemePreference.from(context.getSharedPreferences("document", Context.MODE_PRIVATE).getString("theme", null))
    fun isDark(context: Context) = preference(context).isDark(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    fun color(context: Context, role: ThemeColor): Int {
        val dark = isDark(context)
        return Color.parseColor(when (role) {
            ThemeColor.PAPER -> if (dark) "#1e1e1e" else "#ffffff"
            ThemeColor.SIDEBAR -> if (dark) "#181818" else "#f5f5f5"
            ThemeColor.INK -> if (dark) "#e5e5e5" else "#292929"
            ThemeColor.MUTED -> if (dark) "#aaaaaa" else "#737373"
            ThemeColor.ACCENT -> if (dark) "#dddddd" else "#333333"
            ThemeColor.LINE -> if (dark) "#3b3b3b" else "#e5e5e5"
            ThemeColor.SELECTED -> if (dark) "#383838" else "#e8e8e8"
            ThemeColor.MENU -> if (dark) "#262626" else "#ffffff"
            ThemeColor.DISABLED -> if (dark) "#808080" else "#a3a3a3"
            ThemeColor.DANGER -> if (dark) "#ffaaa3" else "#b23030"
        })
    }
    fun dialogContext(context: Context) = ContextThemeWrapper(context,
        if (isDark(context)) R.style.Inkstone_Dialog_Dark else R.style.Inkstone_Dialog_Light)

    fun dialogBuilder(context: Context): AlertDialog.Builder = object : AlertDialog.Builder(dialogContext(context)) {
        override fun create(): AlertDialog = super.create().also { dialogs[it] = Unit }
    }

    fun refreshDialogs(context: Context) {
        // A system night-mode change can arrive while a find or rename dialog is open.
        // Recolor its existing controls, retaining the text, selection and callbacks.
        dialogs.keys.toList().filter { it.isShowing }.forEach { dialog ->
            dialog.window?.setBackgroundDrawable(ColorDrawable(color(context, ThemeColor.MENU)))
            fun recolor(view: View) {
                if (view is TextView) {
                    val role = view.getTag(R.id.theme_text) as? ThemeColor
                        ?: if (view is android.widget.Button) ThemeColor.ACCENT else ThemeColor.INK
                    view.setTextColor(color(context, role))
                }
                if (view is EditText) {
                    view.setHintTextColor(color(context, ThemeColor.MUTED))
                    view.backgroundTintList = ColorStateList.valueOf(color(context, ThemeColor.ACCENT))
                    if (android.os.Build.VERSION.SDK_INT >= 29) view.textCursorDrawable?.setTint(color(context, ThemeColor.ACCENT))
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) recolor(view.getChildAt(i))
            }
            dialog.window?.decorView?.let(::recolor)
        }
    }

    fun refresh(view: View) {
        (view.getTag(R.id.theme_background) as? ThemeColor)?.let { view.setBackgroundColor(color(view.context, it)) }
        if (view is TextView) (view.getTag(R.id.theme_text) as? ThemeColor)?.let { view.setTextColor(color(view.context, it)) }
        if (view is EditText) {
            view.setHintTextColor(color(view.context, ThemeColor.MUTED))
            view.backgroundTintList = ColorStateList.valueOf(color(view.context, ThemeColor.ACCENT))
            if (android.os.Build.VERSION.SDK_INT >= 29) view.textCursorDrawable?.setTint(color(view.context, ThemeColor.ACCENT))
        }
        view.invalidate()
        if (view is ViewGroup && view !is WebView) for (i in 0 until view.childCount) refresh(view.getChildAt(i))
    }
}

fun View.themeBackground(role: ThemeColor) {
    setTag(R.id.theme_background, role)
    setBackgroundColor(AppTheme.color(context, role))
}
fun TextView.themeText(role: ThemeColor) {
    setTag(R.id.theme_text, role)
    setTextColor(AppTheme.color(context, role))
}
