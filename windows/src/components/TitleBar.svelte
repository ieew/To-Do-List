<script lang="ts">
  import { onMount } from 'svelte';
  import {
    CheckSquare,
    Cloud,
    CloudOff,
    Copy,
    Minus,
    Pin,
    Plus,
    RefreshCw,
    Settings,
    Square,
    X
  } from 'lucide-svelte';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import { store } from '../lib/store.svelte';

  const isTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

  let maximized = $state(false);

  const syncLabel = $derived(
    store.syncStatus === 'synced'
      ? '已同步'
      : store.syncStatus === 'syncing'
        ? '同步中'
        : store.syncStatus === 'offline'
          ? '离线'
          : '重试中'
  );

  onMount(() => {
    if (!isTauri) return;
    const win = getCurrentWindow();
    let disposed = false;
    let unlisten: (() => void) | undefined;

    const refreshMaximized = async (): Promise<void> => {
      const value = await win.isMaximized();
      if (!disposed) maximized = value;
    };

    void refreshMaximized();
    void win
      .onResized(() => {
        void refreshMaximized();
      })
      .then((fn) => {
        if (disposed) fn();
        else unlisten = fn;
      });

    return () => {
      disposed = true;
      unlisten?.();
    };
  });

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

  function handleTitleDoubleClick(event: MouseEvent): void {
    if ((event.target as HTMLElement).closest('button')) return;
    void windowAction('maximize');
  }
</script>

<header class="titlebar">
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div class="tb-brand" data-tauri-drag-region ondblclick={handleTitleDoubleClick}>
    <span class="tb-logo" aria-hidden="true"><CheckSquare size="12" /></span>
    <span class="win-title">待办清单</span>
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
  </div>

  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div class="tb-drag-space" data-tauri-drag-region ondblclick={handleTitleDoubleClick}></div>

  <div class="tb-actions">
    <button
      class="tb-btn"
      title="隐藏主窗口并打开独立便利贴 (Ctrl+Shift+T)"
      onclick={() => store.openStickyMainWindow()}
    >
      <Pin size="13" />
      <span>便利贴</span>
    </button>
    <button
      class="tb-btn"
      title="同时打开独立便利贴窗口，最多 5 个"
      onclick={() => store.openFloatingStickyWindow()}
    >
      <Plus size="14" />
      <span>新便利贴</span>
    </button>
    <button class="tb-btn icon-btn" title="设置 (Ctrl+,)" aria-label="打开设置" onclick={() => (store.settingsOpen = true)}>
      <Settings size="14" />
    </button>
  </div>

  <div class="win-controls" role="group" aria-label="窗口控制">
    <button
      class="wc-btn"
      title="最小化"
      aria-label="最小化窗口"
      onclick={() => windowAction('minimize')}
    >
      <Minus size="14" strokeWidth={1.4} />
    </button>
    <button
      class="wc-btn"
      title={maximized ? '还原' : '最大化'}
      aria-label={maximized ? '还原窗口' : '最大化窗口'}
      onclick={() => windowAction('maximize')}
    >
      {#if maximized}
        <Copy size="12" strokeWidth={1.4} />
      {:else}
        <Square size="11" strokeWidth={1.4} />
      {/if}
    </button>
    <button
      class="wc-btn wc-close"
      title="关闭"
      aria-label="关闭窗口"
      onclick={() => windowAction('close')}
    >
      <X size="14" strokeWidth={1.4} />
    </button>
  </div>
</header>

<style>
  .titlebar {
    height: var(--titlebar-h);
    flex-shrink: 0;
    display: flex;
    align-items: stretch;
    gap: var(--space-2);
    padding-left: var(--space-3);
    background: var(--color-border-2);
    border-bottom: 1px solid var(--color-border);
    user-select: none;
    overflow: hidden;
  }

  .tb-brand {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    flex-shrink: 0;
    padding-right: var(--space-1);
  }

  .tb-logo {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border-radius: 5px;
    background: var(--color-primary-50);
    color: var(--color-primary);
  }

  .win-title {
    font-size: var(--text-base);
    font-weight: var(--weight-medium);
    color: var(--color-text-2);
    white-space: nowrap;
  }

  .tb-drag-space {
    flex: 1;
    min-width: var(--space-4);
  }

  .tb-actions {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-shrink: 0;
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
    margin-left: var(--space-1);
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

  /* Windows 11 风格窗口控制：贴右边缘、无圆角、与标题栏等高 */
  .win-controls {
    display: flex;
    align-items: stretch;
    flex-shrink: 0;
    margin-left: var(--space-1);
  }

  .win-controls::before {
    content: '';
    align-self: center;
    width: 1px;
    height: var(--wc-divider-h);
    margin-right: var(--space-1);
    background: var(--color-border);
  }

  .wc-btn {
    width: var(--wc-btn-w);
    height: 100%;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 0;
    border: none;
    border-radius: 0;
    background: transparent;
    color: var(--wc-icon);
    transition:
      background var(--duration-fast) var(--ease-out),
      color var(--duration-fast) var(--ease-out);
  }

  .wc-btn :global(svg) {
    width: var(--wc-icon-size);
    height: var(--wc-icon-size);
  }

  .wc-btn:hover {
    background: var(--wc-hover-bg);
    color: var(--wc-hover-fg);
  }

  .wc-btn:active {
    filter: brightness(0.95);
  }

  .wc-btn:focus-visible {
    outline: 2px solid var(--color-primary);
    outline-offset: -2px;
  }

  .wc-close:hover {
    background: var(--wc-close-hover-bg);
    color: var(--wc-close-hover-fg);
  }

  :global(html[data-theme='dark']) .tb-btn {
    background: var(--color-card);
  }

  :global(html[data-theme='dark']) .tb-btn:hover {
    background: var(--color-bg);
    color: #60a5fa;
  }
</style>
