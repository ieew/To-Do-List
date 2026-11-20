<script lang="ts">
  import { onMount } from 'svelte';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import QuickAddModal from './components/QuickAddModal.svelte';
  import Toast from './components/Toast.svelte';
  import { errorMessage, isTauri } from './lib/ipc';
  import { store } from './lib/store.svelte';

  let ready = $state(false);

  async function initialize(): Promise<void> {
    await store.init();
    if (isTauri) {
      try {
        const current = getCurrentWindow();
        await current.show();
        await current.setFocus();
      } catch (error) {
        store.loadError = errorMessage(error);
        return;
      }
    }
    if (store.loadError) return;
    store.quickAddOpen = true;
    ready = true;
  }

  onMount(() => void initialize());

  async function closeWindow(): Promise<void> {
    if (!isTauri) return;
    await getCurrentWindow().close();
  }
</script>

<div class="quick-add-stage">
  {#if store.loadError}
    <div class="quick-add-error" role="alert">
      <p>加载任务数据失败：{store.loadError}</p>
      <button class="btn" onclick={() => void initialize()}>重试</button>
    </div>
  {:else if !ready}
    <div class="quick-add-loading" role="status">正在加载任务数据…</div>
  {:else}
    <QuickAddModal onClose={closeWindow} transparentBackdrop />
  {/if}
  <Toast />
</div>

<style>
  .quick-add-stage {
    position: relative;
    width: 100%;
    height: 100%;
    background: transparent;
  }

  .quick-add-loading,
  .quick-add-error {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    width: 100%;
    height: 100%;
    color: var(--color-text-2);
    font-size: var(--text-base);
  }

  .quick-add-error {
    color: var(--color-danger);
  }
</style>
