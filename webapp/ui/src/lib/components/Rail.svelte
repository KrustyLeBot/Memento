<script>
  // The module switcher: a left rail on a wide screen (like the Windows app), a bottom bar on a phone.
  import Icon from './Icon.svelte';
  import { href } from '../router.js';
  /** @type {{ key: string, label: string, icon: any }[]} */
  export let modules;
  export let current = '';
</script>

<nav class="rail" aria-label="Modules">
  {#each modules as m (m.key)}
    <a class="item" class:on={current === m.key} href={href([m.key])} aria-current={current === m.key ? 'page' : undefined}>
      <span class="pill"><Icon name={m.icon} /></span>
      <span class="label">{m.label}</span>
    </a>
  {/each}
  <span class="spacer"></span>
  <a class="item" class:on={current === 'settings'} href={href(['settings'])} aria-current={current === 'settings' ? 'page' : undefined}>
    <span class="pill"><Icon name="settings" /></span>
    <span class="label">Réglages</span>
  </a>
</nav>

<style>
  .rail {
    width: 84px; flex: none; background: var(--surface); display: flex; flex-direction: column; align-items: center;
    gap: 6px; padding: 12px 6px;
  }
  .spacer { flex: 1; }
  .item {
    display: flex; flex-direction: column; align-items: center; gap: 4px; text-decoration: none; color: var(--on-surface);
    width: 72px; padding: 4px 0; border-radius: 16px; font-size: 12px; font-weight: 500;
  }
  .pill { width: 56px; height: 32px; border-radius: 16px; display: flex; align-items: center; justify-content: center; }
  .item:hover .pill { background: var(--surface-high); }
  .item.on .pill { background: var(--primary-container); color: var(--on-primary-container); }
  .item.on .label { font-weight: 700; }
  @media (max-width: 719px) {
    .rail {
      position: fixed; left: 0; right: 0; bottom: 0; width: auto; height: calc(68px + env(safe-area-inset-bottom));
      flex-direction: row; justify-content: space-around; padding: 6px 4px env(safe-area-inset-bottom); z-index: 30;
      box-shadow: 0 -1px 0 var(--outline-variant);
    }
    .spacer { display: none; }
    .item { width: auto; flex: 1; min-width: 0; }
    .label { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; }
  }
</style>
