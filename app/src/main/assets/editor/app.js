(function () {
  'use strict';
  const native = window.InkstoneNative;
  const utils = window.InkstoneUtils;
  function notice(text) { if (native) native.notice(text); }
  window.addEventListener('error', function () { notice('编辑器遇到错误，请先保存重要内容。'); });
  let timer;
  let suppress = false;
  let loaded = false;
  let editor;
  let writingTools;
  let livePreview;
  let currentToken = null;
  let formattingVisible = false;
  let currentMode = 'wysiwyg';
  const modes = ['wysiwyg', 'source'];
  let sourceLayout = 'source';
  function viewState() {
    return Object.assign({ mode: currentMode, sourceLayout, formattingVisible }, writingTools ? writingTools.state() : {});
  }
  function publishViewState() {
    const state = viewState();
    const buttons = { wysiwyg: 'rich-mode', source: 'source-mode' };
    modes.forEach(function (mode) {
      const button = document.getElementById(buttons[mode]);
      button.classList.toggle('selected', currentMode === mode);
      button.setAttribute('aria-pressed', String(currentMode === mode));
    });
    document.getElementById('view-lock').hidden = !state.readOnly;
    const layout = document.getElementById('source-layout');
    layout.hidden = currentMode !== 'source';
    layout.value = sourceLayout;
    if (livePreview) livePreview.refresh();
    if (native && typeof native.viewStateChanged === 'function') native.viewStateChanged(JSON.stringify(state));
  }
  let cachedHeadings = [];
  let activeTimer;
  let lastActiveToken;
  let lastActiveIndex = -2;
  function publishActive(index, token = currentToken) {
    if (!loaded || token !== currentToken || !native || typeof native.activeHeadingChanged !== 'function') return;
    if (lastActiveToken === token && lastActiveIndex === index) return;
    lastActiveToken = token; lastActiveIndex = index;
    native.activeHeadingChanged(index, token.docId);
  }
  function richHeadings() {
    return Array.from(document.querySelectorAll('#editor .toastui-editor-ww-container .toastui-editor-contents h1, #editor .toastui-editor-ww-container .toastui-editor-contents h2, #editor .toastui-editor-ww-container .toastui-editor-contents h3, #editor .toastui-editor-ww-container .toastui-editor-contents h4, #editor .toastui-editor-ww-container .toastui-editor-contents h5, #editor .toastui-editor-ww-container .toastui-editor-contents h6'));
  }
  function activeFromViewport(kind) {
    const isSource = editor.isMarkdownMode() && kind !== 'preview';
    const container = document.querySelector(editor.isMarkdownMode()
      ? (isSource ? '#editor .toastui-editor-md-container .toastui-editor' : '#editor .toastui-editor-md-preview')
      : '#editor .toastui-editor-ww-container');
    if (!container || typeof container.getBoundingClientRect !== 'function') return null;
    const rect = container.getBoundingClientRect();
    if (!(rect.height > 0)) return null;
    let elements;
    if (isSource) {
      const body = container.querySelector('.ProseMirror');
      if (!body) return null;
      elements = cachedHeadings.map(h => body.children[h.line - 1]);
    } else elements = editor.isMarkdownMode()
      ? Array.from(container.querySelectorAll('h1,h2,h3,h4,h5,h6')) : richHeadings();
    let active = -1;
    for (let i = 0; i < cachedHeadings.length; i++) {
      const element = elements[i];
      if (!element || typeof element.getBoundingClientRect !== 'function') continue;
      const headingRect = element.getBoundingClientRect();
      if (headingRect.top <= rect.top + Math.min(44, rect.height / 3)) active = i;
      else break;
    }
    return active;
  }
  function updateActive(kind = 'caret', token = currentToken) {
    if (!loaded || suppress || token !== currentToken || !native || typeof native.activeHeadingChanged !== 'function') return;
    if (!cachedHeadings.length) { publishActive(-1, token); return; }
    try {
      if (kind !== 'caret') {
        const index = activeFromViewport(kind);
        if (index !== null) { publishActive(index, token); return; }
      }
      if (editor.isMarkdownMode() && typeof editor.getSelection === 'function') {
        const selection = editor.getSelection();
        const line = selection && Array.isArray(selection[0]) ? selection[0][0] : 1;
        let index = -1;
        cachedHeadings.forEach((heading, i) => { if (heading.line <= line) index = i; });
        publishActive(index, token);
      } else {
        const selection = typeof window.getSelection === 'function' ? window.getSelection() : null;
        const selected = selection ? selection.anchorNode : null;
        const headings = richHeadings();
        let index = -1;
        if (selected && selected.isConnected) {
          for (let i = 0; i < headings.length; i++) {
            if (headings[i].contains(selected) || (headings[i].compareDocumentPosition(selected) & 4)) index = i;
          }
        }
        if (index < 0) index = activeFromViewport('scroll');
        publishActive(index === null ? 0 : index, token);
      }
    } catch (_) { /* Do not let a transient selection interrupt editing. */ }
  }
  function scheduleActive(kind = 'caret') {
    if (!loaded || suppress) return;
    clearTimeout(activeTimer);
    const token = currentToken;
    activeTimer = setTimeout(function () { updateActive(kind, token); }, 80);
  }
  function changed() {
    if (!loaded || suppress) return;
    if (writingTools) writingTools.changed();
    clearTimeout(timer);
    const token = currentToken;
    timer = setTimeout(function () { flush(token); }, 700);
  }
  function flush(token = currentToken) {
    if (token !== currentToken) return;
    clearTimeout(timer);
    timer = undefined;
    if (!editor || !loaded || suppress) return;
    const markdown = editor.getMarkdown();
    const headings = utils.extractOutline(markdown);
    cachedHeadings = headings;
    document.getElementById('count').textContent = utils.visibleLength(markdown).toLocaleString() + ' 字符';
    if (utils.documentFits(markdown)) {
      if (native) native.changed(markdown, token.docId);
    } else notice('文档超过 20 MiB，暂未自动保存；请减少图片。');
    if (token === currentToken && native && typeof native.outlineChanged === 'function') {
      native.outlineChanged(JSON.stringify(headings), token.docId);
    }
    updateActive('caret', token);
  }
  function scrollToHeading(index) {
    if (!loaded || !Number.isInteger(index) || index < 0) return false;
    const heading = utils.extractOutline(editor.getMarkdown())[index];
    if (!heading) return false;
    if (editor.isMarkdownMode()) {
      if (typeof editor.setSelection !== 'function') return false;
      editor.setSelection([heading.line, 1], [heading.line, 1]);
      editor.focus();
    } else {
      const headings = document.querySelectorAll('#editor .toastui-editor-ww-container .toastui-editor-contents h1, #editor .toastui-editor-ww-container .toastui-editor-contents h2, #editor .toastui-editor-ww-container .toastui-editor-contents h3, #editor .toastui-editor-ww-container .toastui-editor-contents h4, #editor .toastui-editor-ww-container .toastui-editor-contents h5, #editor .toastui-editor-ww-container .toastui-editor-contents h6');
      const target = headings[index];
      if (!target || typeof target.scrollIntoView !== 'function') return false;
      target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
    publishActive(index);
    return true;
  }
  function setMode(mode) {
    if (mode === 'split' || mode === 'preview') {
      sourceLayout = mode;
      mode = 'source';
    }
    if (!modes.includes(mode)) return false;
    // Writing is a decorated Markdown view. All layouts share the same document,
    // selection and undo history; no Markdown/rich-text conversion occurs.
    if (!editor.isMarkdownMode()) editor.changeMode('markdown', true);
    editor.changePreviewStyle('vertical');
    currentMode = mode;
    modes.forEach(function (item) {
      document.getElementById('editor').classList.toggle('mode-' + item, mode === item);
    });
    ['source', 'split', 'preview'].forEach(layout => document.getElementById('editor').classList.toggle('layout-' + layout, currentMode === 'source' && sourceLayout === layout));
    if (writingTools) writingTools.apply();
    publishViewState();
    try { localStorage.setItem('inkstone-mode', mode); } catch (_) { /* storage may be unavailable */ }
    flush();
    return true;
  }
  function setSourceLayout(layout) {
    if (!['source', 'split', 'preview'].includes(layout)) return false;
    sourceLayout = layout;
    try { localStorage.setItem('inkstone-source-layout', layout); } catch (_) { }
    return setMode('source');
  }
  function toggleMode() {
    setMode(modes[(modes.indexOf(currentMode) + 1) % modes.length]);
    return currentMode;
  }
  function format(command, value) {
    const commands = { heading: 'heading', paragraph: 'heading', bold: 'bold', italic: 'italic', strike: 'strike', code: 'code', codeblock: 'codeBlock', quote: 'blockQuote', undo: 'undo', redo: 'redo', link: 'addLink', ul: 'bulletList', ol: 'orderedList', task: 'taskList', table: 'addTable', hr: 'hr' };
    if (!loaded || (writingTools && !writingTools.editable()) || !Object.prototype.hasOwnProperty.call(commands, command)) return false;
    if (currentMode === 'source' && sourceLayout === 'preview') return false;
    if (['bold', 'italic', 'strike', 'code'].includes(command) && !editor.getSelectedText()) {
      if (livePreview && livePreview.exitInline(command)) { editor.focus(); flush(); return true; }
      const marker = { bold: '**', italic: '*', strike: '~~', code: '`' }[command];
      const start = editor.getSelection()[0];
      editor.replaceSelection(marker + marker);
      const caret = [start[0], start[1] + marker.length];
      editor.setSelection(caret, caret);
      editor.focus(); flush();
      return true;
    }
    if (command === 'paragraph' && editor.isMarkdownMode()) {
      try {
        const edit = utils.paragraphReset(editor.getMarkdown(), editor.getSelection());
        if (edit) {
          editor.replaceSelection(edit.text, edit.from, edit.to);
          editor.setSelection(edit.selection[0], edit.selection[1]);
        }
        editor.focus(); flush();
        return true;
      } catch (_) { return false; }
    }
    // Toast UI 3.x uses ProseMirror history for both editors, not CodeMirror.
    let options;
    if (command === 'heading') {
      if (!Number.isInteger(value) || value < 1 || value > 6) return false;
      options = { level: value };
    } else if (command === 'paragraph') {
      // There is no separate paragraph command: level 0 turns the block into a paragraph.
      options = { level: 0 };
    } else if (command === 'link') {
      const url = typeof value === 'string' ? value : value && value.url;
      if (typeof url !== 'string' || !/^(?:https?:\/\/|mailto:|#)/i.test(url) || /[\s<>]/.test(url)) return false;
      options = { linkUrl: url, linkText: (value && value.text) || editor.getSelectedText() || url };
    } else if (command === 'table') {
      options = { rowCount: 3, columnCount: 3 };
    }
    try {
      editor.exec(commands[command], options);
      editor.focus();
      flush();
      return true;
    } catch (_) { return false; }
  }
  function insertImage(url) {
    if (writingTools && !writingTools.editable()) { notice('请先关闭只读模式'); return; }
    if (currentMode === 'source' && sourceLayout === 'preview') { notice('请先切换到源码编辑布局'); return; }
    if (!utils.allowedImageDataUrl(url)) { notice('仅支持 PNG、JPEG、GIF、WebP 图片数据。'); return; }
    if (!utils.documentFits(editor.getMarkdown() + url)) { notice('文档空间不足，未插入图片。'); return; }
    editor.focus();
    editor.exec('addImage', { imageUrl: url, altText: '图片' });
    flush();
  }
  function readBlob(blob, callback) {
    if (blob.size > utils.MAX_IMAGE_BYTES || !/^image\/(png|jpeg|gif|webp)$/.test(blob.type)) {
      notice('粘贴图片格式不支持或超过 6 MiB；请使用菜单里的“插入图片”导入。');
      return;
    }
    const token = currentToken;
    const reader = new FileReader();
    reader.onload = function () {
      if (token !== currentToken || !loaded || (writingTools && !writingTools.editable())) return;
      if (utils.allowedImageDataUrl(reader.result) && utils.documentFits(editor.getMarkdown() + reader.result)) callback(reader.result);
      else notice('图片不支持或文档空间不足。');
    };
    reader.onerror = function () { notice('无法读取剪贴板图片，请用“图片”选择器。'); };
    reader.readAsDataURL(blob);
  }
  try {
    editor = new toastui.Editor({
      el: document.getElementById('editor'),
      height: '100%',
      initialEditType: 'markdown',
      initialValue: '',
      previewStyle: 'vertical',
      hideModeSwitch: true,
      autofocus: false,
      language: 'zh-CN',
      usageStatistics: false,
      customHTMLSanitizer: function (html) {
        return window.DOMPurify.sanitize(html, {
          USE_PROFILES: { html: true },
          SANITIZE_NAMED_PROPS: true,
          FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form', 'svg', 'math', 'link', 'meta', 'base'],
          FORBID_ATTR: ['style', 'srcset', 'autofocus']
        });
      },
      placeholder: '在这里写下你的想法…',
      toolbarItems: [
        ['heading', 'bold', 'italic', 'strike'],
        ['hr', 'quote'],
        ['ul', 'ol', 'task'],
        ['table', 'link'],
        ['code', 'codeblock']
      ],
      hooks: {
        addImageBlobHook: function (blob, callback) {
          if (writingTools && !writingTools.editable()) return false;
          readBlob(blob, function (url) { callback(url, '图片'); changed(); });
          return false;
        }
      },
      events: {
        change: changed,
        caretChange: function () { scheduleActive('caret'); if (writingTools) writingTools.caretChanged(); },
        scroll: function (source) { scheduleActive(source === 'preview' ? 'preview' : 'scroll'); }
      }
    });
    livePreview = window.InkstoneLivePreview.create(editor, {
      enabled: () => currentMode === 'wysiwyg',
      readOnly: () => writingTools && !writingTools.editable()
    });
    writingTools = window.InkstoneWritingTools.create(editor, { changed: flush, stateChanged: publishViewState });
    window.inkstone = {
      getMarkdown: function () { return editor.getMarkdown(); },
      loadDocument: function (text, docId = '') {
        suppress = true;
        loaded = false;
        clearTimeout(timer);
        clearTimeout(activeTimer);
        timer = undefined;
        activeTimer = undefined;
        cachedHeadings = [];
        lastActiveToken = undefined; lastActiveIndex = -2;
        currentToken = { docId: String(docId) };
        try { editor.setMarkdown(text, false); livePreview.resetHistory(); }
        finally { suppress = false; }
        loaded = true;
        writingTools.resetDocument();
        let mode = 'wysiwyg';
        try { mode = localStorage.getItem('inkstone-mode') || mode; } catch (_) { }
        if (mode === 'markdown') mode = 'source';
        if (mode === 'split') { mode = 'source'; sourceLayout = 'split'; }
        try { sourceLayout = localStorage.getItem('inkstone-source-layout') || sourceLayout; } catch (_) { }
        setMode(modes.includes(mode) ? mode : 'wysiwyg');
      },
      refreshOutline: function () { flush(); },
      scrollToHeading: scrollToHeading,
      toggleFormatting: function () {
        formattingVisible = !formattingVisible;
        document.getElementById('editor').classList.toggle('formatting-visible', formattingVisible);
        publishViewState();
        return formattingVisible;
      },
      insertImage: insertImage,
      insertText: function (text) { if (!writingTools.editable() || (currentMode === 'source' && sourceLayout === 'preview')) return false; editor.focus(); editor.insertText(text); flush(); return true; },
      setSelection: function (start, end) {
        if (typeof editor.setSelection !== 'function') return false;
        if (typeof start === 'number' && typeof editor.convertPosToMatchEditorMode === 'function') {
          const positions = editor.convertPosToMatchEditorMode(start, end);
          start = positions[0]; end = positions[1];
        }
        editor.setSelection(start, end);
        editor.focus();
        return true;
      },
      format: format,
      getViewState: viewState,
      setTheme: function (preference, resolved) { return window.InkstoneTheme.set(preference, resolved); },
      getTheme: function () { return window.InkstoneTheme.state(); },
      setViewOption: writingTools.setOption,
      find: writingTools.find,
      replace: writingTools.replace,
      setMode: setMode,
      setSourceLayout: setSourceLayout,
      getMode: function () { return currentMode; },
      toggleMode: toggleMode
    };
    document.getElementById('rich-mode').addEventListener('click', function () { setMode('wysiwyg'); });
    document.getElementById('source-mode').addEventListener('click', function () { setMode('source'); });
    document.getElementById('source-layout').addEventListener('change', function (event) { setSourceLayout(event.target.value); });
    document.addEventListener('paste', function (event) {
      if (!writingTools.editable()) { event.preventDefault(); return; }
      const items = event.clipboardData && event.clipboardData.items;
      if (!items) return;
      for (const item of items) {
        if (item.kind === 'file' && item.type.startsWith('image/')) {
          event.preventDefault();
          event.stopImmediatePropagation();
          const blob = item.getAsFile();
          if (blob) readBlob(blob, insertImage);
          return;
        }
      }
    }, true);
    document.addEventListener('keydown', function (event) {
      if (event.defaultPrevented || event.isComposing || event.keyCode === 229 || event.altKey || !(event.ctrlKey || event.metaKey)) return;
      const key = event.key.toLowerCase();
      const shift = !!event.shiftKey;
      let action;
      if (!shift && /^[1-6]$/.test(key)) action = function () { format('heading', Number(key)); };
      else if (!shift && key === '0') action = function () { format('paragraph'); };
      else if (!shift && key === 'b') action = function () { format('bold'); };
      else if (!shift && key === 'i') action = function () { format('italic'); };
      else if (shift && key === 'x') action = function () { format('strike'); };
      else if (shift && key === 'c') action = function () { format('codeblock'); };
      else if (!shift && key === 'q') action = function () { format('quote'); };
      else if (!shift && key === '`') action = function () { format('code'); };
      else if (key === 'z') action = function () { format(shift ? 'redo' : 'undo'); };
      else if (!shift && key === 'y') action = function () { format('redo'); };
      else if (shift && key === 'i') action = function () { if (native && typeof native.insertImage === 'function') native.insertImage(); };
      else if (shift && key === 'm') action = toggleMode;
      else if (!shift && key === '/') action = function () { setMode(currentMode === 'wysiwyg' ? 'source' : 'wysiwyg'); };
      else if (!shift && key === 's') action = function () { if (native) native.saveDocument(); };
      if (!action) return;
      // Capture before Toast UI's own keymaps, so every shortcut executes once.
      event.preventDefault();
      if (event.stopImmediatePropagation) event.stopImmediatePropagation();
      action();
    }, true);
    document.addEventListener('visibilitychange', function () { if (document.hidden) flush(); });
    if (native) native.ready();
    else window.inkstone.loadDocument('# 墨页\n\n离线 Markdown 编辑器。');
  } catch (error) {
    console.error('Inkstone initialization failed:', error);
    document.getElementById('editor').textContent = '编辑器加载失败。请返回后重新打开；本机草稿仍会保留。';
    notice('编辑器加载失败，草稿未删除。');
  }
})();
