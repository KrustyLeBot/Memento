// The contacts module's own rules: texts as the Windows view, links for a phone, the editor's form.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { age, birthdayText, fromForm, initials, isEmpty, mapsHref, previewLines, reportLines, smsHref, telHref, toForm }
  from '../src/modules/contacts/contacts.js';

test('birthday text and age', () => {
  assert.equal(birthdayText({ day: 1, month: 5, year: 1990 }), '1er mai 1990');
  assert.equal(birthdayText({ day: 14, month: 12, year: null }), '14 décembre');
  assert.equal(age({ day: 14, month: 5, year: 1990 }, new Date(2026, 4, 13)), 35);
  assert.equal(age({ day: 14, month: 5, year: 1990 }, new Date(2026, 4, 14)), 36);
  assert.equal(age({ day: 29, month: 2, year: 2000 }, new Date(2027, 1, 28)), 27);
  assert.equal(age({ day: 29, month: 2, year: null }), null);
});

test('links a phone browser understands', () => {
  assert.equal(telHref('+33 (0)6 12-34.56 78'), 'tel:+330612345678');
  assert.equal(telHref('06 12 34 56 78'), 'tel:0612345678');
  assert.equal(smsHref('06 12 34 56 78'), 'sms:0612345678');
  assert.equal(mapsHref('12 rue de la Paix, Paris', 'Mozilla/5.0 (Linux; Android 14)'), 'geo:0,0?q=12%20rue%20de%20la%20Paix%2C%20Paris');
  assert.match(mapsHref('x', 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0)'), /^https:\/\/maps\.apple\.com\/\?q=x$/);
  assert.match(mapsHref('x', 'Mozilla/5.0 (X11; Linux x86_64)'), /^https:\/\/www\.openstreetmap\.org\/search\?query=x$/);
});

test('initials', () => {
  assert.equal(initials('Marie Dupont'), 'MD');
  assert.equal(initials('Jean-François O’Neill'), 'JF');
  assert.equal(initials('élodie'), 'É');
  assert.equal(initials('+33 6 12 34 56 78'), '#');
});

test('import preview and report read as on Windows', () => {
  assert.deepEqual(previewLines({ token: 't', name: 'a.vcf', cards: 3, duplicates: 2, blank: 1, bad: 0, empty: 1,
    ignored: { URL: 1, PHOTO: 3 }, policies: [] }), [
    '2 ressemblent à des contacts déjà présents', '1 fiche sans nom ni numéro', '1 fiche vide (ignorée)',
    'Non repris : PHOTO (3), URL (1)']);
  assert.deepEqual(reportLines({ created: 1, updated: 2, unchanged: 0, skipped: 3, bad: 0, empty: 0, ignored: {} }),
    ['1 contact créé', '2 contacts mis à jour', '3 doublons non importés']);
});

test('form round trip and validation', () => {
  const contact = { ref: 'a', id: 'a', path: 'contacts/a.md', name: 'Zoé', first_name: 'Zoé', last_name: '',
    phones: [{ number: '06', label: null }], emails: [], address: null, birthday: { day: 29, month: 2, year: null },
    notes: 'x\n', favorite: true, conflict: false, created: 0, updated: 0 };
  const form = toForm(contact);
  assert.deepEqual(form.emails, ['']);
  assert.equal(form.month, '2');
  assert.deepEqual(fromForm(form), { first_name: 'Zoé', last_name: '', phones: [{ number: '06', label: null }], emails: [],
    address: null, birthday: { day: 29, month: 2, year: null }, notes: 'x', favorite: true });
  assert.throws(() => fromForm({ ...form, month: '' }), /Jour et mois/);
  assert.throws(() => fromForm({ ...form, year: '19x0' }), /Année invalide/);
  assert.ok(isEmpty(fromForm(toForm(null))));
  assert.deepEqual(toForm(null).phones, [{ number: '', label: 'mobile' }]);
});
