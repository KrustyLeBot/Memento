// Map settings of the web app: the photo's top zoom (a preference of this browser) and the tile source's status line
// (MEMENTO_TILES_* on the server, read-only here). Pure functions, tested with node --test.

export const PHOTO_ZOOM_DEFAULT = 17;
export const PHOTO_ZOOMS = [14, 15, 16, 17, 18, 19];

/** The stored preference as the map page takes it: 14..19, 17 when unset or unreadable. */
export function photoMaxZoom(/** @type {unknown} */ value) {
  const z = Math.round(Number(value));
  if (!Number.isFinite(z) || value === null || value === undefined || value === '') return PHOTO_ZOOM_DEFAULT;
  return Math.min(19, Math.max(14, z));
}

/**
 * The status line of the « Serveur de cartes » section, from GET /api/map/tiles.
 * @param {{ source: string, url?: string, name?: string, version?: string, max_zoom?: number | null, error?: string } | null} s
 */
export function tilesStatus(s) {
  if (!s) return 'Vérification…';
  if (s.source !== 'tuileries') return 'IGN, OpenStreetMap et AWS directement (MEMENTO_TILES_URL non défini sur le serveur).';
  if (s.error) return `Tuileries (${s.url}) : ${s.error}`;
  const zoom = s.max_zoom != null ? `, photos archivées jusqu'au zoom ${s.max_zoom}` : '';
  return `Tuileries (${s.url}) : connecté, ${s.name ?? 'Tuileries'} ${s.version}${zoom}.`;
}
