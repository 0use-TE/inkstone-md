const { JSDOM, ResourceLoader, VirtualConsole } = require('jsdom');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../app/src/main/assets/editor');
const image = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==';
class LocalAssets extends ResourceLoader {
  fetch(url) {
    const parsed = new URL(url);
    if (parsed.host !== 'appassets.androidplatform.net' || !parsed.pathname.startsWith('/assets/editor/')) {
      throw new Error('Unexpected network request: ' + url);
    }
    const local = path.resolve(root, parsed.pathname.slice('/assets/editor/'.length));
    if (!local.startsWith(root + path.sep)) throw new Error('Unsafe asset path');
    return Promise.resolve(fs.readFileSync(local));
  }
}
let resolveReady;
let rejectReady;
const ready = new Promise((resolve, reject) => { resolveReady = resolve; rejectReady = reject; });
const errors = [];
const notices = [];
const changes = [];
const bridgeCalls = [];
const outlines = [];
const activeCalls = [];
const scrolled = [];
const console = new VirtualConsole();
console.on('jsdomError', error => { if (!error.message.includes('Not implemented:')) errors.push(error.message); });
console.on('error', (...args) => { errors.push(args.map(String).join(' ')); process.stderr.write(args.map(String).join(' ') + '\n'); });
const dom = new JSDOM(fs.readFileSync(path.join(root, 'index.html'), 'utf8'), {
  url: 'https://appassets.androidplatform.net/assets/editor/index.html',
  pretendToBeVisual: true,
  runScripts: 'dangerously',
  resources: new LocalAssets(),
  virtualConsole: console,
  beforeParse(window) {
    window.TextEncoder = TextEncoder;
    window.TextDecoder = TextDecoder;
    window.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });
    window.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
    window.scrollTo = () => {};
    window.document.elementFromPoint = () => window.document.body;
    window.document.elementsFromPoint = () => [window.document.body];
    window.Element.prototype.scrollIntoView = function () { scrolled.push(this); };
    window.Range.prototype.getClientRects = () => [];
    window.Range.prototype.getBoundingClientRect = () => ({ x:0,y:0,top:0,left:0,right:0,bottom:0,width:0,height:0 });
    window.InkstoneNative = {
      ready() { resolveReady(); },
      changed(text, docId) { changes.push(text); bridgeCalls.push([text, docId]); },
      outlineChanged(json, docId) { outlines.push([JSON.parse(json), docId]); },
      activeHeadingChanged(index, docId) { activeCalls.push([index, docId]); },
      notice(text) { notices.push(text); if (text.includes('加载失败')) rejectReady(new Error(text)); },
      saveDocument() {},
      insertImage() {}
    };
  }
});
(async () => {
  const timer = setTimeout(() => rejectReady(new Error('Editor startup timeout')), 10000);
  try {
    await ready;
    clearTimeout(timer);
    const w = dom.window;
    assert.equal(w.DOMPurify.version, '3.3.0');
    w.inkstone.loadDocument('# 标题\n\n这是 **真实编辑器** 测试。\n', 'first');

    // Three modes: rich writing, source only, and source with a live preview.
    assert.equal(w.inkstone.getMode(), 'wysiwyg');
    assert.equal(w.inkstone.setMode('source'), true);
    assert.equal(w.inkstone.getMode(), 'source');
    assert.equal(w.inkstone.setMode('split'), true);
    assert.equal(w.inkstone.getMode(), 'split');
    assert.equal(w.inkstone.setMode('wysiwyg'), true);
    assert.equal(w.inkstone.setMode('nope'), false);
    assert.equal(w.inkstone.getMode(), 'wysiwyg');
    assert.equal(w.inkstone.toggleMode(), 'source');
    assert.equal(w.inkstone.toggleMode(), 'split');
    assert.equal(w.inkstone.toggleMode(), 'wysiwyg');

    const editor = w.document.querySelector('#editor');
    assert.ok(editor.classList.contains('mode-wysiwyg'));
    w.inkstone.setMode('split');
    assert.ok(editor.classList.contains('mode-split'));
    assert.ok(!editor.classList.contains('mode-source'));

    // No save-status wording may remain in the chrome.
    const chrome = w.document.body.textContent;
    assert.ok(!chrome.includes('本地草稿'));
    assert.ok(!chrome.includes('已保存'));
    assert.ok(!chrome.includes('离线'));

    // Real rich-text formatting.
    w.inkstone.setMode('wysiwyg');
    w.inkstone.loadDocument('正文', 'format');
    assert.equal(w.inkstone.format('heading', 2), true);
    assert.match(w.inkstone.getMarkdown(), /^##\s/);
    assert.equal(w.inkstone.format('bold'), true);
    assert.equal(w.inkstone.format('italic'), true);
    assert.equal(w.inkstone.format('heading', 9), false);

    // Inline marks on a real selection are verified in the source editor,
    // where CodeMirror selection is deterministic in this DOM harness.
    w.inkstone.setMode('source');
    w.inkstone.loadDocument('正文', 'inline');
    w.inkstone.setSelection([1, 1], [1, 3]);
    assert.equal(w.inkstone.format('bold'), true);
    assert.match(w.inkstone.getMarkdown(), /\*\*正文\*\*/);
    w.inkstone.loadDocument('正文', 'inline2');
    w.inkstone.setSelection([1, 1], [1, 3]);
    assert.equal(w.inkstone.format('italic'), true);
    assert.match(w.inkstone.getMarkdown(), /\*正文\*/);

    // Real source-mode formatting uses the Markdown editor commands.
    w.inkstone.setMode('source');
    w.inkstone.loadDocument('源码正文', 'source');
    assert.equal(w.inkstone.format('heading', 3), true);
    assert.match(w.inkstone.getMarkdown(), /^###\s/);

    // Outline navigation works in rich mode and selects a line in source mode.
    w.inkstone.setMode('wysiwyg');
    w.inkstone.loadDocument('# Duplicate\n\n## Duplicate\n\n尾部\n---', 'outline');
    assert.deepEqual(outlines.at(-1), [[
      { level: 1, text: 'Duplicate', line: 1, index: 0 },
      { level: 2, text: 'Duplicate', line: 3, index: 1 },
      { level: 2, text: '尾部', line: 5, index: 2 }
    ], 'outline']);
    assert.equal(w.inkstone.scrollToHeading(2), true);
    assert.equal(scrolled.at(-1).textContent, '尾部');
    w.inkstone.setMode('source');
    assert.equal(w.inkstone.scrollToHeading(2), true);
    w.inkstone.setMode('wysiwyg');

    // Images survive every mode change.
    w.inkstone.insertImage(image);
    assert.ok(w.inkstone.getMarkdown().includes(image), 'Actual editor must preserve data URI images');
    assert.ok(w.document.querySelector('#editor img[src^="data:image/png"]'));
    w.inkstone.setMode('source');
    assert.ok(w.inkstone.getMarkdown().includes(image), 'Image must survive source mode conversion');
    w.inkstone.setMode('split');
    assert.ok(w.inkstone.getMarkdown().includes(image), 'Image must survive split mode conversion');
    w.inkstone.setMode('wysiwyg');
    assert.ok(w.inkstone.getMarkdown().includes(image), 'Image must survive rich mode conversion');

    // Restoring a plain paragraph removes the heading marker in the real editor.
    w.inkstone.setMode('wysiwyg');
    w.inkstone.loadDocument('## 标题正文', 'paragraph');
    assert.equal(w.inkstone.format('paragraph'), true);
    assert.ok(!w.inkstone.getMarkdown().includes('#'), 'Heading marker must be removed: ' + w.inkstone.getMarkdown());
    w.inkstone.setMode('source');
    w.inkstone.loadDocument('### 源码标题', 'paragraph2');
    assert.equal(w.inkstone.format('paragraph'), true);
    assert.ok(!w.inkstone.getMarkdown().includes('#'), 'Source heading marker must be removed: ' + w.inkstone.getMarkdown());

    // Ctrl+0 must work on every heading level and both Markdown heading syntaxes.
    for (const mode of ['wysiwyg', 'source', 'split']) {
      w.inkstone.setMode(mode);
      const cases = [
        ...[1, 2, 3, 4, 5, 6].map(level => '#'.repeat(level) + ' 标题正文'),
        '标题正文\n===', '标题正文\n---'
      ];
      for (let i = 0; i < cases.length; i++) {
        w.inkstone.loadDocument(cases[i] + '\n\n保留段落', 'paragraph-' + mode + '-' + i);
        if (mode === 'wysiwyg') w.inkstone.setSelection(1, 1);
        else w.inkstone.setSelection([1, 1], [1, 1]);
        assert.equal(w.inkstone.format('paragraph'), true, mode + ': ' + cases[i]);
        assert.deepEqual(Array.from(w.inkstone.getMarkdown().split('\n'), line => line.trim()).filter(Boolean), ['标题正文', '保留段落'], mode + ': must remove heading syntax and preserve text');
      }
    }

    // Both editors use ProseMirror history: undo/redo must not depend on a nonexistent CodeMirror wrapper.
    for (const mode of ['wysiwyg', 'source', 'split']) {
      w.inkstone.setMode(mode);
      w.inkstone.loadDocument('## 历史测试', 'history-' + mode);
      if (mode === 'wysiwyg') w.inkstone.setSelection(1, 1);
      else w.inkstone.setSelection([1, 1], [1, 1]);
      assert.equal(w.inkstone.format('paragraph'), true);
      const after = w.inkstone.getMarkdown();
      assert.equal(w.inkstone.format('undo'), true, mode + ': undo must be available');
      assert.equal(w.inkstone.format('redo'), true, mode + ': redo must be available');
      assert.equal(w.inkstone.getMarkdown(), after, mode + ': redo restores paragraph conversion');
    }

    // Active outline callbacks use the real source cursor and remain document-scoped.
    w.inkstone.setMode('source');
    w.inkstone.loadDocument('# 第一节\n\n## 第二节\n\n正文', 'active-real');
    w.inkstone.setSelection([5, 1], [5, 1]);
    w.inkstone.refreshOutline();
    assert.deepEqual(activeCalls.at(-1), [1, 'active-real']);
    w.inkstone.loadDocument('', 'empty-real');
    assert.deepEqual(activeCalls.at(-1), [-1, 'empty-real']);

    // Unsafe HTML from imported Markdown is filtered.
    w.inkstone.loadDocument('<img src="x" onerror="window.__pwned=1"><a href="javascript:window.__pwned=2">bad</a><iframe src="https://example.org"></iframe>');
    assert.equal(w.document.querySelector('#editor [onerror]'), null);
    assert.equal(w.document.querySelector('#editor a[href^="javascript:"]'), null);
    assert.equal(w.document.querySelector('#editor iframe'), null);
    assert.equal(w.__pwned, undefined);

    // A document switch must not leak old callbacks into the new document.
    w.inkstone.insertText(' pending');
    w.inkstone.loadDocument('# Switched', 'switched');
    const switchPosition = bridgeCalls.length - 1;
    const outlinePosition = outlines.length - 1;
    await new Promise(resolve => setTimeout(resolve, 850));
    assert.ok(bridgeCalls.slice(switchPosition).every(([, id]) => id === 'switched'));
    assert.ok(outlines.slice(outlinePosition).every(([, id]) => id === 'switched'));
    assert.equal(bridgeCalls.at(-1)[0], '# Switched');
    assert.ok(changes.length >= 4, 'Native autosave bridge must receive editor contents');
    assert.deepEqual(errors, []);
    process.stdout.write('PASS: real editor startup, three modes, real rich/source formatting, outline navigation, embedded image roundtrip across modes, sanitizer 3.3.0, unsafe HTML filtering, document isolation.\n');
    if (notices.length) process.stdout.write('Notices: ' + JSON.stringify(notices) + '\n');
  } finally {
    clearTimeout(timer);
    dom.window.close();
  }
})().catch(error => { process.stderr.write(error.stack + '\n'); process.exitCode = 1; });
