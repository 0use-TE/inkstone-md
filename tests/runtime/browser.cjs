const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const root = path.resolve(__dirname, '../../app/src/main/assets/editor');
const server = http.createServer((req, res) => {
  const pathname = new URL(req.url, 'http://localhost').pathname;
  const file = path.join(root, pathname === '/' ? 'index.html' : pathname);
  if (!file.startsWith(root + path.sep) || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
  let data = fs.readFileSync(file);
  if (pathname.endsWith('/writing-tools.js')) data = Buffer.from(data + '\n(function(){ const create=window.InkstoneWritingTools.create; window.InkstoneWritingTools.create=function(editor, callbacks){ window.__testEditor=editor; return window.__testTools=create(editor, callbacks); }; })();');
  res.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
  res.end(data);
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ channel: process.env.INKSTONE_BROWSER_CHANNEL || 'chrome', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 412, height: 780 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(() => !!window.inkstone);
    const markdown = '# 标题\n\n**粗体** 和 *斜体* 与 [链接](https://example.com)\n\n其他段落';
    await page.evaluate(markdown => {
      window.inkstone.loadDocument(markdown, 'live-preview');
      window.inkstone.setMode('wysiwyg');
      window.inkstone.setSelection([5, 1], [5, 1]);
    }, markdown);
    const body = page.locator('.toastui-editor-md-container .ProseMirror');
    assert.ok(!(await body.innerText()).includes('**'), 'Inactive bold markers are hidden');
    assert.ok(!(await body.innerText()).includes('# 标题'), 'Inactive heading markers are hidden');
    assert.equal(await body.locator('.inkstone-live-strong').first().evaluate(node => getComputedStyle(node).fontWeight), '700');
    await page.evaluate(() => window.inkstone.setSelection([3, 4], [3, 4]));
    assert.ok((await body.innerText()).includes('**粗体**'), 'Caret reveals the edited bold scope');
    assert.ok(!(await body.innerText()).includes('*斜体*'), 'Other inline scopes stay rendered');
    await page.evaluate(() => window.inkstone.setMode('source'));
    assert.ok((await body.innerText()).includes('**粗体**'));
    assert.equal(await page.evaluate(() => window.inkstone.getMarkdown()), markdown);
    await page.evaluate(() => window.inkstone.setSourceLayout('preview'));
    assert.equal(await page.locator('.toastui-editor-md-container > .toastui-editor').isVisible(), false);
    assert.equal(await page.locator('.toastui-editor-md-preview').isVisible(), true);
    assert.equal(await page.evaluate(() => window.inkstone.getMode()), 'source');
    await page.evaluate(() => window.inkstone.setSourceLayout('split'));
    assert.equal(await page.locator('.toastui-editor-md-container > .toastui-editor').isVisible(), true);
    assert.equal(await page.locator('.toastui-editor-md-preview').isVisible(), true);
    assert.equal(await page.locator('.mode-switch button').count(), 2);

    await page.evaluate(() => {
      window.inkstone.setMode('wysiwyg');
      window.inkstone.loadDocument('', 'empty-bold');
      window.inkstone.setSelection([1, 1], [1, 1]);
    });
    await page.keyboard.press('Control+b');
    assert.equal(await page.evaluate(() => window.inkstone.getMarkdown()), '****');
    assert.deepEqual(await page.evaluate(() => window.__testEditor.getSelection()), [[1, 3], [1, 3]]);
    await page.keyboard.insertText('加粗文字');
    assert.equal(await page.evaluate(() => window.inkstone.getMarkdown()), '**加粗文字**');
    assert.equal(await body.locator('.inkstone-live-strong').first().evaluate(node => getComputedStyle(node).fontWeight), '700');
    await page.keyboard.press('Control+b');
    await page.keyboard.insertText('普通文字');
    assert.equal(await page.evaluate(() => window.inkstone.getMarkdown()), '**加粗文字**普通文字');
    await page.evaluate(() => window.inkstone.insertText('\n\n**手输粗体**\n\n末段'));
    assert.ok(!(await body.innerText()).includes('**'));
    fs.mkdirSync(path.resolve(__dirname, '../../dev-runs'), { recursive: true });
    await page.screenshot({ path: path.resolve(__dirname, '../../dev-runs/live-preview-mobile.png') });
    await page.evaluate(() => {
      window.inkstone.loadDocument('新文档', 'history-isolation');
      window.inkstone.format('undo');
    });
    assert.equal(await page.evaluate(() => window.inkstone.getMarkdown()), '新文档', 'Undo must not resurrect another document');
    await page.evaluate(() => {
      window.inkstone.insertText('新增');
      window.inkstone.setMode('source');
      window.inkstone.setSourceLayout('split');
      window.inkstone.setMode('wysiwyg');
      window.inkstone.format('undo');
    });
    assert.equal(await page.evaluate(() => window.inkstone.getMarkdown()), '新文档', 'View changes keep the same undo history');

    await page.evaluate(() => {
      window.inkstone.loadDocument(Array.from({length: 50}, (_, i) => `第 ${i + 1} 段：打字机模式应当让输入行保持在可见区域中。`).join('\n\n'), 'browser');
      window.inkstone.setMode('wysiwyg');
      const view = window.__testEditor.getCurrentModeEditor().view;
      const end = view.state.doc.content.size - 1;
      window.inkstone.setSelection(end, end);
      window.inkstone.setViewOption('typewriter', true);
    });
    await page.waitForTimeout(150);
    async function alignment() {
      return page.evaluate(() => {
        const view = window.__testEditor.getCurrentModeEditor().view;
        const caret = view.coordsAtPos(view.state.selection.head);
        const rect = document.querySelector('.toastui-editor-main').getBoundingClientRect();
        return { caret: (caret.top + caret.bottom) / 2, target: rect.top + rect.height * 0.45 };
      });
    }
    let position = await alignment();
    assert.ok(Math.abs(position.caret - position.target) < 6, JSON.stringify(position));
    await page.keyboard.insertText('继续输入中文');
    await page.waitForTimeout(150);
    position = await alignment();
    assert.ok(Math.abs(position.caret - position.target) < 6, 'Caret notifications must not cancel input following: ' + JSON.stringify(position));
    await page.setViewportSize({ width: 412, height: 430 });
    await page.waitForTimeout(150);
    position = await alignment();
    assert.ok(Math.abs(position.caret - position.target) < 6, 'Keyboard-sized viewport: ' + JSON.stringify(position));

    await page.evaluate(() => {
      const scroller = window.__testEditor.getCurrentModeEditor().view.dom;
      scroller.scrollTop = 0;
      document.dispatchEvent(new CompositionEvent('compositionstart'));
      window.__testTools.changed();
    });
    await page.waitForTimeout(150);
    assert.equal(await page.evaluate(() => window.__testEditor.getCurrentModeEditor().view.dom.scrollTop), 0, 'Composition must pause typewriter following');
    await page.evaluate(() => document.dispatchEvent(new CompositionEvent('compositionend')));
    await page.waitForTimeout(150);
    position = await alignment();
    assert.ok(Math.abs(position.caret - position.target) < 6, 'Following resumes after composition');
    fs.mkdirSync(path.resolve(__dirname, '../../dev-runs'), { recursive: true });
    await page.screenshot({ path: path.resolve(__dirname, '../../dev-runs/typewriter-mobile.png') });

    await page.evaluate(() => {
      window.inkstone.setViewOption('typewriter', false);
      window.inkstone.loadDocument('- [ ] 阅读时不能切换任务\n\n第二段', 'readonly');
      window.inkstone.setViewOption('readOnly', true);
    });
    const locked = await page.evaluate(() => window.inkstone.getMarkdown());
    const task = page.locator('.toastui-editor-md-container [data-task]').first();
    await task.click({ position: { x: 5, y: 10 } });
    await page.keyboard.insertText('不能写入');
    await page.locator('.toastui-editor-md-container .ProseMirror').dispatchEvent('keydown', { key: 'u', ctrlKey: true, bubbles: true, cancelable: true });
    assert.equal(await page.evaluate(() => window.inkstone.getMarkdown()), locked, 'Readonly blocks task toggles, typing and editor keymaps');

    for (const mode of ['source', 'split']) {
      await page.evaluate(mode => {
        window.inkstone.setMode(mode);
        window.inkstone.loadDocument(Array.from({length: 40}, (_, i) => `第 ${i + 1} 行源码内容`).join('\n'), 'typewriter-' + mode);
        const editor = window.__testEditor;
        const end = editor.getCurrentModeEditor().view.state.doc.content.size - 1;
        const positions = editor.convertPosToMatchEditorMode(end, end);
        window.inkstone.setSelection(positions[0], positions[1]);
        window.inkstone.setViewOption('typewriter', true);
      }, mode);
      await page.waitForTimeout(150);
      position = await alignment();
      assert.ok(Math.abs(position.caret - position.target) < 6, mode + ': ' + JSON.stringify(position));
      await page.evaluate(() => window.inkstone.setViewOption('typewriter', false));
    }
    // Theme changes use the same editor instance, including selection and undo history.
    await page.evaluate(() => {
      window.inkstone.setViewOption('readOnly', false);
      window.inkstone.setMode('wysiwyg');
      window.inkstone.loadDocument('# 主题测试\n\n**粗体** 与 `代码`\n\n> 引用\n\n| 列一 | 列二 |\n| --- | --- |\n| 内容 | 内容 |\n\n```js\nconst hello = 1;\n```\n\n末段', 'theme');
      window.inkstone.setSelection([15, 3], [15, 3]);
      window.inkstone.insertText('新增');
    });
    const beforeTheme = await page.evaluate(() => ({ markdown: window.inkstone.getMarkdown(), selection: window.__testEditor.getSelection() }));
    const scrollBeforeTheme = await body.evaluate(node => node.scrollTop);
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.evaluate(() => window.inkstone.setTheme('system'));
    assert.deepEqual(await page.evaluate(() => window.inkstone.getTheme()), { preference: 'system', resolved: 'dark' });
    const styles = await page.evaluate(() => {
      const color = selector => getComputedStyle(document.querySelector(selector)).color;
      const background = selector => getComputedStyle(document.querySelector(selector)).backgroundColor;
      return { paper: background('body'), source: color('.ProseMirror'), quote: color('.inkstone-live-block blockquote p'),
        cell: color('.inkstone-live-block td'), code: color('.inkstone-live-block pre code'), scheme: getComputedStyle(document.documentElement).colorScheme };
    });
    assert.equal(styles.paper, 'rgb(30, 30, 30)');
    assert.equal(styles.source, 'rgb(229, 229, 229)');
    assert.equal(styles.quote, 'rgb(184, 184, 184)');
    assert.equal(styles.cell, 'rgb(229, 229, 229)');
    assert.equal(styles.code, 'rgb(229, 229, 229)');
    assert.equal(styles.scheme, 'dark');
    assert.equal(await body.evaluate(node => node.scrollTop), scrollBeforeTheme, 'Theme switching keeps the editor scroll position');
    await page.setViewportSize({ width: 1100, height: 780 });
    await page.screenshot({ path: path.resolve(__dirname, '../../dev-runs/theme-dark-writing.png') });
    await page.evaluate(() => { window.inkstone.setSourceLayout('split'); window.inkstone.toggleFormatting(); });
    await page.waitForTimeout(350); // Allow the editor's toolbar ResizeObserver to settle.
    assert.equal(await page.locator('.toastui-editor-md-preview p').first().evaluate(node => getComputedStyle(node).color), 'rgb(229, 229, 229)');
    assert.equal(await page.locator('#source-layout').evaluate(node => getComputedStyle(node).colorScheme), 'dark');
    await page.screenshot({ path: path.resolve(__dirname, '../../dev-runs/theme-dark-split.png') });
    assert.deepEqual(await page.evaluate(() => ({ markdown: window.inkstone.getMarkdown(), selection: window.__testEditor.getSelection() })), beforeTheme);
    await page.evaluate(() => { window.inkstone.setTheme('light'); window.inkstone.format('undo'); });
    assert.ok(!(await page.evaluate(() => window.inkstone.getMarkdown())).includes('新增'), 'Theme switching must retain undo history');
    await page.emulateMedia({ colorScheme: 'light' });
    await page.emulateMedia({ colorScheme: 'dark' });
    assert.deepEqual(await page.evaluate(() => window.inkstone.getTheme()), { preference: 'light', resolved: 'light' });
    await page.evaluate(() => window.inkstone.setTheme('dark'));
    await page.reload();
    await page.waitForFunction(() => !!window.inkstone);
    assert.deepEqual(await page.evaluate(() => window.inkstone.getTheme()), { preference: 'dark', resolved: 'dark' });
    await page.evaluate(() => window.inkstone.setTheme('system'));
    await page.emulateMedia({ colorScheme: 'light' });
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'light');
    await page.screenshot({ path: path.resolve(__dirname, '../../dev-runs/theme-light-split.png') });
    // Native resolution wins over WebView's media query, including manual overrides.
    await page.addInitScript(() => { window.InkstoneNative = { themeState: () => JSON.stringify({ preference: 'system', resolved: 'dark' }), ready() {} }; });
    await page.reload();
    await page.waitForFunction(() => !!window.inkstone);
    assert.deepEqual(await page.evaluate(() => window.inkstone.getTheme()), { preference: 'system', resolved: 'dark' });
    await page.evaluate(() => window.inkstone.setTheme('system', 'light'));
    assert.deepEqual(await page.evaluate(() => window.inkstone.getTheme()), { preference: 'system', resolved: 'light' });
    assert.deepEqual(errors, []);
    console.log('PASS: real browser editing, typewriter, readonly, system/light/dark themes, persistence, native resolution, theme text colors and undo preservation.');
  } finally {
    await browser.close();
    server.close();
  }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
