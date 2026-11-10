<script lang="ts">
  import { onMount } from 'svelte';
  import AiModal from './components/AiModal.svelte';
  import ContextMenu from './components/ContextMenu.svelte';
  import DetailDrawer from './components/DetailDrawer.svelte';
  import QuickAddModal from './components/QuickAddModal.svelte';
  import SettingsOverlay from './components/SettingsOverlay.svelte';
  import Sidebar from './components/Sidebar.svelte';
  import SyncBanner from './components/SyncBanner.svelte';
  import TaskView from './components/TaskView.svelte';
  import TitleBar from './components/TitleBar.svelte';
  import Toast from './components/Toast.svelte';
  import Toolbar from './components/Toolbar.svelte';
  import { store } from './lib/store.svelte';

  const isTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

  /** 单例化的按需 import，避免 await 块在父组件更新时被重置。 */
  function lazy<T>(factory: () => Promise<T>): () => Promise<T> {
    let cached: Promise<T> | null = null;
    return () => (cached ??= factory());
  }

  /* 非首屏组件按需加载：启动时不必解析浏览器预览专用的便利贴与低频视图。 */
  const loadCalendarView = lazy(() => import('./components/CalendarView.svelte'));
  const loadStatsView = lazy(() => import('./components/StatsView.svelte'));
  const loadGlobalHistory = lazy(() => import('./components/GlobalHistory.svelte'));
  const loadStickyMode = lazy(() => import('./components/StickyMode.svelte'));
  const loadFloatingSticky = lazy(() => import('./components/FloatingSticky.svelte'));

  onMount(() => {
    const demo = new URLSearchParams(window.location.search).get('demo');
    if (!demo) return;
    setTimeout(() => {
      if (demo === 'detail' || demo === 'history') {
        const firstTask = store.tasks[0];
        if (firstTask) store.openTask(firstTask.id, demo === 'history' ? 'history' : 'detail');
      }
      if (demo === 'settings') store.settingsOpen = true;
      if (demo === 'ai') store.aiOpen = true;
      if (demo === 'sticky') store.toggleStickyMode();
      if (demo === 'floating') {
        store.addFloatingSticky();
        store.addFloatingSticky();
      }
      if (demo === 'dark') store.settings.theme = 'dark';
      if (demo === 'calendar') store.view = 'calendar';
      if (demo === 'stats') store.view = 'stats';
      if (demo === 'global') store.showGlobalHistory();
    }, 520);
  });

  $effect(() => {
    document.documentElement.dataset.theme = store.theme;
    document.documentElement.dataset.density = store.density;
    // 仅缓存 UI 偏好，供下次启动的首帧占位选用正确配色；业务数据仍只存在 Rust 投影里。
    try {
      localStorage.setItem('todo.theme', store.settings.theme);
    } catch {
      /* 存储不可用时忽略 */
    }
  });

  let resizing = $state(false);
  let resizeStartX = 0;
  let resizeStartWidth = 0;

  function startResize(event: PointerEvent): void {
    resizing = true;
    resizeStartX = event.clientX;
    resizeStartWidth = store.sidebarWidth;
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    document.body.classList.add('is-resizing');
  }

  function moveResize(event: PointerEvent): void {
    if (!resizing) return;
    const next = Math.min(300, Math.max(150, resizeStartWidth + event.clientX - resizeStartX));
    store.sidebarWidth = Math.round(next);
  }

  function endResize(event: PointerEvent): void {
    if (!resizing) return;
    resizing = false;
    document.body.classList.remove('is-resizing');
    const target = event.currentTarget as HTMLElement;
    if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId);
  }

  function resetSidebarWidth(): void {
    store.sidebarWidth = 180;
  }

  function closeTopLayer(): boolean {
    if (store.contextMenu) {
      store.closeContextMenu();
      return true;
    }
    if (store.settingsOpen) {
      store.previewTheme(null);
      store.settingsOpen = false;
      return true;
    }
    if (store.aiOpen) {
      store.aiOpen = false;
      return true;
    }
    if (store.quickAddOpen) {
      store.quickAddOpen = false;
      return true;
    }
    if (store.drawerOpen) {
      store.closeDrawer();
      return true;
    }
    if (store.stickyMode) {
      store.expandFromSticky();
      return true;
    }
    return false;
  }

  function handleKeydown(event: KeyboardEvent): void {
    const mod = event.ctrlKey || event.metaKey;

    if (event.key === 'Escape') {
      if (closeTopLayer()) event.preventDefault();
      return;
    }

    if (mod && event.key.toLowerCase() === 'n') {
      event.preventDefault();
      store.quickAddOpen = true;
      return;
    }

    if (mod && event.key.toLowerCase() === 'f') {
      event.preventDefault();
      (document.getElementById('search-input') as HTMLInputElement | null)?.focus();
      return;
    }

    if (mod && event.shiftKey && event.key.toLowerCase() === 't') {
      event.preventDefault();
      void store.openStickyMainWindow();
      return;
    }

    if (mod && event.key === ',') {
      event.preventDefault();
      store.settingsOpen = true;
    }
  }
</script>

<div class="stage" class:is-tauri={isTauri}>
  <div class="app-window" class:is-collapsed={store.stickyMode && !isTauri}>
    <TitleBar />

    <div class="win-body" style="--sidebar-w:{store.sidebarWidth}px">
      <Sidebar />

      <div
        class="sidebar-resizer"
        class:is-resizing={resizing}
        title="拖动调节宽度 · 双击恢复默认"
        role="separator"
        aria-orientation="vertical"
        aria-label="侧栏宽度"
        onpointerdown={startResize}
        onpointermove={moveResize}
        onpointerup={endResize}
        onpointercancel={endResize}
        ondblclick={resetSidebarWidth}
      ></div>

      <main class="main-pane">
        <SyncBanner />
        {#if store.globalHistory}
          {#await loadGlobalHistory() then { default: GlobalHistory }}
            <GlobalHistory />
          {/await}
        {:else}
          <Toolbar />
          {#if store.view === 'calendar'}
            {#await loadCalendarView() then { default: CalendarView }}
              <CalendarView />
            {/await}
          {:else if store.view === 'stats'}
            {#await loadStatsView() then { default: StatsView }}
              <StatsView />
            {/await}
          {:else}
            <TaskView />
          {/if}
        {/if}
      </main>

      <DetailDrawer />
      <SettingsOverlay />
    </div>
  </div>

  {#if store.stickyMode && !isTauri}
    {#await loadStickyMode() then { default: StickyMode }}
      <StickyMode />
    {/await}
  {/if}

  {#if !isTauri}
    {#each store.floatingStickies as sticky, index (sticky.id)}
      {#await loadFloatingSticky() then { default: FloatingSticky }}
        <FloatingSticky {sticky} {index} />
      {/await}
    {/each}
  {/if}

  {#if isTauri && store.loading}
    <div class="boot-mask" role="status">正在加载本地 op_log 投影…</div>
  {/if}

  {#if isTauri && store.loadError}
    <div class="boot-mask boot-error" role="alert">
      <p>本地数据加载失败：{store.loadError}</p>
      <button onclick={() => store.init()}>重试</button>
    </div>
  {/if}

  <QuickAddModal />
  <AiModal />
  <ContextMenu />
  <Toast />
</div>

<svelte:window onkeydown={handleKeydown} onclick={() => store.closeContextMenu()} />

<style>
  .stage {
    height: 100%;
    padding: var(--space-6) var(--space-4);
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: auto;
  }

  .stage.is-tauri {
    padding: 0;
  }

  .app-window {
    position: relative;
    width: var(--window-w);
    height: var(--window-h);
    min-width: var(--window-min-w);
    min-height: var(--window-min-h);
    display: flex;
    flex-direction: column;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 14px;
    background: var(--color-card);
    color: var(--color-text);
    box-shadow: var(--shadow-xl);
    overflow: hidden;
    transition:
      opacity var(--duration-slow) var(--ease-standard),
      transform var(--duration-slow) var(--ease-standard),
      background var(--duration-base) var(--ease-standard);
  }

  .stage.is-tauri .app-window {
    width: 100vw;
    height: 100vh;
    min-width: 0;
    min-height: 0;
    border: none;
    border-radius: 0;
    box-shadow: none;
  }

  .app-window.is-collapsed {
    opacity: 0;
    transform: scale(0.96);
    pointer-events: none;
  }

  .win-body {
    position: relative;
    flex: 1;
    min-height: 0;
    display: flex;
    background: var(--color-card);
  }

  .main-pane {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    background: var(--color-card);
  }

  .sidebar-resizer {
    position: relative;
    width: 8px;
    flex-shrink: 0;
    margin-left: -4px;
    margin-right: -4px;
    z-index: var(--z-resizer);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: col-resize;
    touch-action: none;
  }

  .sidebar-resizer::after {
    content: '';
    width: 3px;
    height: 40px;
    border-radius: var(--radius-full);
    background: transparent;
    transition:
      background var(--duration-fast) var(--ease-out),
      height var(--duration-fast) var(--ease-out);
  }

  .sidebar-resizer:hover::after,
  .sidebar-resizer.is-resizing::after {
    height: 56px;
    background: var(--color-primary);
  }

  .boot-mask {
    position: fixed;
    inset: 0;
    z-index: var(--z-toast);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--space-3);
    background: var(--color-bg);
    color: var(--color-text-2);
    font-size: var(--text-base);
  }

  .boot-mask button {
    padding: 6px 14px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-card);
    color: var(--color-primary);
  }

  .boot-error {
    color: var(--color-danger);
  }
</style>
