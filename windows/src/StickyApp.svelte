<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import FloatingSticky from './components/FloatingSticky.svelte';
  import StickyMode from './components/StickyMode.svelte';
  import { ipc, isTauri } from './lib/ipc';
  import { store } from './lib/store.svelte';

  let ready = $state(false);
  let mode = $state<'main' | 'floating'>('main');
  let focusTaskId = $state<string | null>(null);
  let label = $state('sticky-main');

  // 便利贴窗口同样跟随主窗口选择的主题（此前从未设置 data-theme，暗色下仍是浅色）。
  $effect(() => {
    document.documentElement.dataset.theme = store.theme;
  });

  onMount(() => {
    void (async () => {
      await store.init();

      if (isTauri) {
        label = getCurrentWindow().label;
        try {
          const context = await ipc.getStickyContext(label);
          if (context) {
            mode = context.mode;
            focusTaskId = context.focusTaskId;
          }
        } catch {
          /* 上下文缺失时按主形态便利贴渲染，窗口仍要显示 */
        }
      } else {
        const params = new URLSearchParams(location.search);
        mode = params.get('mode') === 'floating' ? 'floating' : 'main';
        focusTaskId = params.get('focus');
      }

      ready = true;

      // 窗口在 Rust 侧是隐藏创建的：等首帧渲染完成再显示，并在这里完成
      // 「便利贴出现 → 主窗口隐藏」的换手，避免两窗口之间露出桌面。
      if (isTauri) {
        await tick();
        const current = getCurrentWindow();
        await current.show().catch(() => {});
        await current.setFocus().catch(() => {});
        if (mode === 'main') {
          await ipc.hideMainWindow().catch(() => {});
        }
      }
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
    border-radius: var(--radius-sticky);
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
