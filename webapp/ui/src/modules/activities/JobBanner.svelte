<script>
  // Progress of the module's background job (import, altitude completion).
  import { job } from './jobs.js';
</script>

{#if $job}
  <div class="banner" role="status">
    <div class="text">{$job.text || (($job.kind === 'import' ? 'Import' : 'Altitude') + '…')}</div>
    <div class="track"><div class="fill" style="width: {Math.round((100 * $job.done) / Math.max($job.total, 1))}%"></div></div>
  </div>
{/if}

<style>
  .banner {
    background: var(--secondary-container); color: var(--on-secondary-container); border-radius: 14px; padding: 10px 14px;
    margin-bottom: 12px; display: flex; flex-direction: column; gap: 6px; font-size: 14px;
  }
  .track { height: 4px; border-radius: 2px; background: rgba(128, 128, 128, 0.3); overflow: hidden; }
  .fill { height: 100%; background: var(--primary); transition: width 0.3s; }
</style>
