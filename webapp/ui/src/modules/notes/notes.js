// @ts-check
// Small rules of the notes module, as in windows/memento_win/core/note.py and the Windows views.

/** id -> French name, in the palette's order (core note.COLORS). */
export const COLORS = {
  default: 'Par défaut', red: 'Corail', orange: 'Pêche', yellow: 'Sable', green: 'Menthe', teal: 'Sauge', blue: 'Brume',
  darkblue: 'Orage', purple: 'Lavande', pink: 'Rose', brown: 'Argile', gray: 'Graphite',
};

/** CSS background of a note card (default: the surface) or an open note (default: the page). */
export function noteBackground(/** @type {string} */ color, page = false) {
  if (color && color !== 'default' && color in COLORS) return `var(--note-${color})`;
  return page ? 'var(--background)' : 'var(--surface)';
}

export const isColored = (/** @type {string} */ color) => !!color && color !== 'default' && color in COLORS;

/** attachments/facture-edf-k3f9a2.pdf -> facture-edf.pdf */
export function attachmentName(/** @type {string} */ path) {
  const name = path.split('/').pop() || path;
  const dot = name.lastIndexOf('.');
  let stem = dot > 0 ? name.slice(0, dot) : name;
  const ext = dot > 0 ? name.slice(dot + 1) : '';
  stem = stem.replace(/-[a-z0-9]{6}$/, '');
  return ext ? `${stem}.${ext}` : stem;
}

export const isDrawing = (/** @type {string} */ path) => path.startsWith('drawings/') && path.endsWith('.svg');
export const isImage = (/** @type {string} */ path) => /\.(jpe?g|png|gif|webp|bmp)$/i.test(path);

/** Links of the text, in order, without duplicates or trailing punctuation (note.find_urls). */
export function findUrls(/** @type {string} */ text) {
  /** @type {string[]} */
  const seen = [];
  for (const m of text.matchAll(/https?:\/\/[^\s<>"]+/g)) {
    const url = m[0].replace(/[.,;:!?)]+$/, '');
    if (!seen.includes(url)) seen.push(url);
  }
  return seen;
}

/** Same accent- and case-insensitive folding as the server's search (note.fold). */
export function fold(/** @type {string} */ text) {
  return text.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
}

/** Above this, the server refuses an attachment (it is synced to every device). */
export const MAX_ATTACHMENT = 50_000_000;

/** Copies text, also over plain http on the LAN where navigator.clipboard does not exist. */
export async function copyText(/** @type {string} */ text) {
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const area = document.createElement('textarea');
  area.value = text;
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.appendChild(area);
  area.select();
  const ok = document.execCommand('copy');
  area.remove();
  if (!ok) throw new Error('Le navigateur a refusé la copie');
}
