<script lang="ts">
  import { AlertCircle, CheckCircle2, Info } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
</script>

{#if store.toast}
  <div class="toast {store.toast.tone}" role="status" aria-live="polite">
    {#if store.toast.tone === 'error'}
      <AlertCircle size="14" />
    {:else if store.toast.tone === 'info'}
      <Info size="14" />
    {:else}
      <CheckCircle2 size="14" />
    {/if}
    <span>{store.toast.message}</span>
  </div>
{/if}

<style>
  .toast {
    position: fixed;
    left: 50%;
    bottom: 26px;
    z-index: var(--z-toast);
    display: inline-flex;
    align-items: center;
    gap: 7px;
    max-width: min(520px, calc(100vw - 40px));
    padding: 8px 16px;
    border-radius: var(--radius-full);
    background: var(--color-text);
    color: #fff;
    font-size: var(--text-base);
    font-weight: var(--weight-medium);
    box-shadow: var(--shadow-lg);
    transform: translateX(-50%);
    animation: toast-in var(--duration-base) var(--ease-standard);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .toast.error {
    background: var(--color-danger);
  }

  .toast.info {
    background: #334155;
  }

  @keyframes toast-in {
    from {
      opacity: 0;
      transform: translateX(-50%) translateY(10px);
    }
    to {
      opacity: 1;
      transform: translateX(-50%) translateY(0);
    }
  }
</style>
