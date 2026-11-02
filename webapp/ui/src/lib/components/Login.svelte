<script>
  import { ApiError, auth, session } from '../api.js';
  import logo from '../../../../../branding/memento.svg';

  let user = '';
  let password = '';
  let error = '';
  let busy = false;

  async function submit() {
    error = '';
    busy = true;
    try {
      session.set(await auth.login(user.trim(), password));
      password = '';
    } catch (e) {
      error = e instanceof ApiError ? e.message : String(e);
    } finally {
      busy = false;
    }
  }
</script>

<main class="login">
  <form class="card" on:submit|preventDefault={submit}>
    <img src={logo} alt="" width="72" height="72" />
    <h1>Mémento</h1>
    <p class="muted">Connectez-vous pour retrouver vos notes.</p>
    <label>
      <span>Identifiant</span>
      <input class="field" bind:value={user} autocomplete="username" autocapitalize="none" spellcheck="false" required />
    </label>
    <label>
      <span>Mot de passe</span>
      <input class="field" type="password" bind:value={password} autocomplete="current-password" required />
    </label>
    {#if error}<p class="error" role="alert">{error}</p>{/if}
    <button class="btn primary" type="submit" disabled={busy}>{busy ? 'Connexion…' : 'Se connecter'}</button>
  </form>
</main>

<style>
  .login {
    min-height: 100%; display: flex; align-items: center; justify-content: center; padding: 16px;
    background: radial-gradient(circle at 30% 10%, var(--surface) 0, var(--background) 60%);
  }
  .card {
    width: min(380px, 100%); background: var(--surface-high); border-radius: 28px; padding: 32px 28px 28px;
    display: flex; flex-direction: column; gap: 14px; box-shadow: var(--shadow); text-align: center;
  }
  img { align-self: center; border-radius: 18px; }
  h1 { margin: 0; font-size: 26px; font-weight: 700; color: var(--primary); }
  p { margin: 0 0 6px; }
  label { display: flex; flex-direction: column; gap: 6px; text-align: left; font-size: 13px; font-weight: 600; color: var(--on-surface-variant); }
  .error { color: var(--error); font-weight: 600; text-align: left; }
  .btn { padding: 12px; margin-top: 6px; font-size: 15px; }
</style>
