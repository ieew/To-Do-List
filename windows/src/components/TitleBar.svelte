<script lang="ts">
  import { Pin, Plus, Settings, Cloud, CloudOff, RefreshCw } from 'lucide-svelte';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import { store } from '../lib/store.svelte';

  const isTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

  const syncLabel = $derived(
    store.syncStatus === 'synced'
      ? '已同步'
      : store.syncStatus === 'syncing'
        ? '同步中'
        : store.syncStatus === 'offline'
          ? '离线'
          : '重试中'
  );

  async function windowAction(action: 'minimize' | 'maximize' | 'close'): Promise<void> {
    if (!isTauri) {
      store.showToast('浏览器预览中，窗口控制仅在桌面端可用', 'info');
      return;
    }
    const win = getCurrentWindow();
    if (action === 'minimize') await win.minimize();
    if (action === 'maximize') await win.toggleMaximize();
    if (action === 'close') await win.close();
  }
</script>

<header class="titlebar" data-tauri-drag-region>
  <div class="win-dots">
    <button
      class="dot dot-close"
      title="关闭"
      aria-label="关闭窗口"
      onclick={() => windowAction('close')}
    ></button>
    <button
      class="dot dot-min"
      title="最小化"
      aria-label="最小化窗口"
      onclick={() => windowAction('minimize')}
    ></button>
    <button
      class="dot dot-max"
      title="最大化"
      aria-label="最大化窗口"
      onclick={() => windowAction('maximize')}
    ></button>
  </div>

  <span class="win-title">待办清单</span>

  <div class="tb-actions">
    <button class="tb-btn" title="收起为便利贴 (Ctrl+Shift+T)" onclick={() => store.toggleStickyMode()}>
      <Pin size="13" />
      <span>便利贴</span>
    </button>
    <button class="tb-btn" title="同时打开独立便利贴窗口，最多 5 个" onclick={() => store.addFloatingSticky()}>
      <Plus size="14" />
      <span>新便利贴</span>
    </button>
    <button class="tb-btn icon-btn" title="设置 (Ctrl+,)" aria-label="打开设置" onclick={() => (store.settingsOpen = true)}>
      <Settings size="14" />
    </button>
  </div>

  <button
    class="win-sync"
    class:is-syncing={store.syncStatus === 'syncing'}
    title="点击立即同步"
    onclick={() => store.syncNow()}
  >
    {#if store.syncStatus === 'syncing'}
      <RefreshCw class="spin" size="12" />
    {:else if store.syncStatus === 'offline'}
      <CloudOff size="12" />
    {:else}
      <Cloud size="12" />
    {/if}
    <span>{syncLabel}</span>
  </button>
</header>

<style>
  .titlebar {
    height: var(--titlebar-h);
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: 0 var(--space-4);
    background: var(--color-border-2);
    border-bottom: 1px solid var(--color-border);
    user-select: none;
  }

  .win-dots {
    display: flex;
    gap: 6px;
    align-items: center;
  }

  .dot {
    width: 11px;
    height: 11px;
    padding: 0;
    border: none;
    border-radius: 50%;
    transition: filter var(--duration-fast) var(--ease-out);
  }

  .dot:hover {
    filter: brightness(0.92);
  }

  .dot-close {
    background: #fca5a5;
  }

  .dot-min {
    background: #fcd34d;
  }

  .dot-max {
    background: #86efac;
  }

  .win-title {
    margin-left: var(--space-2);
    font-size: var(--text-base);
    font-weight: var(--weight-medium);
    color: var(--color-text-2);
    white-space: nowrap;
  }

  .tb-actions {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .tb-btn {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    height: 26px;
    padding: 0 10px;
    border: 1px solid var(--color-border);
    border-radius: 6px;
    background: rgba(255, 255, 255, 0.7);
    color: var(--color-text-2);
    font-size: 11.5px;
    font-weight: var(--weight-medium);
    white-space: nowrap;
    transition:
      background var(--duration-fast) var(--ease-out),
      color var(--duration-fast) var(--ease-out),
      border-color var(--duration-fast) var(--ease-out);
  }

  .tb-btn:hover {
    background: var(--color-card);
    border-color: #bfdbfe;
    color: var(--color-primary);
  }

  .tb-btn.icon-btn {
    padding: 0 8px;
  }

  .win-sync {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    margin-left: var(--space-3);
    padding: 3px 7px;
    border: none;
    border-radius: var(--radius-full);
    background: transparent;
    color: var(--color-success);
    font-size: 11px;
    font-weight: var(--weight-medium);
    white-space: nowrap;
    transition: background var(--duration-fast) var(--ease-out);
  }

  .win-sync:hover {
    background: var(--color-success-50);
  }

  .win-sync.is-syncing {
    color: var(--color-primary);
  }

  :global(html[data-theme='dark']) .tb-btn {
    background: var(--color-card);
  }

  :global(html[data-theme='dark']) .tb-btn:hover {
    background: var(--color-bg);
    color: #60a5fa;
  }

  :global(html[data-theme='dark']) .dot-close {
    background: #7f1d1d;
  }

  :global(html[data-theme='dark']) .dot-min {
    background: #78350f;
  }

  :global(html[data-theme='dark']) .dot-max {
    background: #14532d;
  }
</style>
