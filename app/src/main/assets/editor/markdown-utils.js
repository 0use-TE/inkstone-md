(function (root) {
  'use strict';
  const MAX_DOCUMENT_BYTES = 20 * 1024 * 1024;
  const MAX_IMAGE_BYTES = 6 * 1024 * 1024;
  function visibleLength(markdown) {
    return Array.from(markdown.replace(/!\[[^\]]*\]\(data:image\/[^\s)]+\)/gi, '🖼')).length;
  }
  function allowedImageDataUrl(value) {
    return typeof value === 'string' && /^data:image\/(?:png|jpeg|gif|webp);base64,[A-Za-z0-9+/]+={0,2}$/.test(value);
  }
  function documentFits(markdown) {
    return new TextEncoder().encode(markdown).length <= MAX_DOCUMENT_BYTES;
  }
  function headingText(text) {
    return text
      .replace(/!?(?:\[([^\]]*)\])\([^)]*\)/g, '$1')
      .replace(/!?(?:\[([^\]]*)\])\[[^\]]*\]/g, '$1')
      .replace(/<[^>]*>/g, '')
      .replace(/\\([\\`*{}\[\]()#+.!_>~-])/g, '$1')
      .replace(/[`*_~]/g, '')
      .replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos|nbsp);/gi, function (match, entity) {
        const named = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };
        if (entity[0] !== '#') return named[entity.toLowerCase()];
        const code = entity[1].toLowerCase() === 'x' ? parseInt(entity.slice(2), 16) : Number(entity.slice(1));
        return code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : match;
      })
      .replace(/\s+/g, ' ').trim();
  }
  function extractOutline(markdown) {
    const lines = String(markdown).split(/\r\n?|\n/);
    const headings = [];
    let fence = null;
    let paragraph = [];
    let paragraphLine = 0;
    function add(level, text, line) {
      headings.push({ level: level, text: headingText(text), line: line, index: headings.length });
    }
    lines.forEach(function (line, offset) {
      const marker = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(line);
      if (fence) {
        if (marker && marker[1][0] === fence.char && marker[1].length >= fence.length && !marker[2].trim()) fence = null;
        return;
      }
      if (marker && (marker[1][0] !== '`' || !marker[2].includes('`'))) {
        fence = { char: marker[1][0], length: marker[1].length };
        paragraph = [];
        return;
      }
      const atx = /^ {0,3}(#{1,6})(?:[ \t]+(.*)|[ \t]*)$/.exec(line);
      if (atx) {
        add(atx[1].length, (atx[2] || '').replace(/[ \t]+#+[ \t]*$/, ''), offset + 1);
        paragraph = [];
      } else if (paragraph.length && /^ {0,3}(?:=+|-+)[ \t]*$/.test(line)) {
        add(line.trim()[0] === '=' ? 1 : 2, paragraph.join(' '), paragraphLine);
        paragraph = [];
      } else if (!line.trim() || /^(?: {4}|\t)/.test(line) || /^ {0,3}(?:>|[-+*][ \t]|\d+[.)][ \t]|(?:\*[ \t]*){3,}$|(?:-[ \t]*){3,}$|(?:_[ \t]*){3,}$)/.test(line)) {
        paragraph = [];
      } else {
        if (!paragraph.length) paragraphLine = offset + 1;
        paragraph.push(line.trim());
      }
    });
    return headings;
  }
  // Plan a single undoable source edit. Only headings touched by the selection change.
  function paragraphReset(markdown, selection) {
    if (!Array.isArray(selection) || selection.length !== 2 || !selection.every(p => Array.isArray(p) && p.length === 2 && p.every(Number.isInteger))) return null;
    const lines = String(markdown).split(/\r\n?|\n/);
    const [start, end] = selection;
    if (start[0] < 1 || end[0] > lines.length || start[0] > end[0]) return null;
    const firstSelected = start[0] - 1;
    const lastSelected = Math.max(firstSelected, end[0] - 1 - (end[0] > start[0] && end[1] === 1 ? 1 : 0));
    const replacements = new Map();
    const prefixLengths = new Map();
    const deleted = new Set();
    let first = lines.length, last = -1;
    extractOutline(markdown).forEach(heading => {
      const from = heading.line - 1;
      const atx = /^( {0,3})#{1,6}(?:[\t ]+|$)/.exec(lines[from]);
      let to = from;
      if (!atx) {
        to++;
        while (to < lines.length && !/^ {0,3}(?:=+|-+)[\t ]*$/.test(lines[to])) to++;
        if (to === lines.length) return;
      }
      if (to < firstSelected || from > lastSelected) return;
      first = Math.min(first, from); last = Math.max(last, to);
      if (atx) {
        const text = atx[1] + lines[from].slice(atx[0].length).replace(/[\t ]+#+[\t ]*$/, '');
        replacements.set(from, text);
        prefixLengths.set(from, atx[0].length - atx[1].length);
      } else deleted.add(to);
    });
    if (last < first) return null;
    const text = lines.slice(first, last + 1).map((line, offset) => {
      const i = first + offset;
      return deleted.has(i) ? null : replacements.has(i) ? replacements.get(i) : line;
    }).filter(line => line !== null).join('\n');
    function mappedPosition(position) {
      let index = position[0] - 1;
      let column = position[1];
      if (deleted.has(index)) { index = Math.max(0, index - 1); column = (replacements.get(index) || lines[index]).length + 1; }
      const removedBefore = Array.from(deleted).filter(line => line < index).length;
      column -= prefixLengths.get(index) || 0;
      const newLine = replacements.has(index) ? replacements.get(index) : lines[index];
      return [index + 1 - removedBefore, Math.max(1, Math.min(column, newLine.length + 1))];
    }
    return { from: [first + 1, 1], to: [last + 1, lines[last].length + 1], text,
      selection: [mappedPosition(start), mappedPosition(end)] };
  }
  const api = { MAX_DOCUMENT_BYTES, MAX_IMAGE_BYTES, visibleLength, allowedImageDataUrl, documentFits, extractOutline, paragraphReset };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.InkstoneUtils = api;
})(typeof window !== 'undefined' ? window : globalThis);
