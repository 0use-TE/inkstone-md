package com.example.inkstone

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "InkstoneMD"
private const val MAX_IMAGE_INPUT = 32 * 1024 * 1024

class MainActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("document", MODE_PRIVATE) }
    private lateinit var store: DocumentStore
    private val documents = linkedMapOf<String, LocalDocument>()
    private var activeId = ""
    private var currentMd = ""
    private var ready = false
    private var busy = false
    private var documentEpoch = 0
    private var outline = emptyList<OutlineHeading>()
    private var outlineNodes = emptyList<OutlineNode>()
    private var activeHeading = -1
    private val foldedByDocument = mutableMapOf<String, MutableSet<String>>()
    private lateinit var outlineTreeView: OutlineTreeView
    private var outlineTab = false
    private var wideScreen = false
    private var expandedMenus = false
    private var editorMode = "wysiwyg"
    private var sourceLayout = "source"
    private var readOnly = false
    private var focusMode = false
    private var typewriterMode = false
    private var formattingVisible = false
    private lateinit var menuAnchor: View
    private val categoryAnchors = linkedMapOf<String, View>()
    private var accessCuesVisible = false
    private var accessCueIndex = 0
    private var altTapCandidate = false
    private var altWasVisible = false
    private val pendingImages = mutableListOf<Uri>()
    private val pendingImports = java.util.ArrayDeque<Uri>()
    private lateinit var web: WebView
    private lateinit var titleView: TextView
    private lateinit var sidebar: LinearLayout
    private lateinit var sidebarList: LinearLayout
    private lateinit var sideScroll: ScrollView
    private lateinit var filesTabView: TextView
    private lateinit var outlineTabView: TextView
    private lateinit var search: EditText
    private lateinit var scrim: View
    private val active: LocalDocument get() = documents.getValue(activeId)

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) queueImport(uri)
    }
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val id = prefs.getString("pendingExportId", null)
        prefs.edit().remove("pendingExportId").apply()
        if (uri != null && id != null && documents.containsKey(id)) {
            io.execute {
                try { writeExternal(id, uri, store.read(id)) }
                catch (_: Exception) { message("未能读取待保存文档；本机文档仍保留") }
            }
        }
    }
    private val selectImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) insertImage(uri) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        setTheme(if (AppTheme.isDark(this)) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(state)
        applyWindowTheme()
        try { bootstrapLibrary() }
        catch (_: Exception) {
            setContentView(TextView(this).apply { text = "文档库暂时无法读取。请保留应用数据，不要卸载；原始文档没有被删除。"; setPadding(dp(24), dp(40), dp(24), dp(24)) })
            Log.e(TAG, "event=library_load_failed")
            return
        }
        outlineTab = prefs.getBoolean("sidebarOutline", true)
        wideScreen = resources.configuration.screenWidthDp >= 840
        expandedMenus = resources.configuration.screenWidthDp >= 720
        val frame = FrameLayout(this).apply { themeBackground(ThemeColor.PAPER); fitsSystemWindows = true }
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; themeBackground(ThemeColor.PAPER) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(2), dp(8), dp(2)) }
        header.addView(icon("☰", "文档与大纲侧栏") { toggleSidebar() }, LinearLayout.LayoutParams(dp(48), dp(52)))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(8), 0) }
        titleView = TextView(this).apply { textSize = 17f; themeText(ThemeColor.INK); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        titleBox.addView(titleView)
        titleBox.setOnLongClickListener { renameDocument(); true }
        header.addView(titleBox, LinearLayout.LayoutParams(0, -1, 1f))
        if (expandedMenus) MenuAccessKeys.categories.forEach { category ->
            val key = MenuAccessKeys.letter(category)
            val button = smallButton(category, "$category 菜单，Alt+$key") { showCategory(it, category) }.apply {
                textSize = Dropdown.TEXT_SIZE_SP
                themeText(ThemeColor.INK)
                maxLines = 1
                isFocusableInTouchMode = true
            }
            categoryAnchors[category] = button
            header.addView(button, LinearLayout.LayoutParams(dp(80), dp(48)))
        }
        menuAnchor = icon("⋮", if (expandedMenus) "帮助与许可" else "菜单") { showMenu(it) }
        header.addView(menuAnchor, LinearLayout.LayoutParams(dp(48), dp(52)))
        main.addView(header, LinearLayout.LayoutParams(-1, dp(56)))
        main.addView(View(this).apply { themeBackground(ThemeColor.LINE) }, LinearLayout.LayoutParams(-1, dp(1)))
        web = object : WebView(this) {
            override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
                // Hardware menu keys must reach the app before an IME can consume Alt+V.
                if (handleMenuKeyEvent(event)) return true
                return super.dispatchKeyEventPreIme(event)
            }
        }
        web.themeBackground(ThemeColor.PAPER)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
        }
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                assets.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "UTF-8", 403, "Offline editor", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (request.isForMainFrame && uri.scheme in listOf("https", "http") && uri.host != "appassets.androidplatform.net") {
                    AppTheme.dialogBuilder(this@MainActivity).setTitle("打开外部链接？").setMessage(uri.toString())
                        .setNegativeButton("取消", null).setPositiveButton("浏览器打开") { _, _ ->
                            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { message("没有可用浏览器") }
                        }.show()
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) Log.e(TAG, "event=web_error line=${message.lineNumber()}")
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "InkstoneNative")
        main.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        sidebar = buildSidebar()
        scrim = View(this).apply { setBackgroundColor(0x55000000); visibility = View.GONE; setOnClickListener { hideSidebar() } }
        if (wideScreen) {
            val split = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            split.addView(sidebar, LinearLayout.LayoutParams(dp(260), -1))
            split.addView(main, LinearLayout.LayoutParams(0, -1, 1f))
            frame.addView(split, FrameLayout.LayoutParams(-1, -1))
            sidebar.visibility = if (prefs.getBoolean("wideSidebar", true)) View.VISIBLE else View.GONE
        } else {
            frame.addView(main, FrameLayout.LayoutParams(-1, -1))
            frame.addView(scrim, FrameLayout.LayoutParams(-1, -1))
            val width = min(dp(290), (resources.displayMetrics.widthPixels * 0.84f).roundToInt())
            frame.addView(sidebar, FrameLayout.LayoutParams(width, -1, Gravity.START))
            sidebar.visibility = View.GONE
            sidebar.elevation = dp(8).toFloat()
        }
        setContentView(frame)
        AppTheme.refresh(frame)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!wideScreen && sidebar.visibility == View.VISIBLE) hideSidebar()
                else if (ready) withSavedCurrent { finish() } else finish()
            }
        })
        updateTitle()
        renderSidebar()
        web.loadUrl("https://appassets.androidplatform.net/assets/editor/index.html")
        acceptSharedImage(intent)
        acceptDocumentIntent(intent)
        Log.i(TAG, "event=activity_created")
    }

    private fun bootstrapLibrary() {
        store = DocumentStore(File(filesDir, "library"))
        var all = store.list()
        var migrated = false
        if (all.isEmpty()) {
            val legacy = AtomicFile(File(filesDir, "draft.md"))
            val hasLegacy = legacy.baseFile.exists() || File(legacy.baseFile.path + ".bak").exists()
            val text = if (hasLegacy) legacy.openRead().use { readLimited(it, DocumentStore.MAX_BYTES).decodeToString(throwOnInvalidSequence = true) } else null
            val welcome = assets.open("editor/welcome.md").bufferedReader().use { it.readText() }
            all = store.bootstrap(text, prefs.getString("name", "未命名.md") ?: "未命名.md", prefs.getString("uri", null), prefs.getString("savedHash", "") ?: "", welcome)
            migrated = hasLegacy
        }
        all.forEach { documents[it.id] = it }
        activeId = prefs.getString("activeDocumentId", null)?.takeIf { documents.containsKey(it) } ?: all.first().id
        currentMd = store.read(activeId)
        prefs.edit().putString("activeDocumentId", activeId).apply()
        Log.i(TAG, "event=library_ready documents=${documents.size} migrated=$migrated")
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun icon(symbol: String, label: String, click: (View) -> Unit) = TextView(this).apply {
        text = symbol; textSize = 23f; themeText(ThemeColor.INK); gravity = Gravity.CENTER; contentDescription = label
        isClickable = true; isFocusable = true; setOnClickListener(click)
    }
    private fun tab(text: String, click: () -> Unit) = object : TextView(this) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppTheme.color(context, ThemeColor.ACCENT); strokeWidth = dp(2).toFloat() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            line.color = AppTheme.color(context, ThemeColor.ACCENT)
            if (isSelected) canvas.drawLine(width * 0.33f, height - dp(2).toFloat(), width * 0.67f, height - dp(2).toFloat(), line)
        }
    }.apply {
        this.text = text; textSize = 12.5f; gravity = Gravity.CENTER
        setPadding(dp(8), dp(11), dp(8), dp(11)); isClickable = true; isFocusable = true
        setOnClickListener { click() }
    }
    /** Flat text action: no fill, no elevation, only a pressed state. */
    private fun smallButton(label: String, description: String, click: (View) -> Unit) = TextView(this).apply {
        text = label; textSize = 12f; themeText(ThemeColor.ACCENT); contentDescription = description
        gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(4)); includeFontPadding = false
        isClickable = true; isFocusable = true
        setOnClickListener(click)
        stateListAnimator = null
        setOnTouchListener { view, event ->
            view.alpha = if (event.action == android.view.MotionEvent.ACTION_DOWN) 0.55f else 1f
            if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) view.alpha = 1f
            false
        }
    }
    private fun buildSidebar(): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; themeBackground(ThemeColor.SIDEBAR); setPadding(dp(8), dp(10), dp(8), dp(8)) }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(this).apply {
            text = "墨页"; textSize = 15f; themeText(ThemeColor.INK); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        heading.addView(icon("＋", "新建文档") { newDocument() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(heading)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filesTabView = tab("文档") { selectSidebarTab(false) }
        outlineTabView = tab("大纲") { selectSidebarTab(true) }
        tabs.addView(filesTabView, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(outlineTabView, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(tabs)
        search = EditText(this).apply { hint = "搜索文档"; textSize = 13f; themeText(ThemeColor.INK); setHintTextColor(AppTheme.color(context, ThemeColor.MUTED)); setSingleLine(true); setPadding(dp(10), dp(6), dp(10), dp(6)) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!outlineTab) renderEntries() }
            override fun afterTextChanged(s: Editable?) {}
        })
        panel.addView(search, LinearLayout.LayoutParams(-1, dp(44)))
        sidebarList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outlineTreeView = OutlineTreeView(this).apply {
            onNavigate = { index -> navigateOutline(index) }
            onFold = { key ->
                val folded = foldedOutline()
                if (!folded.add(key)) folded.remove(key)
                saveFoldedOutline(); renderOutline()
            }
        }
        sideScroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(sidebarList) }
        panel.addView(sideScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return panel
    }
    private fun toggleSidebar() {
        if (sidebar.visibility == View.VISIBLE) hideSidebar() else {
            hideKeyboard(); sidebar.visibility = View.VISIBLE
            if (!wideScreen) scrim.visibility = View.VISIBLE else prefs.edit().putBoolean("wideSidebar", true).apply()
            renderSidebar()
            js("window.inkstone.refreshOutline && window.inkstone.refreshOutline()")
        }
    }
    private fun hideSidebar() {
        sidebar.visibility = View.GONE; scrim.visibility = View.GONE
        if (wideScreen) prefs.edit().putBoolean("wideSidebar", false).apply()
    }
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(web.windowToken, 0); web.clearFocus() }
    private fun renderSidebar() {
        if (!::sidebarList.isInitialized) return
        filesTabView.themeText(if (outlineTab) ThemeColor.MUTED else ThemeColor.INK)
        outlineTabView.themeText(if (outlineTab) ThemeColor.INK else ThemeColor.MUTED)
        filesTabView.isSelected = !outlineTab
        outlineTabView.isSelected = outlineTab
        filesTabView.invalidate(); outlineTabView.invalidate()
        search.visibility = if (outlineTab) View.GONE else View.VISIBLE
        renderEntries()
    }
    private fun emptyLabel(text: String) = TextView(this).apply { this.text = text; textSize = 12f; themeText(ThemeColor.MUTED); setPadding(dp(10), dp(24), dp(10), dp(10)); setLineSpacing(dp(4).toFloat(), 1f) }
    private fun renderEntries() {
        if (!::sidebarList.isInitialized) return
        if (outlineTab) {
            if (outlineTreeView.parent !== sidebarList) {
                sidebarList.removeAllViews()
                sidebarList.addView(outlineTreeView, LinearLayout.LayoutParams(-1, -2))
            }
            renderOutline()
        } else {
            sidebarList.removeAllViews()
            val query = search.text.toString().trim()
            val visible = documents.values.filter { it.name.contains(query, true) }.sortedByDescending { it.updatedAt }
            if (visible.isEmpty()) sidebarList.addView(emptyLabel("没有找到文档\n点侧栏“＋”新建，或在右上菜单打开文件。"))
            visible.forEach { doc ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(11), dp(10), dp(9), dp(10))
                    setBackgroundColor(if (doc.id == activeId) AppTheme.color(context, ThemeColor.SELECTED) else Color.TRANSPARENT)
                }
                val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                textBox.addView(TextView(this).apply { text = doc.name; textSize = 14f; themeText(ThemeColor.INK); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; if (doc.id == activeId) setTypeface(null, Typeface.BOLD) })
                textBox.addView(TextView(this).apply { text = doc.preview; textSize = 11f; themeText(ThemeColor.MUTED); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) })
                row.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
                row.setOnClickListener { if (doc.id != activeId) withSavedCurrent { switchDocument(doc.id) } else if (!wideScreen) hideSidebar() }
                row.setOnLongClickListener { showDocumentMenu(doc, row); true }
                sidebarList.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }
    /** Writes one document to a file the user picks. Never overwrites silently elsewhere. */
    private fun exportDocument(doc: LocalDocument) {
        if (doc.id == activeId) {
            capture(failure = { message("无法读取当前内容，未导出") }) { text ->
                persist(text, doc.id, after = { launchExport(doc.id, doc.name) }, failed = { message("本机保存失败，未导出") })
            }
        } else launchExport(doc.id, doc.name)
    }
    private fun launchExport(id: String, name: String) {
        if (id !in documents) { message("文档已不存在"); return }
        prefs.edit().putString("pendingExportId", id).apply()
        createDocument.launch(if (name.endsWith(".md")) name else "$name.md")
    }
    private fun selectSidebarTab(showOutline: Boolean) {        outlineTab = showOutline
        prefs.edit().putBoolean("sidebarOutline", showOutline).apply()
        renderSidebar()
        if (showOutline) js("window.inkstone.refreshOutline()")
    }
    private fun foldedOutline(): MutableSet<String> = foldedByDocument.getOrPut(activeId) {
        prefs.getStringSet("outlineFold:$activeId", emptySet()).orEmpty().toMutableSet()
    }
    private fun saveFoldedOutline() {
        prefs.edit().putStringSet("outlineFold:$activeId", foldedOutline().toSet()).apply()
    }
    private fun renderOutline() {
        if (!::outlineTreeView.isInitialized) return
        val y = sideScroll.scrollY
        outlineTreeView.render(outlineNodes, foldedOutline(), activeHeading)
        sideScroll.post { if (!isDestroyed && outlineTab && sideScroll.scrollY != y) sideScroll.scrollTo(0, y) }
    }
    private fun navigateOutline(index: Int) {
        if (!ready || busy) return
        activeHeading = index
        outlineTreeView.setActive(index)
        hideKeyboard(); if (!wideScreen) hideSidebar()
        web.evaluateJavascript("window.inkstone.scrollToHeading($index)") { result ->
            if (result == "true") Log.i(TAG, "event=outline_navigated")
            else message("标题已变化，请重新打开大纲")
        }
    }
    private fun showDocumentMenu(doc: LocalDocument, anchor: View) {
        Dropdown.show(anchor, doc.name, listOf(
            Dropdown.Item("导出为文件") { exportDocument(doc) },
            Dropdown.Item("重命名") {
                if (doc.id == activeId) renameDocument() else message("先切换到该文档再重命名")
            },
            Dropdown.Item("删除", destructive = true) { confirmDelete(doc) }
        ), alignEnd = false)
    }
    private fun confirmDelete(doc: LocalDocument) {
        AppTheme.dialogBuilder(this).setTitle("删除《${doc.name}》？")
            .setMessage("只删除本机副本，已经导出的 .md 文件不受影响。此操作无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteDocument(doc) }
            .show()
    }
    private fun deleteDocument(doc: LocalDocument) {
        val id = doc.id
        io.execute {
            try {
                store.delete(id)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    documents.remove(id)
                    foldedByDocument.remove(id)
                    prefs.edit().remove("outlineFold:$id").apply()
                    Log.i(TAG, "event=document_deleted")
                    if (activeId == id) {
                        val next = documents.values.sortedByDescending { it.updatedAt }.firstOrNull()
                        if (next != null) switchDocument(next.id) else {
                            io.execute {
                                val created = store.create("")
                                runOnUiThread { if (!isDestroyed) activate(created, "") }
                            }
                        }
                    } else renderSidebar()
                    message("已删除《${doc.name}》")
                }
            } catch (_: Exception) { runOnUiThread { message("删除失败，文档仍保留") } }
        }
    }
    private fun updateTitle() {
        if (!::titleView.isInitialized || activeId !in documents) return
        titleView.text = active.name
    }
    private fun message(text: String) { runOnUiThread { if (!isDestroyed) Toast.makeText(this, text, Toast.LENGTH_LONG).show() } }
    private fun js(code: String) { if (ready && !isDestroyed) web.evaluateJavascript(code, null) }
    private fun setBusy(value: Boolean) {
        busy = value
        if (::web.isInitialized) {
            web.isEnabled = !value
            // Defer until the current save/switch callback has finished.
            if (!value) web.post { drainImports() }
        }
    }
    private fun persist(markdown: String, id: String = activeId, after: (() -> Unit)? = null, failed: (() -> Unit)? = null) {
        if (markdown.toByteArray().size > DocumentStore.MAX_BYTES) { message("文档超过 20 MiB，未保存；请减少图片"); failed?.invoke(); return }
        if (id !in documents) { failed?.invoke(); return }
        if (id == activeId) { currentMd = markdown; updateTitle() }
        val previous = documents[id]
        io.execute {
            try {
                val saved = store.saveDraft(id, markdown)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    documents[id] = saved
                    if (id == activeId && currentMd == markdown) updateTitle()
                    if (previous?.name != saved.name && !outlineTab && sidebar.visibility == View.VISIBLE) renderEntries()
                    after?.invoke()
                }
            } catch (_: Exception) { runOnUiThread { message("本机保存失败，未切换文档。请尝试保存到文件"); failed?.invoke() } }
        }
    }
    private fun capture(failure: () -> Unit = {}, callback: (String) -> Unit) {
        if (!ready || isDestroyed) { message("编辑器仍在加载"); failure(); return }
        val id = activeId
        if (id !in documents) { failure(); return }
        web.evaluateJavascript("window.inkstone.getMarkdown()") { value ->
            if (id != activeId || isDestroyed) { failure(); return@evaluateJavascript }
            try {
                val text = JSONTokener(value).nextValue() as? String ?: throw IllegalStateException()
                persist(text, id, after = { callback(text) }, failed = failure)
            } catch (_: Exception) { message("无法读取文档，未切换或退出"); failure() }
        }
    }
    private fun withSavedCurrent(action: () -> Unit) {
        if (busy) { message("正在保存或切换，请稍候"); return }
        setBusy(true)
        capture(failure = { setBusy(false) }) { setBusy(false); action() }
    }
    private fun activate(doc: LocalDocument, text: String) {
        documentEpoch++
        documents[doc.id] = doc
        activeId = doc.id; currentMd = text; outline = emptyList(); outlineNodes = emptyList(); activeHeading = -1
        prefs.edit().putString("activeDocumentId", doc.id).apply()
        js("window.inkstone.loadDocument(${JSONObject.quote(text)}, ${JSONObject.quote(doc.id)})")
        updateTitle(); renderSidebar(); setBusy(false)
        if (!wideScreen) hideSidebar()
    }
    private fun newDocument() {
        withSavedCurrent {
            setBusy(true)
            io.execute {
                try { val doc = store.create(""); runOnUiThread { if (!isDestroyed) { activate(doc, ""); Log.i(TAG, "event=document_created") } } }
                catch (_: Exception) { runOnUiThread { setBusy(false); message("新建失败，原文档仍保留") } }
            }
        }
    }
    private fun switchDocument(id: String) {
        setBusy(true)
        io.execute {
            try { val doc = store.get(id); val text = store.read(id); runOnUiThread { if (!isDestroyed) { activate(doc, text); Log.i(TAG, "event=document_switched") } } }
            catch (_: Exception) { runOnUiThread { setBusy(false); message("文档无法读取，未删除任何内容") } }
        }
    }
    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "文档.md"
    } catch (_: Exception) { "文档.md" }
    private fun queueImport(uri: Uri) {
        pendingImports.addLast(uri)
        drainImports()
    }
    private fun drainImports() {
        if (!ready || busy || isDestroyed || pendingImports.isEmpty()) return
        val uri = pendingImports.removeFirst()
        withSavedCurrent { importDocument(uri) }
    }
    private fun acceptDocumentIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme !in listOf("content", "file")) {
            message("仅支持打开本地或系统授权的文档")
            return
        }
        queueImport(uri)
    }
    private fun importDocument(uri: Uri) {
        val existing = documents.values.firstOrNull { it.uri == uri.toString() }
        if (existing != null) { switchDocument(existing.id); message("已打开本机副本，不覆盖本机修改"); return }
        setBusy(true)
        io.execute {
            try {
                val text = contentResolver.openInputStream(uri)?.use { readLimited(it, DocumentStore.MAX_BYTES).decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF") } ?: error("Unreadable")
                require('\u0000' !in text)
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
                val doc = store.create(text, displayName(uri), uri.toString(), DocumentStore.digest(text), false)
                runOnUiThread { if (!isDestroyed) { activate(doc, text); Log.i(TAG, "event=document_imported") } }
            } catch (_: Exception) { runOnUiThread { setBusy(false); message("导入失败：仅支持 UTF-8 文本，也可能文件过大或授权失效") } }
        }
    }
    private fun save(saveAs: Boolean = false) {
        if (busy) { message("正在保存或切换，请稍候"); return }
        capture { text ->
            val doc = active
            if (saveAs || doc.uri == null) {
                prefs.edit().putString("pendingExportId", doc.id).apply()
                createDocument.launch(if (doc.name.endsWith(".md")) doc.name else "${doc.name}.md")
            } else io.execute { writeExternal(doc.id, Uri.parse(doc.uri), text) }
        }
    }
    private fun writeExternal(id: String, uri: Uri, text: String) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Cannot write")
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
            val saved = store.markExported(id, uri.toString(), displayName(uri), text)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                documents[id] = saved; updateTitle(); if (!outlineTab) renderEntries()
                message("文件已保存"); Log.i(TAG, "event=document_saved")
            }
        } catch (_: Exception) { message("文件保存失败，本机副本仍保留。可尝试“另存为”") }
    }
    private fun renameDocument() {
        if (busy || !ready) return
        val input = EditText(AppTheme.dialogContext(this)).apply { setText(active.name); setSingleLine(true); selectAll() }
        AppTheme.dialogBuilder(this).setTitle("重命名本机文档").setMessage("只修改本机列表名称，不改动外部文件名。")
            .setView(input).setNegativeButton("取消", null).setPositiveButton("确定") { _, _ ->
                val id = activeId; val name = input.text.toString().trim()
                if (name.isBlank() || name.length > 120) { message("名称须为 1–120 个字符"); return@setPositiveButton }
                io.execute { try {
                    val doc = store.rename(id, if (name.endsWith(".md")) name else "$name.md")
                    runOnUiThread { documents[id] = doc; updateTitle(); renderSidebar() }
                } catch (_: Exception) { message("重命名失败") } }
            }.show()
    }
    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(16384)
        while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= maxBytes); output.write(buffer, 0, n) }
        return output.toByteArray()
    }
    private fun insertImage(uri: Uri) {
        if (!ready) { pendingImages.add(uri); return }
        if (readOnly) { message("请先关闭只读模式"); return }
        if (busy) { message("请在文档切换完成后插图"); return }
        if (uri.scheme != "content") { message("仅接受系统授权的图片 URI"); return }
        val epoch = documentEpoch
        message("正在导入图片…")
        io.execute {
            try {
                val data = contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_IMAGE_INPUT) } ?: error("Unreadable image")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Decode failed")
                val scaled = if (max(bitmap.width, bitmap.height) > 1600) {
                    val ratio = 1600f / max(bitmap.width, bitmap.height)
                    Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * ratio).roundToInt()), max(1, (bitmap.height * ratio).roundToInt()), true)
                } else bitmap
                val output = ByteArrayOutputStream(); val alpha = scaled.hasAlpha()
                val ok = scaled.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, output)
                if (scaled !== bitmap) scaled.recycle(); bitmap.recycle()
                require(ok && output.size() <= 6 * 1024 * 1024)
                val encoded = "data:image/${if (alpha) "png" else "jpeg"};base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                runOnUiThread {
                    if (epoch != documentEpoch || busy || readOnly || isDestroyed) { message("文档已切换或为只读，未插入先前图片"); return@runOnUiThread }
                    if (currentMd.toByteArray().size + encoded.length > DocumentStore.MAX_BYTES) { message("文档空间不足，未插图"); return@runOnUiThread }
                    js("window.inkstone.insertImage(${JSONObject.quote(encoded)})")
                }
            } catch (_: Exception) { message("图片导入失败：格式不支持、图片过大或没有权限") }
        }
    }
    @Suppress("DEPRECATION") private fun acceptSharedImage(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::insertImage)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedImage(intent)
        acceptDocumentIntent(intent)
    }
    private fun handleMenuKeyEvent(event: KeyEvent): Boolean {
        val accessCategory = MenuAccessKeys.resolve(event.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed)
        if (accessCategory != null) {
            altTapCandidate = false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openMenuFromKeyboard(accessCategory)
            return true
        }
        // Left Alt enters the menu bar. Right Alt remains available for AltGr input.
        if (event.keyCode == KeyEvent.KEYCODE_ALT_LEFT && !event.isCtrlPressed && !event.isShiftPressed && !event.isMetaPressed) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                altWasVisible = accessCuesVisible
                altTapCandidate = true
                setAccessCues(true)
            } else if (event.action == KeyEvent.ACTION_UP && altTapCandidate) {
                altTapCandidate = false
                if (altWasVisible) leaveMenuBar()
                else if (expandedMenus) categoryAnchors[MenuAccessKeys.categories[accessCueIndex]]?.requestFocus()
                else showMenu(menuAnchor, keyboard = true)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            altTapCandidate = false
            if (accessCuesVisible && !event.isCtrlPressed && !event.isMetaPressed && (!event.isShiftPressed || event.keyCode == KeyEvent.KEYCODE_TAB)) {
                val category = MenuAccessKeys.resolve(event.keyCode, false, false, true, false)
                if (category != null) {
                    if (event.repeatCount == 0) openMenuFromKeyboard(category)
                    return true
                }
                when (event.keyCode) {
                    KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> { leaveMenuBar(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TAB -> {
                        val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.isShiftPressed) -1 else 1
                        accessCueIndex = Math.floorMod(accessCueIndex + direction, MenuAccessKeys.categories.size)
                        categoryAnchors[MenuAccessKeys.categories[accessCueIndex]]?.requestFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE -> {
                        if (event.repeatCount == 0) openMenuFromKeyboard(MenuAccessKeys.categories[accessCueIndex])
                        return true
                    }
                    else -> leaveMenuBar()
                }
            } else if (accessCuesVisible) setAccessCues(false)
        }
        return false
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleMenuKeyEvent(event)) return true
        if (ready && event.action == KeyEvent.ACTION_DOWN) {
            val shortcut = EditorShortcuts.resolve(
                event.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed)
            if (shortcut != null && (!shortcut.requiresEditor || web.hasFocus())) {
                // Consume repeats instead of forwarding them into a second WebView keymap.
                if (event.repeatCount > 0 || busy) return true
                if (runShortcut(shortcut)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && accessCuesVisible && !Dropdown.isShowing) setAccessCues(false)
        return super.dispatchTouchEvent(event)
    }
    private fun setAccessCues(visible: Boolean) {
        accessCuesVisible = visible
        categoryAnchors.forEach { (category, view) ->
            val letter = MenuAccessKeys.letter(category)
            (view as TextView).text = if (visible) SpannableString("$category($letter)").apply {
                val index = length - 2
                setSpan(UnderlineSpan(), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else category
        }
    }
    private fun leaveMenuBar() {
        altTapCandidate = false
        setAccessCues(false)
        if (::web.isInitialized) web.requestFocus()
    }
    private fun menuDismissed(anchor: View) {
        // A sibling menu may open synchronously after dismissal; retain its cues.
        anchor.post { if (!Dropdown.isShowing && accessCuesVisible && !isDestroyed) leaveMenuBar() }
    }
    /** Returns true when the shortcut was consumed by the app. */
    private fun runShortcut(shortcut: EditorShortcut): Boolean = when (shortcut.action) {
        "save" -> { save(); true }
        "saveAs" -> { save(true); true }
        "open" -> { withSavedCurrent { openDocument.launch(arrayOf("text/*", "application/octet-stream")) }; true }
        "new" -> { newDocument(); true }
        "image" -> { if (!busy && !readOnly) selectImage.launch(arrayOf("image/*")) else if (readOnly) message("请先关闭只读模式"); true }
        "focusMode" -> { setViewOption("focus", !focusMode); true }
        "typewriterMode" -> { if (!readOnly) setViewOption("typewriter", !typewriterMode); true }
        "find" -> { showFind(false); true }
        "replace" -> { if (!readOnly) showFind(true); true }
        "link" -> { if (!readOnly) showLink(); true }
        "menu" -> { shortcut.menu?.let(::openMenuFromKeyboard); true }
        "cycleMode" -> { js("window.inkstone.toggleMode()"); true }
        "toggleSource" -> { js("window.inkstone.setMode(window.inkstone.getMode() === 'wysiwyg' ? 'source' : 'wysiwyg')"); true }
        else -> {
            if (!shortcut.requiresEditor) false
            else {
                val command = when (shortcut.action) {
                    "heading" -> "heading"; "paragraph" -> "paragraph"; "bold" -> "bold"; "italic" -> "italic"
                    "strike" -> "strike"; "code" -> "code"; "codeblock" -> "codeblock"; "quote" -> "quote"
                    "undo" -> "undo"; "redo" -> "redo"; else -> null
                } ?: return false
                runFormat(command, if (shortcut.action == "heading") shortcut.level else null)
                true
            }
        }
    }
    private fun runFormat(command: String, value: Any? = null) {
        if (!ready || busy) { message("编辑器正在加载或切换"); return }
        if (readOnly) { message("请先关闭只读模式"); return }
        val argument = when (value) {
            is Int -> value.toString()
            is String -> JSONObject.quote(value)
            else -> "null"
        }
        web.evaluateJavascript("window.inkstone.format(${JSONObject.quote(command)}, $argument)") { result ->
            if (result == "false") message("当前位置无法执行该操作")
        }
    }
    private fun showLink() {
        if (!ready || busy || readOnly) return
        val input = EditText(AppTheme.dialogContext(this)).apply {
            hint = "https://example.com"; setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val dialog = AppTheme.dialogBuilder(this).setTitle("插入链接").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("插入", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text.toString().trim()
                if (!Regex("^(https?://|mailto:|#)[^\\s<>]+$", RegexOption.IGNORE_CASE).matches(url)) {
                    input.error = "请输入网页、邮箱或章节链接"
                } else { runFormat("link", url); dialog.dismiss() }
            }
        }
        dialog.show()
    }
    private fun showFind(replaceMode: Boolean) {
        if (!ready || busy || (replaceMode && readOnly)) return
        val documentId = activeId
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val query = EditText(AppTheme.dialogContext(this)).apply { hint = "查找文字（区分大小写）"; setSingleLine(true) }
        val replacement = EditText(AppTheme.dialogContext(this)).apply { hint = "替换为（可留空）"; setSingleLine(true) }
        val status = TextView(this).apply { textSize = 12f; themeText(ThemeColor.MUTED); setPadding(0, dp(8), 0, dp(8)) }
        content.addView(query)
        if (replaceMode) content.addView(replacement)
        content.addView(status)
        val dialog = AppTheme.dialogBuilder(this).setTitle(if (replaceMode) "查找与替换" else "查找")
            .setView(content).setNegativeButton("关闭", null).create()
        fun search(backwards: Boolean = false, replace: Boolean = false, all: Boolean = false) {
            if (documentId != activeId || busy || !ready) { dialog.dismiss(); return }
            val text = query.text.toString()
            if (text.isEmpty()) { query.error = "请输入查找文字"; return }
            if (replace && readOnly) { status.text = "请先关闭只读模式"; return }
            val script = if (replace) "window.inkstone.replace(${JSONObject.quote(text)}, ${JSONObject.quote(replacement.text.toString())}, $all)"
                else "window.inkstone.find(${JSONObject.quote(text)}, $backwards)"
            web.evaluateJavascript(script) { result ->
                if (!dialog.isShowing || documentId != activeId) return@evaluateJavascript
                try {
                    val data = JSONObject(result)
                    status.text = when {
                        data.has("error") -> data.getString("error")
                        replace -> "已替换 ${data.optInt("replaced")} 处"
                        data.optInt("count") == 0 -> "没有找到匹配文字"
                        else -> "${data.optInt("index")} / ${data.optInt("count")}"
                    }
                } catch (_: Exception) { status.text = "暂时无法查找，请重试" }
            }
        }
        fun actions(labels: List<String>, callbacks: List<() -> Unit>) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            labels.forEachIndexed { index, label ->
                row.addView(smallButton(label, label) { callbacks[index]() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            content.addView(row)
        }
        actions(listOf("上一处", "下一处"), listOf({ search(true) }, { search() }))
        if (replaceMode) actions(listOf("替换当前", "全部替换"), listOf({ search(replace = true) }, { search(replace = true, all = true) }))
        query.setOnEditorActionListener { _, _, _ -> search(); true }
        dialog.show()
        query.requestFocus()
    }
    private fun openMenuFromKeyboard(category: String) {
        accessCueIndex = MenuAccessKeys.categories.indexOf(category).coerceAtLeast(0)
        setAccessCues(true)
        showCategory(categoryAnchors[category] ?: menuAnchor, category, keyboard = true)
    }
    private fun showCategory(anchor: View, category: String, keyboard: Boolean = false) {
        val available = ready && !busy
        val editable = available && !readOnly && !(editorMode == "source" && sourceLayout == "preview")
        fun action(label: String, command: String, shortcut: String? = null) =
            Dropdown.Item(label, enabled = editable, shortcut = shortcut) { runFormat(command) }
        val items = when (category) {
            "文件" -> listOf(
                Dropdown.Item("新建文档", enabled = available, shortcut = "Ctrl+N") { newDocument() },
                Dropdown.Item("打开 / 导入", enabled = available, shortcut = "Ctrl+O") { withSavedCurrent { openDocument.launch(arrayOf("text/*", "application/octet-stream")) } },
                Dropdown.Item("保存到文件", enabled = available, shortcut = "Ctrl+S") { save() },
                Dropdown.Item("另存为", enabled = available, shortcut = "Ctrl+Shift+S") { save(true) },
                Dropdown.Item("重命名", enabled = available) { renameDocument() }
            )
            "编辑" -> listOf(
                action("撤销", "undo", "Ctrl+Z"), action("重做", "redo", "Ctrl+Y"),
                Dropdown.Item("查找", enabled = available, shortcut = "Ctrl+F") { showFind(false) },
                Dropdown.Item("替换", enabled = editable, shortcut = "Ctrl+H") { showFind(true) }
            )
            "格式" -> listOf(
                Dropdown.Item("标题  ›", enabled = editable) {
                    Dropdown.show(anchor, "标题", (1..6).map { level ->
                        Dropdown.Item("${level} 级标题", shortcut = "Ctrl+$level") { runFormat("heading", level) }
                    }, alignEnd = !expandedMenus, keyboard = accessCuesVisible, onDismiss = { menuDismissed(anchor) })
                },
                action("正文", "paragraph", "Ctrl+0"), action("加粗", "bold", "Ctrl+B"),
                action("斜体", "italic", "Ctrl+I"), action("删除线", "strike", "Ctrl+Shift+X"),
                action("无序列表", "ul"), action("有序列表", "ol"), action("任务列表", "task"),
                action("引用", "quote", "Ctrl+Q"), action("行内代码", "code", "Ctrl+`"),
                action("代码块", "codeblock", "Ctrl+Shift+C"),
                Dropdown.Item("插入链接", enabled = editable, shortcut = "Ctrl+K") { showLink() },
                Dropdown.Item("插入图片", enabled = editable, shortcut = "Ctrl+Shift+I") { selectImage.launch(arrayOf("image/*")) },
                action("插入表格", "table"), action("分隔线", "hr")
            )
            "视图" -> listOf(
                Dropdown.Item("书写", enabled = available, checked = editorMode == "wysiwyg") { js("window.inkstone.setMode('wysiwyg')") },
                Dropdown.Item("源码", enabled = available, checked = editorMode != "wysiwyg", shortcut = "Ctrl+/") { js("window.inkstone.setMode('source')") },
                Dropdown.Item("仅源码", enabled = available && editorMode == "source", checked = editorMode == "source" && sourceLayout == "source") { js("window.inkstone.setSourceLayout('source')") },
                Dropdown.Item("分栏预览", enabled = available && editorMode == "source", checked = editorMode == "source" && sourceLayout == "split") { js("window.inkstone.setSourceLayout('split')") },
                Dropdown.Item("仅预览", enabled = available && editorMode == "source", checked = editorMode == "source" && sourceLayout == "preview") { js("window.inkstone.setSourceLayout('preview')") },
                Dropdown.Item("只读", enabled = available, checked = readOnly) { setViewOption("readOnly", !readOnly) },
                Dropdown.Item("专注", enabled = available, checked = focusMode, shortcut = "F8") { setViewOption("focus", !focusMode) },
                Dropdown.Item("打字机", enabled = editable, checked = typewriterMode, shortcut = "F9") { setViewOption("typewriter", !typewriterMode) },
                Dropdown.Item("格式工具栏", enabled = editable, checked = formattingVisible) { js("window.inkstone.toggleFormatting()") },
                Dropdown.Item("侧栏") { toggleSidebar() },
                Dropdown.Item("主题  ›", shortcut = AppTheme.preference(this).label) { showThemeMenu(anchor) }
            )
            else -> emptyList()
        }
        Dropdown.show(anchor, category, items, alignEnd = !expandedMenus, keyboard = keyboard,
            onCategory = ::openMenuFromKeyboard, onDismiss = { menuDismissed(anchor) }, onKeyboardMode = { setAccessCues(true) })
    }
    private fun setViewOption(name: String, enabled: Boolean) {
        if (!ready || busy) return
        if (name == "readOnly" && enabled) hideKeyboard()
        js("window.inkstone.setViewOption(${JSONObject.quote(name)}, $enabled)")
    }
    private fun showMenu(anchor: View, keyboard: Boolean = false) {
        val categories = if (expandedMenus) emptyList() else MenuAccessKeys.categories.map { category ->
            Dropdown.Item("$category  ›", shortcut = "Alt+${MenuAccessKeys.letter(category)}") { showCategory(anchor, category) }
        }
        Dropdown.show(anchor, null, categories + listOf(
            Dropdown.Item("帮助") { help() },
            Dropdown.Item("开源许可") {
                AppTheme.dialogBuilder(this@MainActivity).setTitle("开源许可")
                    .setMessage(assets.open("editor/vendor/LICENSE-toast-ui-editor").bufferedReader().use { it.readText() })
                    .setPositiveButton("知道了", null).show()
            }
        ), alignEnd = true, keyboard = keyboard, onDismiss = { menuDismissed(anchor) }, onKeyboardMode = { setAccessCues(true) })
    }
    /** Flat, scannable help: short groups instead of a long paragraph. */
    private fun help() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun group(title: String, lines: List<String>) {
            content.addView(TextView(this).apply {
                text = title; textSize = 12f; themeText(ThemeColor.MUTED)
                setPadding(0, dp(14), 0, dp(4)); includeFontPadding = false
            })
            lines.forEach { line ->
                content.addView(TextView(this).apply {
                    text = line; textSize = 14f; themeText(ThemeColor.INK); setLineSpacing(dp(5).toFloat(), 1f)
                })
            }
        }
        group("文档", listOf(
            "侧栏“＋”新建；右上菜单也可新建、打开、导出",
            "文件管理器中打开 Markdown，选择墨页并设为始终",
            "列表里长按文档：导出、重命名、删除",
            "文档自动存在本机，卸载会删除"))
        group("大纲与模式", listOf(
            "左侧大纲点击标题跳转，可折叠",
            "底部切换书写 / 源码；源码内选择仅源码、分栏或仅预览",
            "书写时当前格式显示标记，移开光标后显示排版",
            "只读防误改；专注淡化其他段落；打字机跟随输入行",
            "视图 → 主题：跟随系统、浅色或深色"))
        group("快捷键", listOf(
            "Ctrl+1~6 标题　Ctrl+0 正文",
            "Ctrl+B 加粗　Ctrl+I 斜体　Ctrl+Shift+X 删除线",
            "Ctrl+Shift+C 代码块　Ctrl+Q 引用",
            "Ctrl+Z 撤销　Ctrl+Y 重做",
            "Ctrl+Shift+I 插图　Ctrl+Shift+M 切模式",
            "Ctrl+F 查找　Ctrl+H 替换　Ctrl+K 链接",
            "F8 专注　F9 打字机",
            "Alt+F 文件　Alt+E 编辑　Alt+O 格式　Alt+V 视图",
            "菜单内方向键选择，Enter 执行，Esc 关闭",
            "Ctrl+N 新建　Ctrl+O 打开　Ctrl+S 保存"))
        AppTheme.dialogBuilder(this).setTitle("墨页 · 帮助").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("知道了", null).show()
    }
    private fun applyWindowTheme() {
        val paper = AppTheme.color(this, ThemeColor.PAPER)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = if (AppTheme.isDark(this)) window.decorView.systemUiVisibility and lightFlags.inv()
            else window.decorView.systemUiVisibility or lightFlags
    }
    private fun applyTheme() {
        Dropdown.dismiss()
        setTheme(if (AppTheme.isDark(this)) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        applyWindowTheme()
        AppTheme.refresh(window.decorView)
        AppTheme.refreshDialogs(this)
        if (::outlineTreeView.isInitialized) outlineTreeView.refreshTheme()
        if (::sidebarList.isInitialized) renderSidebar()
        val preference = AppTheme.preference(this).value
        val resolved = if (AppTheme.isDark(this)) "dark" else "light"
        js("window.inkstone.setTheme(${JSONObject.quote(preference)}, ${JSONObject.quote(resolved)})")
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }
    private fun showThemeMenu(anchor: View) {
        val selected = AppTheme.preference(this)
        Dropdown.show(anchor, "主题", ThemePreference.entries.map { preference ->
            Dropdown.Item(preference.label, checked = selected == preference) {
                prefs.edit().putString("theme", preference.value).apply()
                applyTheme()
            }
        }, alignEnd = !expandedMenus, keyboard = accessCuesVisible, onDismiss = { menuDismissed(anchor) })
    }
    inner class Bridge {
        @JavascriptInterface fun themeState(): String = JSONObject().put("preference", AppTheme.preference(this@MainActivity).value)
            .put("resolved", if (AppTheme.isDark(this@MainActivity)) "dark" else "light").toString()
        @JavascriptInterface fun viewStateChanged(json: String) {
            if (json.length > 1000) return
            try {
                val state = JSONObject(json)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    editorMode = state.optString("mode", "wysiwyg")
                    sourceLayout = state.optString("sourceLayout", "source")
                    readOnly = state.optBoolean("readOnly")
                    focusMode = state.optBoolean("focus")
                    typewriterMode = state.optBoolean("typewriter")
                    formattingVisible = state.optBoolean("formattingVisible")
                }
            } catch (_: Exception) { }
        }
        @JavascriptInterface fun ready() { runOnUiThread {
            ready = true
            applyTheme()
            js("window.inkstone.loadDocument(${JSONObject.quote(currentMd)}, ${JSONObject.quote(activeId)})")
            drainImports()
            pendingImages.toList().also { pendingImages.clear() }.forEach(::insertImage)
            Log.i(TAG, "event=editor_ready")
        } }
        @JavascriptInterface fun changed(markdown: String, docId: String) { runOnUiThread {
            if (!isDestroyed && ready && !busy && docId == activeId) persist(markdown, docId)
        } }
        @JavascriptInterface fun outlineChanged(json: String, docId: String) {
            if (json.length > 200000) return
            try {
                val array = JSONArray(json)
                val entries = (0 until min(array.length(), 500)).map { i ->
                    val item = array.getJSONObject(i)
                    OutlineHeading(item.optInt("index", i).coerceIn(0, 100000), item.optInt("level", 1).coerceIn(1, 6), item.optString("text", "").take(160))
                }
                runOnUiThread {
                    if (!isDestroyed && docId == activeId && entries != outline) {
                        outline = entries; outlineNodes = OutlineTree.build(entries)
                        foldedOutline().retainAll(outlineNodes.map { it.key }.toSet())
                        if (outlineTab && sidebar.visibility == View.VISIBLE) renderOutline()
                    }
                }
            } catch (_: Exception) { }
        }
        @JavascriptInterface fun activeHeadingChanged(index: Int, docId: String) {
            if (index < -1 || index > 100000) return
            runOnUiThread {
                if (!isDestroyed && docId == activeId && activeHeading != index) {
                    activeHeading = index
                    if (outlineTab && sidebar.visibility == View.VISIBLE) outlineTreeView.setActive(index)
                }
            }
        }
        @JavascriptInterface fun saveDocument() { runOnUiThread { save() } }
        @JavascriptInterface fun insertImage() { runOnUiThread { if (!busy && !readOnly) selectImage.launch(arrayOf("image/*")) } }
        @JavascriptInterface fun notice(text: String) { message(text.take(160)) }
    }
    override fun onPause() { if (ready && !busy) capture { }; super.onPause() }
    override fun onDestroy() {
        ready = false
        if (::web.isInitialized) { web.removeJavascriptInterface("InkstoneNative"); web.destroy() }
        io.shutdown()
        super.onDestroy()
    }
}
