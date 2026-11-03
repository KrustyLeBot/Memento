import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pageQuery, photoMaxZoom, tilesStatus } from '../src/modules/settings/map.js';

test('page query: trails and credit only with Tuileries', () => {
  assert.equal(pageQuery(17, null), '&photomax=17');
  assert.equal(pageQuery(16, { source: 'direct' }), '&photomax=16');
  assert.equal(pageQuery(17, { source: 'tuileries', attribution: '' }), '&photomax=17&trails=1');
  assert.equal(pageQuery(17, { source: 'tuileries', attribution: 'Sentinel-2 cloudless & EOX' }),
    '&photomax=17&trails=1&attrib=Sentinel-2%20cloudless%20%26%20EOX');
});

test('photo max zoom: 14..19, 17 by default', () => {
  assert.equal(photoMaxZoom(undefined), 17);
  assert.equal(photoMaxZoom(null), 17);
  assert.equal(photoMaxZoom('x'), 17);
  assert.equal(photoMaxZoom(12), 14);
  assert.equal(photoMaxZoom('18'), 18);
  assert.equal(photoMaxZoom(25), 19);
});

test('tile source status line', () => {
  assert.equal(tilesStatus(null), 'Vérification…');
  assert.match(tilesStatus({ source: 'direct' }), /IGN, OpenStreetMap et AWS directement/);
  assert.equal(tilesStatus({ source: 'tuileries', url: 'https://t.example.org/', name: 'Tuileries', version: '0.1.0', max_zoom: 17 }),
    "Tuileries (https://t.example.org/) : connecté, Tuileries 0.1.0, photos archivées jusqu'au zoom 17.");
  assert.equal(tilesStatus({ source: 'tuileries', url: 'https://t.example.org/', error: 'Identifiant ou mot de passe refusé' }),
    'Tuileries (https://t.example.org/) : Identifiant ou mot de passe refusé');
});
