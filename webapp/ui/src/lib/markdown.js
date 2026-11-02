// @ts-check
// The note editor's rich text <-> the stored Markdown, with exactly the rules of the Windows client
// (windows/memento_win/ui/richtext.py) and the Android editor: `# `..`### ` headings, `- ` / `* ` bullets (written
// back as `- `), **bold** and *italic* runs without nesting. The user never sees a marker; the file never holds
// anything else. Pure functions (no DOM) so they are unit-tested with node --test.

/** @typedef {{ text: string, bold: boolean, italic: boolean }} Run */
/** @typedef {{ kind: 'p' | 'li' | 'h', level: number, runs: Run[] }} Block  level: 1..3 for 'h', else 0 */

const HEADING = /^(#{1,3}) (.*)$/;
const BULLET = /^[-*] (.*)$/;

/**
 * Splits a line into runs, like richtext.inline_runs.
 * @param {string} text
 * @returns {Run[]}
 */
export function inlineRuns(text) {
  /** @type {Run[]} */
  const runs = [];
  let buf = '';
  const flush = () => {
    if (buf) runs.push({ text: buf, bold: false, italic: false });
    buf = '';
  };
  let i = 0;
  const n = text.length;
  while (i < n) {
    if (text.startsWith('**', i)) {
      const close = text.indexOf('**', i + 3);
      if (close >= 0) {
        flush();
        runs.push({ text: text.slice(i + 2, close), bold: true, italic: false });
        i = close + 2;
        continue;
      }
    } else if (text[i] === '*' && i + 1 < n && !'* '.includes(text[i + 1])) {
      const close = text.indexOf('*', i + 2);
      if (close >= 0 && text[close - 1] !== ' ' && !text.startsWith('**', close)) {
        flush();
        runs.push({ text: text.slice(i + 1, close), bold: false, italic: true });
        i = close + 1;
        continue;
      }
    }
    buf += text[i];
    i += 1;
  }
  flush();
  return runs;
}

/**
 * @param {string} markdown
 * @returns {Block[]}
 */
export function parseBlocks(markdown) {
  if (!markdown) return [];
  return markdown.split('\n').map((line) => {
    let m = HEADING.exec(line);
    if (m) return { kind: 'h', level: m[1].length, runs: inlineRuns(m[2]) };
    m = BULLET.exec(line);
    if (m) return { kind: 'li', level: 0, runs: inlineRuns(m[1]) };
    return { kind: 'p', level: 0, runs: inlineRuns(line) };
  });
}

/**
 * Like richtext.to_markdown: neighbours with one style merged, markers around the trimmed text (spaces stay
 * outside), headings never bold, trailing empty lines dropped.
 * @param {Block[]} blocks
 * @returns {string}
 */
export function blocksToMarkdown(blocks) {
  const lines = blocks.map((block) => {
    /** @type {Run[]} */
    const merged = [];
    for (const run of block.runs) {
      const bold = run.bold && block.kind !== 'h';
      const text = run.text.replace(/\u00a0|\u2028/g, ' ');
      const last = merged[merged.length - 1];
      if (last && last.bold === bold && last.italic === run.italic) last.text += text;
      else merged.push({ text, bold, italic: run.italic });
    }
    const body = merged.map(({ text, bold, italic }) => {
      const marker = bold ? '**' : italic ? '*' : '';
      if (!marker || !text.trim()) return text;
      const lead = text.slice(0, text.length - text.trimStart().length);
      const trail = text.slice(text.trimEnd().length);
      return `${lead}${marker}${text.trim()}${marker}${trail}`;
    }).join('');
    if (block.kind === 'h') return `${'#'.repeat(block.level)} ${body}`;
    if (block.kind === 'li') return `- ${body}`;
    return body;
  });
  while (lines.length && !lines[lines.length - 1].trim()) lines.pop();
  return lines.join('\n');
}

/** @param {string} s */
export function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/**
 * The editable HTML of a Markdown text: <h1>..<h3>, <p>, <ul><li>, <b>, <i>.
 * @param {string} markdown
 */
export function markdownToHtml(markdown) {
  let html = '';
  let inList = false;
  for (const block of parseBlocks(markdown)) {
    const inner = block.runs.map((r) => {
      let t = escapeHtml(r.text);
      if (r.italic) t = `<i>${t}</i>`;
      if (r.bold) t = `<b>${t}</b>`;
      return t;
    }).join('') || '<br>';
    if (block.kind === 'li') {
      if (!inList) html += '<ul>';
      inList = true;
      html += `<li>${inner}</li>`;
      continue;
    }
    if (inList) html += '</ul>';
    inList = false;
    html += block.kind === 'h' ? `<h${block.level}>${inner}</h${block.level}>` : `<p>${inner}</p>`;
  }
  if (inList) html += '</ul>';
  return html;
}

/**
 * Reads back what the browser's contenteditable made of that HTML (it adds <div>, <br>, <strong>, <span
 * style="font-weight: bold">, nested lists…). `root` is any DOM-like node: only nodeType, nodeName, childNodes,
 * textContent and style are read, so tests can pass plain objects.
 *
 * In a block, <br> ends a line and a trailing <br> only keeps the last line visible: `<p><br></p>` is one empty
 * line, `<p>a<br></p>` is "a", `<p>a<br><br></p>` is "a" then an empty line.
 * @param {any} root
 * @returns {Block[]}
 */
export function domToBlocks(root) {
  /** @type {Block[]} */
  const blocks = [];

  /** @param {any} node @param {'p' | 'li' | 'h'} kind @param {number} level @param {boolean} isRoot */
  const container = (node, kind, level, isRoot) => {
    /** @type {Run[]} */
    let runs = [];
    let emitted = false;
    const emit = () => {
      blocks.push({ kind, level, runs });
      runs = [];
      emitted = true;
    };

    /** @param {any} n @param {boolean} bold @param {boolean} italic */
    const walk = (n, bold, italic) => {
      if (n.nodeType === 3) {
        const text = (n.textContent || '').replace(/\n/g, ' ');
        if (text) runs.push({ text, bold, italic });
        return;
      }
      if (n.nodeType !== 1) return;
      const name = String(n.nodeName).toUpperCase();
      if (name === 'BR') {
        emit();
        return;
      }
      const child = childBlock(name, kind, level);
      if (child) {
        if (runs.length) emit();
        if (child.list) {
          for (const item of n.childNodes) {
            const itemName = item.nodeType === 1 ? String(item.nodeName).toUpperCase() : '';
            if (itemName === 'UL' || itemName === 'OL') walk(item, false, false);
            else if (item.nodeType === 1 || (item.textContent || '').trim()) container(item, 'li', 0, false);
          }
        } else container(n, child.kind, child.level, false);
        emitted = true;
        return;
      }
      const style = n.style || {};
      const weight = String(style.fontWeight || '');
      const b = bold || name === 'B' || name === 'STRONG' || weight === 'bold' || Number(weight) >= 600;
      const it = italic || name === 'I' || name === 'EM' || style.fontStyle === 'italic';
      for (const c of n.childNodes) walk(c, b, it);
    };

    if (node.nodeType === 3) walk(node, false, false);
    else for (const c of node.childNodes) walk(c, false, false);
    if (runs.length || (!emitted && !isRoot)) emit();
  };

  container(root, 'p', 0, true);
  return blocks;
}

/**
 * What a nested element starts: a list, a heading, or a plain block that keeps its parent's kind (a <div> Chrome
 * puts inside a bullet is still that bullet); null for inline elements.
 * @param {string} name @param {'p' | 'li' | 'h'} kind @param {number} level
 * @returns {{ list?: boolean, kind: 'p' | 'li' | 'h', level: number } | null}
 */
function childBlock(name, kind, level) {
  if (name === 'UL' || name === 'OL') return { list: true, kind: 'li', level: 0 };
  if (name === 'LI') return { kind: 'li', level: 0 };
  const heading = /^H([1-6])$/.exec(name);
  if (heading) return { kind: 'h', level: Math.min(3, Number(heading[1])) };
  if (['P', 'DIV', 'BLOCKQUOTE', 'PRE', 'SECTION', 'ARTICLE'].includes(name)) return { kind, level };
  return null;
}

/**
 * The note's text without markers: what search and copy use (☐/☑ for checklist items, as note.plain_body).
 * @param {string} title
 * @param {{ before: string, items: { text: string, checked: boolean }[], after: string }} parts
 */
export function plainText(title, parts) {
  const strip = (md) => parseBlocks(md).map((b) => b.runs.map((r) => r.text).join('')).join('\n');
  const items = parts.items.map((i) => `${i.checked ? '☑' : '☐'} ${i.text}`).join('\n');
  return [title.trim(), strip(parts.before), items, parts.after].filter((p) => p && p.trim()).join('\n\n');
}

