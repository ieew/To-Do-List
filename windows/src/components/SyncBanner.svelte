<script lang="ts">
  import { AlertTriangle, ShieldAlert, X } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';

  const danger = $derived(
    store.syncNotice?.kind === 'permission' || store.syncNotice?.kind === 'auth'
  );
</script>

{#if store.syncNotice}
  <div class="sync-banner" class:danger role="status">
    {#if danger}
      <ShieldAlert size="14" class="sync-icon" />
    {:else}
      <AlertTriangle size="14" class="sync-icon" />
    {/if}
    <span class="sync-text">{store.syncNotice.message}</span>
    <span class="sync-code">{store.syncNotice.code}</span>
    <button class="sync-close" title="关闭提示" onclick={() => store.dismissSyncNotice()}>
      <X size="13" />
    </button>
  </div>
{/if}

<style>
  .sync-banner {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 7px 12px;
    border-bottom: 1px solid #fcd34d;
    background: #fef3c7;
    color: #92400e;
    font-size: var(--text-sm);
    line-height: 1.5;
  }

  .sync-banner.danger {
    border-bottom-color: #fca5a5;
    background: #fef2f2;
    color: #991b1b;
  }

  :global(.sync-icon) {
    flex-shrink: 0;
  }

  .sync-text {
    flex: 1;
    min-width: 0;
  }

  .sync-code {
    flex-shrink: 0;
    padding: 1px 6px;
    border-radius: var(--radius-full);
    background: rgba(0, 0, 0, 0.06);
    font-family: var(--font-mono);
    font-size: 10px;
    opacity: 0.75;
  }

  .sync-close {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    border: none;
    border-radius: var(--radius-sm);
    background: transparent;
    color: inherit;
    cursor: pointer;
    opacity: 0.7;
  }

  .sync-close:hover {
    background: rgba(0, 0, 0, 0.06);
    opacity: 1;
  }
</style>
