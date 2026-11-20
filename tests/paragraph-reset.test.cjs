const { test } = require('node:test');
const assert = require('node:assert/strict');
const { paragraphReset } = require('../app/src/main/assets/editor/markdown-utils.js');
function apply(markdown, edit) {
  if (!edit) return markdown;
  const lines = markdown.split('\n');
  const offset = ([line, col]) => lines.slice(0, line - 1).reduce((sum, s) => sum + s.length + 1, 0) + col - 1;
  return markdown.slice(0, offset(edit.from)) + edit.text + markdown.slice(offset(edit.to));
}

test('ATX levels 1 through 6 become plain paragraphs with no added leading space', () => {
  for (let level = 1; level <= 6; level++) {
    const source = '#'.repeat(level) + ' 正文\n\n保留段落';
    const edit = paragraphReset(source, [[1, level + 2], [1, level + 2]]);
    assert.equal(apply(source, edit), '正文\n\n保留段落');
    assert.deepEqual(edit.selection, [[1, 1], [1, 1]]);
  }
});
test('Setext reset removes its underline and preserves title text', () => {
  for (const underline of ['===', '---']) {
    const source = '正文\n' + underline + '\n\n保留段落';
    const edit = paragraphReset(source, [[1, 1], [1, 1]]);
    assert.equal(apply(source, edit), '正文\n\n保留段落');
  }
});
test('Multiline Setext reset preserves every content line', () => {
  const source = '第一行\n第二行\n===\n\n后文';
  const edit = paragraphReset(source, [[2, 2], [2, 2]]);
  assert.equal(apply(source, edit), '第一行\n第二行\n\n后文');
  assert.deepEqual(edit.selection, [[2, 2], [2, 2]]);
});
test('Only headings touched by the selection change', () => {
  const source = '# A\n\n## B\n\n### C';
  const edit = paragraphReset(source, [[3, 1], [4, 1]]);
  assert.equal(apply(source, edit), '# A\n\nB\n\n### C');
});
test('Cursor on a Setext underline maps back to title text', () => {
  const source = '正文\n===\n\n后文';
  const edit = paragraphReset(source, [[2, 2], [2, 2]]);
  assert.equal(apply(source, edit), '正文\n\n后文');
  assert.deepEqual(edit.selection, [[1, 3], [1, 3]]);
});
test('Code fences and ordinary text are not modified', () => {
  for (const source of ['普通正文\n\n后文', '```md\n# 代码里的标题\n```']) {
    assert.equal(paragraphReset(source, [[1, 1], [3 <= source.split('\n').length ? 3 : 1, 1]]), null);
  }
});
test('Decorative ATX closing hashes are removed, literal hashes are kept', () => {
  const source = '## 标题 #字面值 ###';
  assert.equal(apply(source, paragraphReset(source, [[1, 4], [1, 4]])), '标题 #字面值');
});
test('A single range can reset multiple headings without touching paragraphs', () => {
  const source = '# A\n\n段落\n\nB\n---\n\n## C';
  const edit = paragraphReset(source, [[1, 1], [8, 5]]);
  assert.equal(apply(source, edit), 'A\n\n段落\n\nB\n\nC');
});
