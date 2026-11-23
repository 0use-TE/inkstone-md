(function () {
  'use strict';
  window.InkstoneLivePreview = {
    create: function (editor, options) {
      if (!editor.mdEditor || !editor.mdEditor.view) return { refresh: function () {}, resetHistory: function () {}, exitInline: function () { return false; } };
      const view = editor.mdEditor.view;
      // Reuse the exact Decoration classes from the pinned editor's placeholder
      // plugin, avoiding a second, incompatible copy of ProseMirror.
      let Decoration, DecorationSet;
      view.state.plugins.some(plugin => {
        const set = plugin.props.decorations && plugin.props.decorations(view.state);
        const first = set && set.find()[0];
        if (!first) return false;
        Decoration = first.constructor; DecorationSet = set.constructor;
        return true;
      });
      if (!Decoration) throw new Error('Pinned Markdown decoration API unavailable');
      const blockTypes = new Set(['table', 'codeBlock', 'htmlBlock', 'thematicBreak', 'blockQuote', 'list']);
      const styles = { strong: 'strong', emph: 'emph', strikethrough: 'strike', strike: 'strike', code: 'code', link: 'link' };
      const blockCache = new Map();
      let revision = 0;
      function decorate(state) {
        if (!options.enabled()) return DecorationSet.empty;
        const lines = [];
        state.doc.forEach((node, from) => lines.push({ node, from, text: node.textContent }));
        if (!lines.length) return DecorationSet.empty;
        const decorations = [];
        const selection = state.selection;
        function pos(source, after) {
          const line = lines[Math.max(0, Math.min(lines.length - 1, source[0] - 1))];
          return line.from + 1 + Math.max(0, Math.min(line.text.length, source[1] - 1 + (after ? 1 : 0)));
        }
        function range(node) { return { from: pos(node.sourcepos[0], false), to: pos(node.sourcepos[1], true) }; }
        function active(r) { return !options.readOnly() && selection.from <= r.to && selection.to >= r.from; }
        function inline(from, to, className) {
          if (to > from) decorations.push(Decoration.inline(from, to, { class: className }));
        }
        function widget(node, r, block) {
          const raw = block ? lines.slice(node.sourcepos[0][0] - 1, node.sourcepos[1][0]).map(line => line.text).join('\n') : state.doc.textBetween(r.from, r.to, '\n');
          let cached = blockCache.get(node.id);
          if (!cached || cached.raw !== raw) {
            cached = { raw, clean: editor.options.customHTMLSanitizer(editor.preview.renderer.render(node)), revision: ++revision };
            if (blockCache.size > 2000) blockCache.clear();
            blockCache.set(node.id, cached);
          }
          const clean = cached.clean;
          const create = function () {
            const element = document.createElement(block ? 'div' : 'span');
            element.className = block ? 'inkstone-live-block toastui-editor-contents' : 'inkstone-live-image';
            element.innerHTML = clean;
            element.querySelectorAll('img').forEach(image => {
              if (!window.InkstoneUtils.allowedImageDataUrl(image.getAttribute('src') || '')) image.removeAttribute('src');
            });
            element.addEventListener('mousedown', event => {
              if (options.readOnly()) return;
              event.preventDefault();
              const positions = editor.convertPosToMatchEditorMode(r.from, r.from);
              editor.setSelection(positions[0], positions[1]);
              editor.focus();
            });
            return element;
          };
          const at = block ? lines[node.sourcepos[0][0] - 1].from : r.from;
          decorations.push(Decoration.widget(at, create, { side: -1, key: 'live-' + node.id + '-' + cached.revision, stopEvent: () => true }));
          if (block) {
            for (let index = node.sourcepos[0][0] - 1; index < Math.min(lines.length, node.sourcepos[1][0]); index++) {
              const line = lines[index];
              decorations.push(Decoration.node(line.from, line.from + line.node.nodeSize, { class: 'inkstone-live-hidden-line' }));
            }
          } else inline(r.from, r.to, 'inkstone-hidden-syntax');
        }
        function visit(node) {
          if (!node.sourcepos) return;
          if (node.sourcepos[0][0] < 1 || node.sourcepos[0][0] > lines.length) return;
          const r = range(node);
          if (blockTypes.has(node.type) && !active(r)) { widget(node, r, true); return; }
          if (node.type === 'image' && (!active(r) || (selection.empty && selection.from === r.to))) { widget(node, r, false); return; }
          if (node.type === 'heading') {
            const line = lines[node.sourcepos[0][0] - 1];
            const level = node.level || node.headingLevel || (line.text.match(/^#{1,6}/) || ['#'])[0].length;
            decorations.push(Decoration.node(line.from, line.from + line.node.nodeSize, { class: 'inkstone-live-heading inkstone-live-heading-' + level }));
            if (!active(r)) {
              const prefix = line.text.match(/^ {0,3}#{1,6}\s+/);
              if (prefix) inline(line.from + 1, line.from + 1 + prefix[0].length, 'inkstone-hidden-syntax');
              const ending = line.text.match(/\s+#+\s*$/);
              if (prefix && ending) inline(line.from + 1 + line.text.length - ending[0].length, line.from + 1 + line.text.length, 'inkstone-hidden-syntax');
              // Setext underlines belong to the heading but are not visible prose.
              if (!prefix && node.sourcepos[1][0] > node.sourcepos[0][0]) {
                const underline = lines[node.sourcepos[1][0] - 1];
                decorations.push(Decoration.node(underline.from, underline.from + underline.node.nodeSize, { class: 'inkstone-live-hidden-line' }));
              }
            }
          }
          if (styles[node.type]) {
            let from = r.from, to = r.to;
            if (node.firstChild && node.lastChild) {
              from = range(node.firstChild).from; to = range(node.lastChild).to;
            } else if (node.type === 'code') {
              const text = state.doc.textBetween(r.from, r.to, '\n');
              const ticks = (text.match(/^`+/) || [''])[0].length;
              from += ticks; to -= ticks;
            }
            inline(from, to, 'inkstone-live-' + styles[node.type]);
            if (!active(r)) {
              inline(r.from, from, 'inkstone-hidden-syntax');
              inline(to, r.to, 'inkstone-hidden-syntax');
            }
          }
          for (let child = node.firstChild; child; child = child.next) visit(child);
        }
        const root = editor.toastMark.getRootNode();
        for (let node = root.firstChild; node; node = node.next) visit(node);
        return DecorationSet.create(state.doc, decorations);
      }
      view.setProps({ decorations: decorate });
      return {
        refresh: function () { view.setProps({ decorations: decorate }); },
        resetHistory: function () {
          blockCache.clear();
          const state = view.state;
          view.updateState(state.constructor.create({ doc: state.doc, schema: state.schema, selection: state.selection, plugins: state.plugins }));
        },
        exitInline: function (command) {
          const type = { bold: 'strong', italic: 'emph', strike: 'strikethrough', code: 'code' }[command];
          const selection = editor.getSelection();
          let node = editor.toastMark.findNodeAtPosition(selection[0]);
          while (node && node.type !== type) node = node.parent;
          if (!node || !node.sourcepos) return false;
          const end = [node.sourcepos[1][0], node.sourcepos[1][1] + 1];
          editor.setSelection(end, end);
          return true;
        }
      };
    }
  };
})();
