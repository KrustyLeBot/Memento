// The editor's Markdown must round-trip byte for byte, with the vectors of windows/tests/test_richtext.py and the
// shapes browsers actually produce in a contenteditable.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { blocksToMarkdown, domToBlocks, inlineRuns, markdownToHtml, parseBlocks, plainText } from '../src/lib/markdown.js';

// A minimal DOM: enough of nodeType / nodeName / childNodes / textContent / style for domToBlocks.
function parseHtml(html) {
  const root = { nodeType: 1, nodeName: 'DIV', childNodes: [], style: {} };
  const stack = [root];
  const re = /<\/?([a-zA-Z0-9]+)([^>]*)>|([^<]+)/g;
  let m;
  while ((m = re.exec(html))) {
    const top = stack[stack.length - 1];
    if (m[3] !== undefined) {
      top.childNodes.push({ nodeType: 3, nodeName: '#text', textContent: m[3].replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&') });
    } else if (m[0].startsWith('</')) {
      stack.pop();
    } else {
      const style = {};
      const s = /style="([^"]*)"/.exec(m[2]);
      if (s) for (const decl of s[1].split(';')) {
        const [k, v] = decl.split(':').map((x) => x && x.trim());
        if (k === 'font-weight') style.fontWeight = v;
        if (k === 'font-style') style.fontStyle = v;
      }
      const el = { nodeType: 1, nodeName: m[1].toUpperCase(), childNodes: [], style };
      top.childNodes.push(el);
      if (m[1].toLowerCase() !== 'br') stack.push(el);
    }
  }
  return root;
}

const roundTrip = (md) => blocksToMarkdown(domToBlocks(parseHtml(markdownToHtml(md))));

for (const md of [
  'Bonjour **gras** et *italique* fin',
  '# Titre\n\nTexte\n- a\n- b\n\nAprès',
  '## Sous\n- **x** y',
  'a\n\n\nb',
  '',
  '### Petit titre\n\n- un\n\nparagraphe <html> & co\n- deux',
  'ligne *avec* plusieurs **styles** et * étoile seule',
]) {
  test(`round trip: ${JSON.stringify(md)}`, () => {
    assert.equal(roundTrip(md), md);
    assert.equal(blocksToMarkdown(parseBlocks(md)), md);
  });
}

test('bullets typed with * come back as -', () => {
  assert.equal(roundTrip('* a\n* b'), '- a\n- b');
});

test('inline runs follow the Windows rules', () => {
  assert.deepEqual(inlineRuns('a **b** *c* d'), [
    { text: 'a ', bold: false, italic: false }, { text: 'b', bold: true, italic: false },
    { text: ' ', bold: false, italic: false }, { text: 'c', bold: false, italic: true },
    { text: ' d', bold: false, italic: false }]);
  assert.deepEqual(inlineRuns('2 * 3 * 4'), [{ text: '2 * 3 * 4', bold: false, italic: false }]);
});

test('markers are not shown to the user', () => {
  assert.equal(markdownToHtml('# Titre\n- **gras** et *ital*'), '<h1>Titre</h1><ul><li><b>gras</b> et <i>ital</i></li></ul>');
});

test('what Chrome makes of typing is read back', () => {
  const cases = [
    ['premier<div>second</div><div><br></div><div>quatrième</div>', 'premier\nsecond\n\nquatrième'],
    ['<p>a<br></p><p><br></p><p>b</p>', 'a\n\nb'],
    ['<p>a<br>b</p>', 'a\nb'],
    ['<p>x <strong>gras</strong> <span style="font-weight: 700;">aussi</span> <em>pench</em></p>', 'x **gras** **aussi** *pench*'],
    ['<p>espace <b>gras </b>suite</p>', 'espace **gras** suite'],
    ['<ul><li>un</li><li><div>deux</div></li></ul><p>fin</p>', '- un\n- deux\nfin'],
    ['<h1><b>Titre</b></h1>', '# Titre'],
    ['<h4>trop petit</h4>', '### trop petit'],
    ['<p>non\u00a0sécable</p>', 'non sécable'],
    ['<br>', ''],
  ];
  for (const [html, md] of cases) assert.equal(blocksToMarkdown(domToBlocks(parseHtml(html))), md, html);
});

test('plain text for the clipboard', () => {
  assert.equal(plainText('Courses', { before: '**Samedi**', items: [{ text: 'pain', checked: false }, { text: 'lait', checked: true }], after: '' }),
    'Courses\n\nSamedi\n\n☐ pain\n☑ lait');
});
