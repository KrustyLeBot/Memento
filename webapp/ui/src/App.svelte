<script>
  // The shell: session check, login screen, rail + current module, snackbar and dialogs.
  import { onMount } from 'svelte';
  import { auth, session } from './lib/api.js';
  import { connect, disconnect, live } from './lib/events.js';
  import { route, go } from './lib/router.js';
  import { prefs, showError } from './lib/ui.js';
  import { MODULES } from './modules/index.js';
  import Rail from './lib/components/Rail.svelte';
  import Login from './lib/components/Login.svelte';
  import Snackbar from './lib/components/Snackbar.svelte';
  import DialogHost from './lib/components/DialogHost.svelte';
  import Icon from './lib/components/Icon.svelte';

  const loadSettings = () => import('./modules/settings/SettingsPage.svelte');
  /** @type {Record<string, any>} components already loaded, by module key */
  const loaded = {};
  /** @type {any} */
  let page = null;
  let wanted = '';

  /** Each module is its own chunk, fetched the first time it is shown. @param {string} key @param {() => Promise<any>} load */
  async function show(key, load) {
    wanted = key;
    if (!loaded[key]) {
      try {
        loaded[key] = (await load()).default;
      } catch (error) {
        showError(error, 'Chargement du module impossible');
        return;
      }
    }
    if (wanted === key) page = loaded[key];
  }

  onMount(async () => {
    try {
      session.set(await auth.me());
    } catch (error) {
      session.set(null);
      if (!(error && /** @type {any} */ (error).status === 401)) showError(error);
    }
  });

  $: if ($session) connect();
  else if ($session === null) disconnect();

  $: shown = MODULES.filter((m) => !m.hideable || !$prefs.hidden.includes(m.key));
  $: current = $route.module === 'settings' ? null : shown.find((m) => m.key === $route.module);
  $: if ($session && $route.module !== 'settings' && !current) go(['notes'], undefined, true);
  $: if ($session) {
    if ($route.module === 'settings') show('settings', loadSettings);
    else if (current) show(current.key, current.load);
  }
</script>

{#if $session === undefined}
  <div class="boot" aria-busy="true"></div>
{:else if $session === null}
  <Login />
{:else}
  <div class="shell">
    <Rail modules={shown} current={$route.module} />
    <main class="content">
      {#if !$live}
        <div class="offline" role="status"><Icon name="cloudOff" size={18} /> Connexion au serveur perdue, nouvelle tentative…</div>
      {/if}
      {#if page}
        <svelte:component this={page} route={$route} />
      {/if}
    </main>
  </div>
{/if}
<Snackbar />
<DialogHost />

<style>
  .boot { height: 100%; }
  .shell { display: flex; height: 100%; }
  .content { flex: 1; min-width: 0; position: relative; overflow: hidden; display: flex; flex-direction: column; }
  .offline {
    display: flex; align-items: center; gap: 8px; justify-content: center; padding: 6px 12px; font-size: 13px;
    background: var(--secondary-container); color: var(--on-secondary-container);
  }
  @media (max-width: 719px) {
    .shell { --bottom-bar: 68px; padding-bottom: calc(68px + env(safe-area-inset-bottom)); }
  }
  :global(:root) { --bottom-bar: 0px; }
  @media (max-width: 719px) { :global(:root) { --bottom-bar: 68px; } }
</style>
