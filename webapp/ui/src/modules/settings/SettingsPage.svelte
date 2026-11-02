<script>
  // Réglages: this browser's preferences (theme, view, modules shown), the settings synced through the folder
  // (activities/settings.json), sync conflict copies, the session. Modules add their own sections here.
  import { onDestroy, onMount } from 'svelte';
  import { ApiError, auth, conflicts as conflictApi, session, settings as api, undo } from '../../lib/api.js';
  import { onChange } from '../../lib/events.js';
  import { href } from '../../lib/router.js';
  import { prefs, setPref, showError, showSnack } from '../../lib/ui.js';
  import { MODULES } from '../index.js';
  import Icon from '../../lib/components/Icon.svelte';
  import AgendaSettings from '../agenda/AgendaSettings.svelte';
  /** @type {import('../../lib/router.js').Route} */
  export let route;
  $: void route;

  /** @type {import('../../lib/api.js').ActivitySettings | null} */
  let activity = null;
  /** @type {import('../../lib/api.js').Conflict[]} */
  let conflicts = [];
  /** @type {{ user: string, expires: number, build?: string } | null} */
  let me = null;

  async function load() {
    try {
      [activity, { conflicts }, me] = await Promise.all([api.activities(), conflictApi.list(), auth.me()]);
    } catch (error) {
      showError(error);
    }
  }
  const stop = onChange((areas) => (areas.includes('activities') || areas.length) && load());
  onMount(load);
  onDestroy(stop);

  /** @param {string} key @param {boolean} shown */
  function toggleModule(key, shown) {
    const hidden = new Set($prefs.hidden);
    if (shown) hidden.delete(key);
    else hidden.add(key);
    setPref('hidden', [...hidden]);
  }

  /** @param {{ refine_altitude: boolean, totals: string[] }} value */
  async function saveActivity(value) {
    if (!activity) return;
    try {
      activity = await api.saveActivities(activity.version, value);
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        activity = error.body.current;
        showSnack('Ces réglages venaient de changer sur un autre appareil : rechargés, réessayez.');
      } else showError(error);
    }
  }

  /** @param {import('../../lib/api.js').Conflict} c @param {'keep' | 'discard'} action */
  async function resolve(c, action) {
    try {
      const result = await conflictApi.resolve(c.path, action);
      showSnack(action === 'keep' ? 'Copie gardée à la place de l\'originale' : 'Copie supprimée',
        { action: { label: 'Annuler', run: async () => { await undo(result.undo); load(); } } });
      load();
    } catch (error) {
      showError(error);
    }
  }

  async function logout() {
    try {
      await auth.logout();
    } catch (error) {
      showError(error);
    }
    session.set(null);
  }

  const AREA = { notes: 'Notes', activities: 'Activités', agenda: 'Agenda', contacts: 'Contacts' };
  $: expires = me ? new Date(me.expires).toLocaleString('fr-FR', { dateStyle: 'long', timeStyle: 'short' }) : '';
</script>

<div class="scroll">
  <div class="page">
    <h1>Réglages</h1>

    <h2 class="section-title">Affichage (ce navigateur)</h2>
    <div class="card">
      <div class="row">
        <span>Thème</span>
        <div class="segmented" role="radiogroup" aria-label="Thème">
          {#each [['system', 'Système'], ['light', 'Clair'], ['dark', 'Sombre']] as [value, label]}
            <button role="radio" aria-checked={$prefs.theme === value} class:on={$prefs.theme === value}
              on:click={() => setPref('theme', value)}>{label}</button>
          {/each}
        </div>
      </div>
      <div class="row">
        <span>Notes</span>
        <div class="segmented" role="radiogroup" aria-label="Vue des notes">
          <button role="radio" aria-checked={$prefs.grid} class:on={$prefs.grid} on:click={() => setPref('grid', true)}>Grille</button>
          <button role="radio" aria-checked={!$prefs.grid} class:on={!$prefs.grid} on:click={() => setPref('grid', false)}>Liste</button>
        </div>
      </div>
    </div>

    <h2 class="section-title">Modules</h2>
    <div class="card">
      <p class="muted">Les modules masqués disparaissent du menu ; leurs fichiers restent dans le dossier.</p>
      {#each MODULES.filter((m) => m.hideable) as m}
        <label class="check"><input type="checkbox" checked={!$prefs.hidden.includes(m.key)}
          on:change={(e) => toggleModule(m.key, e.currentTarget.checked)} /> {m.label}</label>
      {/each}
    </div>

    {#if !$prefs.hidden.includes('agenda')}<AgendaSettings />{/if}

    {#if activity}
      <h2 class="section-title">Activités (synchronisé avec le téléphone)</h2>
      <div class="card">
        <p class="muted">Sports comptés dans les totaux</p>
        <div class="sports">
          {#each activity.sports as sport}
            <label class="check"><input type="checkbox" checked={activity.totals.includes(sport.key)}
              on:change={(e) => saveActivity({ refine_altitude: activity?.refine_altitude ?? false,
                totals: e.currentTarget.checked ? [...(activity?.totals ?? []), sport.key] : (activity?.totals ?? []).filter((k) => k !== sport.key) })} />
              {sport.label}</label>
          {/each}
        </div>
        <label class="check"><input type="checkbox" checked={activity.refine_altitude}
          on:change={(e) => saveActivity({ refine_altitude: e.currentTarget.checked, totals: activity?.totals ?? [] })} />
          Affiner l'altitude avec le relief à l'enregistrement (téléphone)</label>
      </div>
    {/if}

    <h2 class="section-title">Conflits de synchronisation</h2>
    <div class="card">
      {#if !conflicts.length}
        <p class="muted">Aucune copie de conflit dans le dossier.</p>
      {:else}
        <p class="muted">La synchronisation a gardé deux versions de ces fichiers. « Garder la copie » remplace l'original par la copie ;
          « Supprimer la copie » garde l'original.</p>
        {#each conflicts as c (c.path)}
          <div class="conflict">
            <div class="what">
              <strong>{c.path}</strong>
              <span class="muted">{AREA[/** @type {keyof typeof AREA} */ (c.area)] ?? c.area} · original : {c.original}{c.original_exists ? '' : ' (absent)'}</span>
            </div>
            {#if c.area === 'notes' && c.path.endsWith('.md')}
              <a class="btn" href={href(['notes', 'edit', c.path])}>Ouvrir</a>
            {/if}
            <button class="btn" on:click={() => resolve(c, 'keep')}>Garder la copie</button>
            <button class="btn" on:click={() => resolve(c, 'discard')}>Supprimer la copie</button>
          </div>
        {/each}
      {/if}
    </div>

    <h2 class="section-title">Compte</h2>
    <div class="card">
      {#if me}
        <p>Connecté en tant que <strong>{me.user}</strong>. La session se termine le {expires}.</p>
      {/if}
      <button class="btn tonal" on:click={logout}><span class="inline"><Icon name="logout" size={18} /> Se déconnecter</span></button>
    </div>

    <h2 class="section-title">À propos</h2>
    <div class="card">
      <p>Mémento web{me?.build ? `, build ${me.build}` : ''}. Les fichiers sont ceux du dossier synchronisé : le téléphone et le PC voient
        chaque modification.</p>
    </div>
  </div>
</div>

<style>
  .scroll { height: 100%; overflow-y: auto; }
  .page { max-width: 720px; margin: 0 auto; padding: 16px 16px 120px; }
  h1 { font-size: 24px; margin: 8px 4px 4px; }
  .card { background: var(--surface); border-radius: 20px; padding: 14px 18px; display: flex; flex-direction: column; gap: 8px; }
  .card p { margin: 0; }
  .row { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
  .segmented { display: inline-flex; border: 1px solid var(--outline); border-radius: 20px; overflow: hidden; }
  .segmented button { border: none; background: transparent; padding: 7px 16px; cursor: pointer; font-weight: 600; }
  .segmented button + button { border-left: 1px solid var(--outline); }
  .segmented button.on { background: var(--secondary-container); color: var(--on-secondary-container); }
  .check { display: flex; align-items: center; gap: 10px; padding: 4px 0; cursor: pointer; }
  .check input { width: 18px; height: 18px; accent-color: var(--primary); flex: none; }
  .sports { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 0 12px; }
  .conflict { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; border-top: 1px solid var(--outline-variant); padding-top: 8px; }
  .what { flex: 1; min-width: 200px; display: flex; flex-direction: column; overflow-wrap: anywhere; }
  .what .muted { font-size: 13px; }
  .inline { display: inline-flex; align-items: center; gap: 8px; }
  a.btn { text-decoration: none; }
  .btn.tonal { align-self: flex-start; }
</style>
