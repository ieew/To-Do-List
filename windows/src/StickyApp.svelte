<script lang="ts">
  import { onMount } from 'svelte';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import FloatingSticky from './components/FloatingSticky.svelte';
  import StickyMode from './components/StickyMode.svelte';
  import { ipc, isTauri } from './lib/ipc';
  import { store } from './lib/store.svelte';

  let ready = $state(false);
  let mode = $state<'main' | 'floating'>('main');
  let focusTaskId = $state<string | null>(null);
  let label = $state('sticky-main');

  onMount(() => {
    void (async () => {
      await store.init();

      if (isTauri) {
        label = getCurrentWindow().label;
        const context = await ipc.getStickyContext(label);
        if (context) {
          mode = context.mode;
          focusTaskId = context.focusTaskId;
        }
      } else {
        const params = new URLSearchParams(location.search);
        mode = params.get('mode') === 'floating' ? 'floating' : 'main';
        focusTaskId = params.get('focus');
      }

      ready = true;
    })();
  });
</script>

{#if store.loadError}
  <div class="sticky-loading error" role="alert">
    <span>便利贴加载失败</span>
    <small>{store.loadError}</small>
  </div>
{:else if !ready || store.loading}
  <div class="sticky-loading" role="status">正在加载 Rust 投影…</div>
{:else if mode === 'main'}
  <StickyMode standalone />
{:else}
  <FloatingSticky
    sticky={{ id: label, focusTaskId: focusTaskId ?? undefined }}
    index={0}
    {label}
    standalone
  />
{/if}

<style>
  .sticky-loading {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 6px;
    width: 100%;
    height: 100%;
    border-radius: var(--radius-lg);
    background: var(--color-card);
    color: var(--color-text-3);
    font-size: var(--text-sm);
  }

  .sticky-loading.error {
    color: var(--color-danger);
  }

  .sticky-loading small {
    max-width: 260px;
    text-align: center;
    font-size: 10px;
    opacity: 0.8;
  }
</style>
