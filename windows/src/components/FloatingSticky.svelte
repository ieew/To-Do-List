<script lang="ts">
  import { onMount } from 'svelte';
  import { Maximize2, Pin, Plus, X } from 'lucide-svelte';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import { store } from '../lib/store.svelte';
  import type { FloatingStickyState } from '../lib/types';
  import { formatDueShort } from '../lib/utils';

  interface Props {
    sticky: FloatingStickyState;
    index: number;
  }

  let { sticky, index }: Props = $props();

  const CARD_W = 304;
  const CARD_H = 368;
  const isTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

  let pinned = $state(true);
  let dragging = $state(false);
  let x = $state(40);
  let y = $state(40);
  let startX = 0;
  let startY = 0;
  let originX = 0;
  let originY = 0;

  onMount(() => {
    x = Math.max(12, window.innerWidth - CARD_W - 24 - index * 24);
    y = Math.max(12, window.innerHeight - CARD_H - 24 - index * 24);
  });

  const title = $derived(
    sticky.focusTaskId ? store.taskById(sticky.focusTaskId)?.title ?? '今天 · 便利贴' : '今天 · 便利贴'
  );

  const tasks = $derived.by(() => {
    if (sticky.focusTaskId) {
      const focus = store.taskById(sticky.focusTaskId);
      const rest = store.todayTasks.filter((task) => task.id !== sticky.focusTaskId).slice(0, 4);
      return focus ? [focus, ...rest] : rest;
    }
    return store.todayTasks.slice(0, 5);
  });

  function startDrag(event: PointerEvent): void {
    if ((event.target as HTMLElement).closest('button, select')) return;
    dragging = true;
    startX = event.clientX;
    startY = event.clientY;
    originX = x;
    originY = y;
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }

  function moveDrag(event: PointerEvent): void {
    if (!dragging) return;
    x = Math.min(window.innerWidth - CARD_W - 8, Math.max(8, originX + event.clientX - startX));
    y = Math.min(window.innerHeight - CARD_H - 8, Math.max(8, originY + event.clientY - startY));
  }

  function endDrag(event: PointerEvent): void {
    dragging = false;
    const target = event.currentTarget as HTMLElement;
    if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId);
  }

  async function togglePin(): Promise<void> {
    pinned = !pinned;
    if (isTauri) await getCurrentWindow().setAlwaysOnTop(pinned);
    store.showToast(pinned ? '便利贴已置顶' : '已取消置顶', 'info');
  }

  function expand(): void {
    store.expandFromSticky();
  }
</script>

<div
  class="floating-sticky"
  class:dragging
  style="left:{x}px;top:{y}px;z-index:{1000 + index}"
  role="dialog"
  aria-label="浮动便利贴"
>
  <div
    class="floating-header"
    role="toolbar"
    tabindex="0"
    aria-label="拖动便利贴"
    onpointerdown={startDrag}
    onpointermove={moveDrag}
    onpointerup={endDrag}
    onpointercancel={endDrag}
  >
    <div class="sticky-handle" aria-hidden="true"><span></span><span></span><span></span></div>
    <span class="floating-title" title={title}>{title}</span>
    <button class="floating-btn" class:pinned onclick={togglePin} aria-label="置顶" title="置顶">
      <Pin size="12" />
    </button>
    <button class="floating-btn" onclick={expand} aria-label="展开大界面" title="展开大界面">
      <Maximize2 size="12" />
    </button>
    <button class="floating-btn" onclick={() => store.closeFloatingSticky(sticky.id)} aria-label="关闭便利贴" title="关闭">
      <X size="13" />
    </button>
  </div>

  <div class="floating-body">
    {#if tasks.length === 0}
      <div class="floating-empty">今天清空啦，休息一下</div>
    {:else}
      {#each tasks as task (task.id)}
        <div class="floating-task" class:is-done={task.status === 'done'} class:is-focus={task.id === sticky.focusTaskId}>
          <button
            class="mini-check"
            class:is-checked={task.status === 'done'}
            role="checkbox"
            aria-checked={task.status === 'done'}
            aria-label={task.title}
            onclick={() => store.toggleTask(task.id)}
          ></button>
          <span class="floating-task-title">{task.title}</span>
          {#if task.dueAt}
            <span class="floating-task-meta">{formatDueShort(task.dueAt)}</span>
          {/if}
        </div>
      {/each}
    {/if}
  </div>

  <div class="floating-footer">
    <label class="floating-list">
      <select
        value={store.activeListId}
        onchange={(event) => store.setActiveList((event.currentTarget as HTMLSelectElement).value)}
        aria-label="切换清单"
      >
        {#each store.lists as list (list.id)}
          <option value={list.id}>{list.name}</option>
        {/each}
      </select>
      <span class="caret">▾</span>
    </label>
    <div class="footer-actions">
      <button class="footer-icon" aria-label="添加任务" title="添加任务" onclick={() => (store.quickAddOpen = true)}>
        <Plus size="12" />
      </button>
      <button class="footer-expand" onclick={expand}>
        展开
        <Maximize2 size="10" />
      </button>
    </div>
  </div>
</div>

<style>
  .floating-sticky {
    position: fixed;
    width: 304px;
    height: 368px;
    display: flex;
    flex-direction: column;
    border-radius: var(--radius-lg);
    background: rgba(255, 255, 255, 0.94);
    box-shadow: var(--shadow-xl), var(--sticky-inset);
    backdrop-filter: blur(22px) saturate(170%);
    overflow: hidden;
    color: var(--color-text);
    animation: floating-in var(--duration-base) var(--ease-standard);
  }

  .floating-sticky.dragging {
    cursor: grabbing;
    user-select: none;
    box-shadow: var(--shadow-xl), var(--sticky-inset), 0 0 0 1px var(--color-primary);
  }

  @supports not (backdrop-filter: blur(1px)) {
    .floating-sticky {
      background: #fff;
    }
  }

  .floating-header {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 11px;
    border-bottom: 1px solid rgba(226, 232, 240, 0.75);
    cursor: grab;
    user-select: none;
    touch-action: none;
  }

  .sticky-handle {
    display: flex;
    flex-direction: column;
    gap: 2.5px;
    padding: 2px 0;
    opacity: 0.5;
  }

  .sticky-handle span {
    width: 11px;
    height: 1.5px;
    border-radius: 1px;
    background: var(--color-text-2);
  }

  .floating-title {
    flex: 1;
    min-width: 0;
    font-size: 11.5px;
    font-weight: var(--weight-semibold);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .floating-btn {
    width: 22px;
    height: 22px;
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: none;
    border-radius: 5px;
    background: transparent;
    color: var(--color-text-2);
  }

  .floating-btn:hover {
    background: rgba(15, 23, 42, 0.06);
  }

  .floating-btn.pinned {
    background: var(--color-primary-50);
    color: var(--color-primary);
  }

  .floating-body {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
    overflow-y: auto;
    padding: var(--space-2);
  }

  .floating-task {
    display: flex;
    align-items: center;
    gap: 7px;
    padding: 7px;
    border-radius: 6px;
    font-size: 11.5px;
    color: var(--color-text);
  }

  .floating-task:hover {
    background: rgba(241, 245, 249, 0.9);
  }

  .floating-task.is-focus {
    background: var(--color-primary-50);
  }

  .floating-task .mini-check {
    width: 15px;
    height: 15px;
  }

  .floating-task-title {
    flex: 1;
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .floating-task.is-done .floating-task-title {
    color: var(--color-text-3);
    text-decoration: line-through;
  }

  .floating-task-meta {
    flex-shrink: 0;
    font-size: 9.5px;
    color: var(--color-text-3);
  }

  .floating-empty {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
    color: var(--color-text-3);
    font-size: 11.5px;
    text-align: center;
  }

  .floating-footer {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
    padding: 7px 11px;
    border-top: 1px solid rgba(226, 232, 240, 0.75);
    background: rgba(248, 250, 252, 0.68);
  }

  .floating-list {
    display: inline-flex;
    align-items: center;
    gap: 2px;
    color: var(--color-text-2);
  }

  .floating-list select {
    max-width: 76px;
    border: none;
    outline: none;
    background: transparent;
    color: var(--color-text-2);
    font-size: 11px;
    font-weight: var(--weight-medium);
    appearance: none;
    cursor: pointer;
  }

  .caret {
    font-size: 9px;
    color: var(--color-text-3);
  }

  .footer-actions {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .footer-icon {
    width: 20px;
    height: 20px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--color-border);
    border-radius: 5px;
    background: rgba(255, 255, 255, 0.7);
    color: var(--color-text-2);
  }

  .footer-icon:hover {
    border-color: #bfdbfe;
    color: var(--color-primary);
  }

  .footer-expand {
    display: inline-flex;
    align-items: center;
    gap: 3px;
    border: none;
    background: transparent;
    color: var(--color-primary);
    font-size: 11px;
    font-weight: var(--weight-semibold);
  }

  .footer-expand:hover {
    text-decoration: underline;
  }

  @keyframes floating-in {
    from {
      opacity: 0;
      transform: translateY(10px) scale(0.97);
    }
    to {
      opacity: 1;
      transform: translateY(0) scale(1);
    }
  }

  :global(html[data-theme='dark']) .floating-sticky {
    background: rgba(30, 41, 59, 0.95);
    box-shadow: var(--shadow-xl), 0 0 0 1px rgba(255, 255, 255, 0.08) inset;
  }

  :global(html[data-theme='dark']) .floating-header,
  :global(html[data-theme='dark']) .floating-footer {
    border-color: rgba(51, 65, 85, 0.8);
  }

  :global(html[data-theme='dark']) .floating-footer {
    background: rgba(15, 23, 42, 0.6);
  }

  :global(html[data-theme='dark']) .floating-task:hover {
    background: rgba(15, 23, 42, 0.55);
  }
</style>
