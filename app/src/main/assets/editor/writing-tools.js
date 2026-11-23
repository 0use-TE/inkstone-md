(function () {
  'use strict';
  // Adapter for the pinned Toast UI 3.2.2 ProseMirror editors. Transactions keep
  // replacements undoable without round-tripping the whole Markdown document.
  window.InkstoneWritingTools = {
    create: function (editor, callbacks) {
      let readOnly = false;
      let focus = false;
      let typewriter = false;
      let composing = false;
      let updateTimer;
      let followPending = false;
      let dimmed = [];
      try {
        const options = JSON.parse(localStorage.getItem('inkstone-writing-options') || '{}');
        focus = options.focus === true;
        typewriter = options.typewriter === true;
      } catch (_) { /* Writing preferences are optional. */ }
      function view() {
        return typeof editor.getCurrentModeEditor === 'function' ? editor.getCurrentModeEditor().view : null;
      }
      function state() { return { readOnly, focus, typewriter }; }
      function publish() { callbacks.stateChanged(); }
      function update(follow) {
        clearTimeout(updateTimer);
        dimmed.forEach(node => node.classList.remove('inkstone-dimmed'));
        dimmed = [];
        const current = view();
        if (!current || (!focus && !typewriter)) return;
        if (focus) {
          let block = current.domAtPos(current.state.selection.head).node;
          if (block.nodeType === 3) block = block.parentElement;
          while (block && block.parentElement !== current.dom) block = block.parentElement;
          if (block) Array.from(current.dom.children).forEach(node => {
            if (node !== block) { node.classList.add('inkstone-dimmed'); dimmed.push(node); }
          });
        }
        if (!follow || !typewriter || readOnly || composing) return;
        let scroller = current.dom;
        while (scroller && !/auto|scroll/.test(window.getComputedStyle(scroller).overflowY)) scroller = scroller.parentElement;
        if (!scroller) return;
        const rect = scroller.getBoundingClientRect();
        const caret = current.coordsAtPos(current.state.selection.head);
        const viewport = window.visualViewport;
        const top = Math.max(rect.top, viewport ? viewport.offsetTop : 0);
        const bottom = Math.min(rect.bottom, viewport ? viewport.offsetTop + viewport.height : window.innerHeight);
        if (bottom <= top) return;
        const delta = (caret.top + caret.bottom) / 2 - (top + (bottom - top) * 0.45);
        if (Math.abs(delta) > 3) scroller.scrollTop += delta;
      }
      function schedule(follow) {
        if (!focus && !typewriter) return;
        followPending = followPending || follow;
        clearTimeout(updateTimer);
        updateTimer = setTimeout(() => {
          const shouldFollow = followPending;
          followPending = false;
          try { update(shouldFollow); } catch (_) { /* Transient composition/selection. */ }
        }, 40);
      }
      function apply() {
        const root = document.getElementById('editor');
        root.classList.toggle('reading-only', readOnly);
        root.classList.toggle('typewriter', typewriter && !readOnly);
        const current = view();
        if (current) {
          current.setProps({ editable: () => !readOnly });
          if (readOnly) current.dom.blur();
        }
        update(false);
        publish();
      }
      function setOption(name, value) {
        if (name === 'readOnly') readOnly = !!value;
        else if (name === 'focus') focus = !!value;
        else if (name === 'typewriter') typewriter = !!value;
        else return false;
        try { localStorage.setItem('inkstone-writing-options', JSON.stringify({ focus, typewriter })); } catch (_) { }
        apply();
        if (name === 'typewriter' && typewriter) schedule(true);
        return true;
      }
      function matches(query) {
        const found = [];
        const current = view();
        if (!current || !query || query.length > 10000) return found;
        current.state.doc.descendants((node, pos) => {
          if (found.truncated) return false;
          if (!node.isTextblock) return true;
          const text = node.textBetween(0, node.content.size, '', '\uFFFC');
          let at = 0;
          while ((at = text.indexOf(query, at)) >= 0) {
            if (found.length >= 10000) { found.truncated = true; break; }
            found.push({ from: pos + 1 + at, to: pos + 1 + at + query.length });
            at += query.length;
          }
          return false;
        });
        return found;
      }
      function select(range) {
        const positions = editor.convertPosToMatchEditorMode(range.from, range.to);
        editor.setSelection(positions[0], positions[1]);
      }
      function find(query, backwards) {
        const found = matches(query);
        if (found.truncated) return { error: '匹配超过 10000 处，请使用更具体的查找文字' };
        if (!found.length) return { count: 0, index: 0 };
        const selection = view().state.selection;
        let index = found.findIndex(range => range.from >= selection.to);
        if (backwards) {
          index = -1;
          for (let i = found.length - 1; i >= 0; i--) {
            if (found[i].to <= selection.from) { index = i; break; }
          }
        }
        if (index < 0) index = backwards ? found.length - 1 : 0;
        select(found[index]);
        return { count: found.length, index: index + 1 };
      }
      function replace(query, replacement, all) {
        if (readOnly) return { replaced: 0, error: '请先关闭只读模式' };
        const found = matches(query);
        if (found.truncated) return { replaced: 0, error: '匹配超过 10000 处，请使用更具体的查找文字' };
        if (!found.length) return { replaced: 0 };
        const current = view();
        const selection = current.state.selection;
        let chosen = found;
        if (!all) {
          chosen = [found.find(range => range.from === selection.from && range.to === selection.to)
            || found.find(range => range.from >= selection.from) || found[0]];
        }
        // Conservatively reserve the inserted bytes before mutating the document.
        const size = new TextEncoder().encode(editor.getMarkdown()).length
          + new TextEncoder().encode(replacement).length * chosen.length;
        if (size > window.InkstoneUtils.MAX_DOCUMENT_BYTES) return { replaced: 0, error: '替换后文档可能超过 20 MiB' };
        // The pinned bundle has one prosemirror-history closeHistory key.
        // Keep a menu replacement separate from typing before and after it.
        let transaction = current.state.tr.setMeta('closeHistory$', true);
        for (let i = chosen.length - 1; i >= 0; i--) {
          transaction = transaction.insertText(replacement, chosen[i].from, chosen[i].to);
        }
        current.dispatch(transaction.scrollIntoView());
        current.dispatch(current.state.tr.setMeta('closeHistory$', true));
        callbacks.changed();
        return { replaced: chosen.length };
      }
      document.addEventListener('compositionstart', () => { composing = true; }, true);
      document.addEventListener('compositionend', () => { composing = false; schedule(true); }, true);
      document.addEventListener('keydown', event => {
        if (readOnly && event.target.closest && event.target.closest('#editor .ProseMirror')) {
          const navigation = /^(Arrow|Page)/.test(event.key) || ['Home', 'End', 'Escape', 'Shift', 'Control', 'Alt', 'Meta'].includes(event.key);
          const safeCommand = (event.ctrlKey || event.metaKey) && !event.altKey && ['c', 'a', 'f', 's', '/'].includes(event.key.toLowerCase());
          if (!navigation && !safeCommand) { event.preventDefault(); event.stopImmediatePropagation(); return; }
        }
        if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) schedule(true);
      }, true);
      ['beforeinput', 'cut', 'drop'].forEach(name => document.addEventListener(name, event => {
        if (readOnly && event.target.closest && event.target.closest('#editor')) {
          event.preventDefault(); event.stopImmediatePropagation();
        }
      }, true));
      if (window.visualViewport) window.visualViewport.addEventListener('resize', () => schedule(true));
      ['mousedown', 'click'].forEach(name => document.addEventListener(name, event => {
        // Task-list node views handle mouse events even when text is not editable.
        if (readOnly && event.target.closest && event.target.closest('#editor [data-task], #editor input[type="checkbox"]')) {
          event.stopImmediatePropagation();
          if (event.target.matches('input[type="checkbox"]')) event.preventDefault();
        }
      }, true));
      return {
        state, apply, setOption, find, replace,
        editable: () => !readOnly,
        changed: () => schedule(true),
        caretChanged: () => schedule(false),
        resetDocument: function () { clearTimeout(updateTimer); followPending = false; readOnly = false; apply(); }
      };
    }
  };
})();
