const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assets = path.resolve(__dirname, '../app/src/main/assets/editor');
const utils = require(path.join(assets, 'markdown-utils.js'));
const image = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==';

function harness({ deferImages = false, storedMode } = {}) {
  const elements = new Map();
  const events = {};
  const changes = [];
  const notices = [];
  const bridgeCalls = [];
  const outlines = [];
  const activeCalls = [];
  const timers = new Map();
  const cancelled = [];
  const readers = [];
  const headingNodes = [];
  let timerId = 0;
  let ready = 0;
  let saves = 0;
  let editor;
  let config;
  const makeElement = () => {
    const element = { textContent: '', classes: new Set(), addEventListener() {} };
    element.classList = {
      toggle(name, force) {
        if (force === undefined) { if (element.classes.has(name)) element.classes.delete(name); else element.classes.add(name); }
        else if (force) element.classes.add(name); else element.classes.delete(name);
      },
      contains(name) { return element.classes.has(name); }
    };
    return element;
  };
  const document = {
    hidden: false,
    querySelectorAll() { return headingNodes; },
    querySelector() { return null; },
    getElementById(id) { if (!elements.has(id)) elements.set(id, makeElement()); return elements.get(id); },
    addEventListener(name, fn) { events[name] = fn; }
  };
  class Editor {
    constructor(options) { config = options; editor = this; this.text = ''; this.mode = 'wysiwyg'; this.previewStyle = ''; this.executed = []; }
    getMarkdown() { return this.text; }
    setMarkdown(text) { this.text = text; config.events.change(); }
    changeMode(mode) { this.mode = mode; }
    changePreviewStyle(style) { this.previewStyle = style; }
    isMarkdownMode() { return this.mode === 'markdown'; }
    focus() {}
    getSelectedText() { return this.text; }
    setSelection(start, end) { this.selection = [Array.from(start), Array.from(end)]; }
    getSelection() { return this.selection || [[1, 1], [1, 1]]; }
    insertText(text) { this.text += text; config.events.change(); }
    exec(command, options) {
      this.executed.push([command, options]);
      if (command === 'addImage') { this.text += `![${options.altText}](${options.imageUrl})`; config.events.change(); return; }
      if (command === 'heading') { this.text = options.level ? `${'#'.repeat(options.level)} ${this.text.replace(/^#+\s*/, '')}` : this.text.replace(/^#+\s*/, ''); config.events.change(); return; }
      if (command === 'bold') { this.text = `**${this.text}**`; config.events.change(); return; }
      if (command === 'italic') { this.text = `*${this.text}*`; config.events.change(); return; }
      if (command === 'strike') { this.text = `~~${this.text}~~`; config.events.change(); return; }
      if (command === 'blockQuote') { this.text = `> ${this.text}`; config.events.change(); return; }
      if (command === 'codeBlock') { this.text = '```\n' + this.text + '\n```'; config.events.change(); return; }
      if (command === 'undo' || command === 'redo') { this.text = this.text; config.events.change(); return; }
      if (command === 'addLink') { this.text = `[${options.linkText}](${options.linkUrl})`; config.events.change(); return; }
      throw new Error('Unsupported command: ' + command);
    }
  }
  const native = {
    ready() { ready++; }, changed(md, docId) { changes.push(md); bridgeCalls.push([md, docId]); },
    outlineChanged(json, docId) { outlines.push([JSON.parse(json), docId]); },
    activeHeadingChanged(index, docId) { activeCalls.push([index, docId]); },
    notice(text) { notices.push(text); }, saveDocument() { saves++; }
  };
  const store = new Map();
  const context = {
    document, InkstoneNative: native, InkstoneUtils: utils, toastui: { Editor },
    DOMPurify: { sanitize(html, options) { assert.equal(options.SANITIZE_NAMED_PROPS, true); return 'safe:' + html; } },
    localStorage: { getItem(key) { return store.get(key); }, setItem(key, val) { store.set(key, val); } },
    setTimeout(fn) { const id = ++timerId; timers.set(id, fn); return id; },
    clearTimeout(id) { if (timers.has(id)) cancelled.push(id); timers.delete(id); }, addEventListener() {}, console,
    FileReader: class { readAsDataURL() { this.result = image; readers.push(this); if (!deferImages) this.onload(); } }
  };
  context.window = context;
  vm.runInNewContext(fs.readFileSync(path.join(assets, 'app.js'), 'utf8'), context);
  return { api: context.inkstone, events, changes, notices, editor, config, ready, get saves() { return saves; }, elements,
    bridgeCalls, outlines, activeCalls, timers, cancelled, readers, headingNodes, native };
}

test('Unicode visible count does not count embedded image bytes', () => {
  assert.equal(utils.visibleLength('中文😀'), 3);
  assert.equal(utils.visibleLength(`A![图片](${image})B`), 3);
});
test('Only supported base64 raster image URLs accepted', () => {
  assert.equal(utils.allowedImageDataUrl(image), true);
  for (const bad of ['https://example.org/a.png', 'javascript:alert(1)', 'data:image/svg+xml;base64,AAAA', 'data:text/html;base64,AAAA']) {
    assert.equal(utils.allowedImageDataUrl(bad), false);
  }
});
test('Document size guard counts UTF-8 bytes', () => {
  assert.equal(utils.documentFits('中'.repeat(Math.floor(utils.MAX_DOCUMENT_BYTES / 3))), true);
  assert.equal(utils.documentFits('中'.repeat(Math.floor(utils.MAX_DOCUMENT_BYTES / 3) + 1)), false);
});
test('Editor initializes offline with sanitizer and no telemetry', () => {
  const h = harness();
  assert.equal(h.ready, 1);
  assert.equal(h.config.usageStatistics, false);
  assert.equal(h.config.customHTMLSanitizer('<p>x</p>'), 'safe:<p>x</p>');
});
test('Load synchronizes draft without losing unicode source', () => {
  const h = harness(); const text = '# 中文\n\n**测试**';
  h.api.loadDocument(text);
  assert.equal(h.api.getMarkdown(), text);
  assert.equal(h.changes.at(-1), text);
});
test('Plain-text clipboard insertion reaches the editor', () => {
  const h = harness(); h.api.loadDocument('hello '); h.api.insertText('世界');
  assert.equal(h.api.getMarkdown(), 'hello 世界');
  assert.equal(h.changes.at(-1), 'hello 世界');
});
test('Embedded image insertion is portable and autosaved', () => {
  const h = harness(); h.api.loadDocument(''); h.api.insertImage(image);
  assert.ok(h.api.getMarkdown().includes(image));
  assert.equal(h.changes.at(-1), h.api.getMarkdown());
});
test('SVG and remote image payloads cannot use native insertion API', () => {
  const h = harness(); h.api.loadDocument('safe'); h.api.insertImage('data:image/svg+xml;base64,AAAA');
  assert.equal(h.api.getMarkdown(), 'safe'); assert.equal(h.notices.length, 1);
});
test('Three modes are selectable and toggle cycles through all of them', () => {
  const h = harness(); h.api.loadDocument('text');
  assert.equal(h.api.getMode(), 'wysiwyg');
  assert.equal(h.api.setMode('source'), true);
  assert.equal(h.editor.mode, 'markdown');
  assert.equal(h.api.getMode(), 'source');
  assert.equal(h.api.setMode('split'), true);
  assert.equal(h.editor.mode, 'markdown', 'split keeps the live source editor');
  assert.equal(h.editor.previewStyle, 'vertical');
  assert.equal(h.api.setMode('unknown'), false);
  assert.equal(h.api.getMode(), 'split');
  assert.equal(h.api.toggleMode(), 'wysiwyg');
  assert.equal(h.api.toggleMode(), 'source');
  assert.equal(h.api.toggleMode(), 'split');
  assert.equal(h.api.getMarkdown(), 'text');
});
test('Mode classes drive the source/split layout', () => {
  const h = harness(); h.api.loadDocument('text');
  const editor = h.elements.get('editor');
  h.api.setMode('source');
  assert.ok(editor.classList.contains('mode-source'));
  assert.ok(!editor.classList.contains('mode-wysiwyg'));
  h.api.setMode('split');
  assert.ok(editor.classList.contains('mode-split'));
  h.api.setMode('wysiwyg');
  assert.ok(editor.classList.contains('mode-wysiwyg'));
});
test('Paragraph restores plain text by clearing the heading level', () => {
  const h = harness(); h.api.loadDocument('## 标题');
  assert.equal(h.api.format('paragraph'), true);
  assert.equal(h.editor.executed.at(-1)[0], 'heading', 'Paragraph uses the heading command with level 0');
  assert.equal(h.editor.executed.at(-1)[1].level, 0);
  assert.equal(h.api.getMarkdown(), '标题');
});
test('Format commands apply the requested block and inline styles', () => {
  const h = harness(); h.api.loadDocument('正文');
  assert.equal(h.api.format('heading', 2), true);
  assert.equal(h.api.getMarkdown(), '## 正文');
  assert.equal(h.api.format('bold'), true);
  assert.equal(h.api.getMarkdown(), '**## 正文**');
  assert.equal(h.api.format('italic'), true);
  assert.equal(h.api.format('strike'), true);
  assert.equal(h.api.format('quote'), true);
  assert.equal(h.api.format('codeblock'), true);
  assert.ok(h.editor.executed.map(([name]) => name).includes('codeBlock'));
});
test('Format rejects unknown commands and invalid heading levels', () => {
  const h = harness(); h.api.loadDocument('正文');
  assert.equal(h.api.format('heading', 0), false);
  assert.equal(h.api.format('heading', 7), false);
  assert.equal(h.api.format('heading', 1.5), false);
  assert.equal(h.api.format('unknown'), false);
  assert.equal(h.api.getMarkdown(), '正文');
});
test('Link format validates the URL scheme', () => {
  const h = harness(); h.api.loadDocument('链接');
  assert.equal(h.api.format('link', 'javascript:alert(1)'), false);
  assert.equal(h.api.format('link', 'https://example.org/a b'), false);
  assert.equal(h.api.format('link', 'https://example.org'), true);
  assert.equal(h.api.getMarkdown(), '[链接](https://example.org)');
});
test('Keyboard shortcuts map to format commands and prevent the browser default once', () => {
  const h = harness(); h.api.loadDocument('正文');
  const events = [];
  const key = (init) => {
    let prevented = 0; let stopped = 0;
    h.events.keydown(Object.assign({ preventDefault() { prevented++; }, stopImmediatePropagation() { stopped++; },
      isComposing: false, defaultPrevented: false, keyCode: 0 }, init));
    events.push({ prevented, stopped });
    return events.at(-1);
  };
  key({ ctrlKey: true, key: '2' });
  assert.equal(h.editor.executed.at(-1)[0], 'heading');
  assert.equal(h.editor.executed.at(-1)[1].level, 2);
  key({ ctrlKey: true, key: '0' });
  assert.equal(h.editor.executed.at(-1)[0], 'heading');
  assert.equal(h.editor.executed.at(-1)[1].level, 0);
  key({ ctrlKey: true, key: 'b' });
  assert.equal(h.editor.executed.at(-1)[0], 'bold');
  key({ ctrlKey: true, key: 'i' });
  assert.equal(h.editor.executed.at(-1)[0], 'italic');
  key({ ctrlKey: true, shiftKey: true, key: 'x' });
  assert.equal(h.editor.executed.at(-1)[0], 'strike');
  key({ ctrlKey: true, shiftKey: true, key: 'c' });
  assert.equal(h.editor.executed.at(-1)[0], 'codeBlock');
  key({ ctrlKey: true, key: 'q' });
  assert.equal(h.editor.executed.at(-1)[0], 'blockQuote');
  key({ ctrlKey: true, key: 'z' });
  assert.equal(h.editor.executed.at(-1)[0], 'undo');
  key({ ctrlKey: true, shiftKey: true, key: 'z' });
  assert.equal(h.editor.executed.at(-1)[0], 'redo');
  assert.ok(events.every((item) => item.prevented === 1 && item.stopped === 1));
});
test('Ctrl+Shift+I requests the native image picker instead of formatting', () => {
  const h = harness(); h.api.loadDocument('正文');
  let images = 0;
  h.native.insertImage = () => { images++; };
  let prevented = 0;
  h.events.keydown({ ctrlKey: true, shiftKey: true, key: 'i', preventDefault() { prevented++; }, stopImmediatePropagation() {}, isComposing: false, defaultPrevented: false });
  assert.equal(images, 1);
  assert.equal(prevented, 1);
  assert.equal(h.editor.executed.length, 0);
});
test('Ctrl+Shift+M cycles modes and Ctrl+/ toggles source', () => {
  const h = harness(); h.api.loadDocument('正文');
  h.events.keydown({ ctrlKey: true, shiftKey: true, key: 'm', preventDefault() {}, stopImmediatePropagation() {}, isComposing: false, defaultPrevented: false });
  assert.equal(h.api.getMode(), 'source');
  h.events.keydown({ ctrlKey: true, key: '/', preventDefault() {}, stopImmediatePropagation() {}, isComposing: false, defaultPrevented: false });
  assert.equal(h.api.getMode(), 'wysiwyg');
});
test('Plain typing and AltGr combinations are never hijacked', () => {
  const h = harness(); h.api.loadDocument('正文');
  const before = h.editor.executed.length;
  h.events.keydown({ ctrlKey: false, key: 'b', preventDefault() {}, stopImmediatePropagation() {}, isComposing: false, defaultPrevented: false });
  h.events.keydown({ ctrlKey: true, altKey: true, key: 's', preventDefault() {}, stopImmediatePropagation() {}, isComposing: false, defaultPrevented: false });
  h.events.keydown({ ctrlKey: true, key: 'b', isComposing: true, preventDefault() {}, stopImmediatePropagation() {}, defaultPrevented: false });
  assert.equal(h.editor.executed.length, before);
});
test('Source and rich mode can toggle without changing contents', () => {
  const h = harness(); h.api.loadDocument('text'); h.api.toggleMode();
  assert.equal(h.editor.mode, 'markdown'); assert.equal(h.api.getMarkdown(), 'text');
});
test('Browser image paste is intercepted exactly once', () => {
  const h = harness(); h.api.loadDocument(''); let prevented = 0; let stopped = 0;
  h.events.paste({ clipboardData: { items: [{ kind: 'file', type: 'image/png', getAsFile() { return {size: 100, type: 'image/png'}; } }] }, preventDefault() { prevented++; }, stopImmediatePropagation() { stopped++; } });
  assert.equal(prevented, 1); assert.equal(stopped, 1);
  assert.equal((h.api.getMarkdown().match(/!\[/g) || []).length, 1);
});
test('Text paste keeps normal browser behavior', () => {
  const h = harness(); let prevented = false;
  h.events.paste({ clipboardData: { items: [{ kind: 'string', type: 'text/plain' }] }, preventDefault() { prevented = true; } });
  assert.equal(prevented, false);
});
test('Ctrl+S requests native save instead of browser download', () => {
  const h = harness(); let prevented = false;
  h.events.keydown({ ctrlKey: true, key: 's', preventDefault() { prevented = true; } });
  assert.equal(h.saves, 1); assert.equal(prevented, true);
});
test('Switch cancels debounce and captured old callbacks cannot autosave or publish outlines', () => {
  const h = harness();
  h.api.loadDocument('# Old', 'old');
  h.editor.insertText('\n## pending');
  const [id, staleCallback] = Array.from(h.timers.entries()).at(-1);
  h.api.loadDocument('# New', 'new');
  assert.ok(h.cancelled.includes(id));
  const count = h.bridgeCalls.length;
  const outlineCount = h.outlines.length;
  staleCallback();
  assert.equal(h.bridgeCalls.length, count);
  assert.equal(h.outlines.length, outlineCount);
  assert.deepEqual(h.bridgeCalls.at(-1), ['# New', 'new']);
  assert.deepEqual(h.outlines.at(-1), [[{ level: 1, text: 'New', line: 1, index: 0 }], 'new']);
  // Reopening the same ID also invalidates callbacks from the previous load.
  h.editor.insertText(' pending');
  const sameIdCallback = Array.from(h.timers.values()).at(-1);
  h.api.loadDocument('# Reloaded', 'new');
  const reloadedCount = h.bridgeCalls.length;
  sameIdCallback();
  assert.equal(h.bridgeCalls.length, reloadedCount);
});
test('A document switch during the native callback cannot publish the old outline', () => {
  const h = harness();
  const changed = h.native.changed;
  h.native.changed = (text, docId) => {
    changed(text, docId);
    if (docId === 'old') h.api.loadDocument('# New', 'new');
  };
  h.api.loadDocument('# Old', 'old');
  assert.deepEqual(h.outlines.map(([, id]) => id), ['new']);
});
test('Legacy loads use an empty document ID and outline bridge is optional', () => {
  const h = harness(); delete h.native.outlineChanged;
  h.api.loadDocument('# Legacy');
  assert.deepEqual(h.bridgeCalls.at(-1), ['# Legacy', '']);
});
test('Async paste from previous document cannot insert into the new document', () => {
  const h = harness({ deferImages: true });
  h.api.loadDocument('old', 'old');
  h.events.paste({ clipboardData: { items: [{ kind: 'file', type: 'image/png', getAsFile() { return { size: 100, type: 'image/png' }; } }] }, preventDefault() {}, stopImmediatePropagation() {} });
  h.api.loadDocument('new', 'new');
  h.readers[0].onload();
  assert.equal(h.api.getMarkdown(), 'new');
});
test('Outline navigation targets ordered rich headings and source line selections', () => {
  const h = harness();
  assert.equal(h.api.scrollToHeading(0), false);
  h.api.loadDocument('# Same\n\n## Same', 'nav');
  const scrolled = [];
  h.headingNodes.push({ scrollIntoView() { scrolled.push(0); } }, { scrollIntoView() { scrolled.push(1); } });
  assert.equal(h.api.scrollToHeading(1), true);
  assert.deepEqual(scrolled, [1]);
  for (const index of [-1, 2, 1.5, '0']) assert.equal(h.api.scrollToHeading(index), false);
  h.api.toggleMode();
  assert.equal(h.api.scrollToHeading(1), true);
  assert.deepEqual(h.editor.selection, [[3, 1], [3, 1]]);
  h.editor.setSelection = undefined;
  assert.equal(h.api.scrollToHeading(0), false);
});
test('Formatting visibility toggles independently of rich/source mode', () => {
  const h = harness(); h.api.loadDocument('text');
  const toggles = [];
  h.elements.get('editor').classList.toggle = (...args) => toggles.push(args);
  assert.equal(h.api.toggleFormatting(), true);
  assert.equal(h.api.toggleFormatting(), false);
  assert.deepEqual(toggles, [['formatting-visible', true], ['formatting-visible', false]]);
  assert.equal(h.editor.mode, 'wysiwyg');
});
test('Active outline heading follows the source cursor and deduplicates unchanged positions', () => {
  const h = harness();
  h.api.setMode('source'); h.api.loadDocument('# 第一节\n\n## 第二节\n\n正文', 'active-doc');
  h.api.setSelection([5, 1], [5, 1]);
  h.config.events.caretChange();
  Array.from(h.timers.values()).at(-1)();
  assert.deepEqual(h.activeCalls.at(-1), [1, 'active-doc']);
  const count = h.activeCalls.length;
  h.api.refreshOutline();
  assert.equal(h.activeCalls.length, count);
});
test('Delayed active heading events cannot leak across document switches', () => {
  const h = harness();
  h.api.setMode('source'); h.api.loadDocument('# Old\n\n## Child', 'old');
  h.config.events.caretChange();
  const oldTimer = Array.from(h.timers.values()).at(-1);
  h.api.loadDocument('# New', 'new');
  const count = h.activeCalls.length;
  oldTimer();
  assert.equal(h.activeCalls.length, count);
  assert.deepEqual(h.activeCalls.at(-1), [0, 'new']);
});
test('An empty document clears active outline selection', () => {
  const h = harness();
  h.api.loadDocument('# Title', 'first');
  h.api.loadDocument('', 'empty');
  assert.deepEqual(h.activeCalls.at(-1), [-1, 'empty']);
});
test('WebView page policy forbids inline scripts and network connections', () => {
  const html = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');
  assert.ok(html.includes("script-src 'self'")); assert.ok(html.includes("connect-src 'none'"));
  assert.ok(!/script-src[^;]*unsafe-inline/.test(html));
  const manifest = fs.readFileSync(path.join(assets, '../../AndroidManifest.xml'), 'utf8');
  assert.ok(!manifest.includes('android.permission.INTERNET'));
});
