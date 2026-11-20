const { test } = require('node:test');
const assert = require('node:assert/strict');
const { extractOutline } = require('../app/src/main/assets/editor/markdown-utils.js');

test('ATX headings retain duplicate labels, Unicode and source order', () => {
  assert.deepEqual(extractOutline('# 中文😀\n\n## **重复**\n## 重复\n###### 六级 ###\n####### not a heading\n#not-heading'), [
    { level: 1, text: '中文😀', line: 1, index: 0 },
    { level: 2, text: '重复', line: 3, index: 1 },
    { level: 2, text: '重复', line: 4, index: 2 },
    { level: 6, text: '六级', line: 5, index: 3 }
  ]);
});
test('Setext headings track their first line and strip inline formatting', () => {
  assert.deepEqual(extractOutline('标题 *一*\r\n===\r\n\r\n[链接](https://example.org) 与 `代码` &amp; 😀\r\n第二行\r\n---'), [
    { level: 1, text: '标题 一', line: 1, index: 0 },
    { level: 2, text: '链接 与 代码 & 😀 第二行', line: 4, index: 1 }
  ]);
});
test('Backtick and tilde fences ignore headings and only matching closing fences end them', () => {
  const markdown = '# Before\n````md\n# hidden\n```\nStill hidden\n---\n````\n~~~js\n# hidden too\n```\n~~~\n## After';
  assert.deepEqual(extractOutline(markdown), [
    { level: 1, text: 'Before', line: 1, index: 0 },
    { level: 2, text: 'After', line: 12, index: 1 }
  ]);
  assert.deepEqual(extractOutline('~~~\n# unclosed\n---'), []);
});
test('Indented code, thematic breaks and empty documents do not create headings', () => {
  assert.deepEqual(extractOutline('    # code\n\t## code\n\n---\n\n***\n\n'), []);
  assert.deepEqual(extractOutline(''), []);
});
