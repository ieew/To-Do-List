<script lang="ts">
  import { Calendar, ChevronDown, Coffee, FileText, Folder, Maximize2, Pin, Plus, X } from 'lucide-svelte';
  import { getCurrentWindow } from '@tauri-apps/api/window';
  import { store } from '../lib/store.svelte';
  import { stickyWindowDrag } from '../lib/sticky-drag';
  import { defaultDueAt, formatDueShort, isOverdue, isUrgent } from '../lib/utils';

  const isTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;
  let { standalone = false }: { standalone?: boolean } = $props();
  let pinned = $state(true);
  let newTask = $state('');
  let addInput: HTMLInputElement | null = null;

  const tasks = $derived(store.stickyTasks);
  const done = $derived(tasks.filter((task) => task.status === 'done').length);
  const pct = $derived(tasks.length === 0 ? 0 : Math.round((done / tasks.length) * 100));
  const remaining = $derived(tasks.length - done);
  const isTodayScope = $derived(store.activeListId === store.todayListId);
  const scopeLabel = $derived(isTodayScope ? '今日进度' : '清单进度');
  const emptyText = $derived(isTodayScope ? '今天清空啦，休息一下' : '这个清单还没有任务');
  const allDoneText = $derived(isTodayScope ? '今天清空啦，休息一下' : '这个清单已清空');

  async function togglePin(): Promise<void> {
    pinned = !pinned;
    if (isTauri) {
      await getCurrentWindow().setAlwaysOnTop(pinned);
    }
    store.showToast(pinned ? '便利贴已置顶' : '已取消置顶', 'info');
  }

  function expand(): void {
    void store.showMainWindow();
  }

  function addTask(): void {
    if (!newTask.trim()) return;
    const fallback =
      store.lists.find((list) => !list.smart)?.id ?? store.lists[0]?.id ?? store.todayListId;
    const listId = store.activeListId === store.todayListId ? fallback : store.activeListId;
    store.createTask({
      title: newTask,
      listId,
      dueAt: defaultDueAt(),
      source: 'human'
    });
    newTask = '';
    store.showToast('已添加到今天');
    addInput?.focus();
  }
</script>

<div class="sticky-stage" class:standalone>
  <div class="desktop-icons" aria-hidden="true">
    <span class="fake-icon blue"><Folder size="22" /></span>
    <span class="fake-icon green"><Calendar size="22" /></span>
    <span class="fake-icon purple"><FileText size="22" /></span>
  </div>

  <div
    class="sticky-note"
    style="--sticky-alpha:{store.stickyAlpha}"
    role="dialog"
    aria-modal="true"
    aria-label="今天便利贴"
  >
    <div class="sticky-header" use:stickyWindowDrag={standalone}>
      <div class="sticky-handle" aria-hidden="true"><span></span><span></span><span></span></div>
      <span class="sticky-title">{store.listName(store.activeListId)} · 便利贴</span>
      <button class="sticky-btn" class:pinned onclick={togglePin} aria-label="置顶" title="置顶">
        <Pin size="13" />
      </button>
      <button class="sticky-btn" aria-label="展开大界面" title="展开大界面 (Esc)" onclick={expand}>
        <Maximize2 size="13" />
      </button>
      <button class="sticky-btn" aria-label="关闭便利贴" title="关闭" onclick={() => store.showMainWindow()}>
        <X size="14" />
      </button>
    </div>

    <div class="sticky-progress">
      <div class="sticky-progress-row">
        <span>{scopeLabel}</span>
        <strong>{done} / {tasks.length} 完成 · {pct}%</strong>
      </div>
      <div class="progress-bar">
        <div class="progress-fill" style="width:{pct}%"></div>
      </div>
    </div>

    <div class="sticky-add">
      <Plus size="13" />
      <input
        class="sticky-add-input"
        placeholder="添加待办…"
        bind:value={newTask}
        bind:this={addInput}
        onkeydown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            addTask();
          }
        }}
      />
    </div>

    <div class="sticky-tasks">
      {#if tasks.length === 0}
        <div class="sticky-empty">
          <Coffee size="24" />
          <span>{emptyText}</span>
        </div>
      {:else}
        {#if remaining === 0}
          <div class="sticky-clear">
            <Coffee size="14" />
            {allDoneText}
          </div>
        {/if}
        {#each tasks as task (task.id)}
          <div class="sticky-task" class:is-done={task.status === 'done'}>
            <button
              class="checkbox"
              class:is-checked={task.status === 'done'}
              role="checkbox"
              aria-checked={task.status === 'done'}
              aria-label={task.title}
              onclick={() => store.toggleTask(task.id)}
            ></button>
            <span class="sticky-task-title">{task.title}</span>
            {#if task.dueAt}
              <span class="sticky-task-meta" class:urgent={isUrgent(task) || isOverdue(task)}>
                {formatDueShort(task.dueAt)}
              </span>
            {/if}
          </div>
        {/each}
      {/if}
    </div>

    <div class="sticky-footer">
      <label class="sticky-list-switch">
        <span class="list-dot" style="background:{store.listById(store.activeListId)?.color ?? '#3B82F6'}"></span>
        <select
          value={store.activeListId}
          onchange={(event) => store.setActiveList((event.currentTarget as HTMLSelectElement).value)}
          aria-label="切换清单"
        >
          {#each store.lists as list (list.id)}
            <option value={list.id}>{list.name}</option>
          {/each}
        </select>
        <ChevronDown size="11" />
      </label>
      <button class="expand-btn" onclick={expand}>
        展开大界面
        <Maximize2 size="11" />
      </button>
    </div>
  </div>
</div>

<style>
  .sticky-stage {
    position: fixed;
    inset: 0;
    z-index: 500;
    display: flex;
    align-items: center;
    justify-content: center;
    animation: stage-in var(--duration-slow) var(--ease-standard);
  }

  .sticky-stage.standalone {
    position: fixed;
    inset: 0;
    z-index: auto;
    background: transparent;
    animation: none;
  }

  .sticky-stage.standalone .desktop-icons {
    display: none;
  }

  .sticky-stage.standalone .sticky-note {
    width: 100%;
    height: 100%;
    border-radius: var(--radius-sticky);
    animation: none;
  }

  .desktop-icons {
    position: absolute;
    top: 24px;
    left: 24px;
    display: flex;
    gap: var(--space-3);
  }

  .fake-icon {
    width: 52px;
    height: 52px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 14px;
    color: rgba(255, 255, 255, 0.66);
    backdrop-filter: blur(8px);
  }

  .fake-icon.blue {
    background: rgba(59, 130, 246, 0.24);
  }

  .fake-icon.green {
    background: rgba(16, 185, 129, 0.22);
  }

  .fake-icon.purple {
    background: rgba(139, 92, 246, 0.22);
  }

  .sticky-note {
    width: 340px;
    height: 440px;
    display: flex;
    flex-direction: column;
    border-radius: var(--radius-sticky);
    background: rgb(255 255 255 / var(--sticky-alpha, 0.94));
    box-shadow: var(--shadow-xl), var(--sticky-inset);
    backdrop-filter: blur(calc(24px * var(--sticky-alpha, 0.94))) saturate(180%);
    color: var(--color-text);
    overflow: hidden;
    animation: sticky-in var(--duration-base) var(--ease-standard);
  }

  @supports not (backdrop-filter: blur(1px)) {
    .sticky-note {
      background: rgb(255 255 255 / var(--sticky-alpha, 0.94));
    }
  }

  .sticky-header {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: 10px 14px;
    border-bottom: 1px solid rgba(226, 232, 240, 0.75);
    cursor: grab;
    user-select: none;
  }

  .sticky-handle {
    display: flex;
    flex-direction: column;
    gap: 2.5px;
    padding: 2px 0;
    opacity: 0.5;
  }

  .sticky-handle span {
    width: 12px;
    height: 1.5px;
    border-radius: 1px;
    background: var(--color-text-2);
  }

  .sticky-title {
    flex: 1;
    font-size: var(--text-base);
    font-weight: var(--weight-semibold);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .sticky-btn {
    width: 24px;
    height: 24px;
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: none;
    border-radius: 6px;
    background: transparent;
    color: var(--color-text-2);
    transition:
      background var(--duration-fast) var(--ease-out),
      color var(--duration-fast) var(--ease-out);
  }

  .sticky-btn:hover {
    background: rgba(15, 23, 42, 0.06);
  }

  .sticky-btn.pinned {
    background: var(--color-primary-50);
    color: var(--color-primary);
  }

  .sticky-progress {
    flex-shrink: 0;
    padding: 10px 14px 8px;
  }

  .sticky-progress-row {
    display: flex;
    justify-content: space-between;
    gap: var(--space-2);
    margin-bottom: 6px;
    font-size: var(--text-sm);
    color: var(--color-text-2);
  }

  .sticky-progress-row strong {
    color: var(--color-text);
    font-weight: var(--weight-semibold);
  }

  .sticky-add {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 4px 14px 8px;
    padding: 7px 11px;
    border-radius: var(--radius-sm);
    background: rgba(241, 245, 249, 0.86);
    color: var(--color-text-3);
  }

  .sticky-add-input {
    flex: 1;
    min-width: 0;
    border: none;
    outline: none;
    background: transparent;
    color: var(--color-text);
    font-size: var(--text-base);
  }

  .sticky-add-input::placeholder {
    color: var(--color-text-3);
  }

  .sticky-tasks {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 0 var(--space-2);
  }

  .sticky-task {
    display: flex;
    align-items: center;
    gap: 9px;
    padding: 8px;
    border-radius: var(--radius-sm);
    color: var(--color-text);
    font-size: var(--text-base);
    transition: background var(--duration-fast) var(--ease-out);
  }

  .sticky-task:hover {
    background: rgba(241, 245, 249, 0.9);
  }

  .sticky-task .checkbox {
    width: 17px;
    height: 17px;
    border-width: 1.5px;
  }

  .sticky-task .checkbox.is-checked::after {
    width: 4px;
    height: 8px;
    border-width: 0 1.5px 1.5px 0;
  }

  .sticky-task-title {
    flex: 1;
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .sticky-task.is-done .sticky-task-title {
    color: var(--color-text-3);
    text-decoration: line-through;
  }

  .sticky-task-meta {
    flex-shrink: 0;
    font-size: 10.5px;
    color: var(--color-text-3);
  }

  .sticky-task-meta.urgent {
    color: var(--color-danger);
    font-weight: var(--weight-semibold);
  }

  .sticky-empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 6px;
    height: 100%;
    padding: var(--space-3);
    color: var(--color-text-3);
    font-size: var(--text-base);
    text-align: center;
  }

  .sticky-clear {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    margin: 2px 6px 6px;
    padding: 7px 10px;
    border-radius: var(--radius-sm);
    background: var(--color-success-50);
    color: var(--color-success);
    font-size: 11.5px;
    font-weight: var(--weight-medium);
  }

  .sticky-footer {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
    padding: 9px 14px;
    border-top: 1px solid rgba(226, 232, 240, 0.75);
    background: rgb(248 250 252 / calc(var(--sticky-alpha, 0.94) * 0.72));
  }

  .sticky-list-switch {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 11.5px;
    color: var(--color-text-2);
    font-weight: var(--weight-medium);
  }

  .list-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    flex-shrink: 0;
  }

  .sticky-list-switch select {
    max-width: 84px;
    border: none;
    outline: none;
    background: transparent;
    color: var(--color-text-2);
    font-size: 11.5px;
    font-weight: var(--weight-medium);
    cursor: pointer;
    appearance: none;
  }

  .expand-btn {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    border: none;
    background: transparent;
    color: var(--color-primary);
    font-size: 11.5px;
    font-weight: var(--weight-semibold);
  }

  .expand-btn:hover {
    text-decoration: underline;
  }

  @keyframes stage-in {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }

  @keyframes sticky-in {
    from {
      opacity: 0;
      transform: translateY(10px) scale(0.97);
    }
    to {
      opacity: 1;
      transform: translateY(0) scale(1);
    }
  }

  :global(html[data-theme='dark']) .sticky-note {
    background: rgb(30 41 59 / var(--sticky-alpha, 0.94));
    box-shadow: var(--shadow-xl), 0 0 0 1px rgba(255, 255, 255, 0.08) inset;
  }

  :global(html[data-theme='dark']) .sticky-header,
  :global(html[data-theme='dark']) .sticky-footer {
    border-color: rgba(51, 65, 85, 0.8);
  }

  :global(html[data-theme='dark']) .sticky-footer {
    background: rgb(15 23 42 / calc(var(--sticky-alpha, 0.94) * 0.6));
  }

  :global(html[data-theme='dark']) .sticky-add {
    background: rgba(15, 23, 42, 0.5);
  }

  :global(html[data-theme='dark']) .sticky-task:hover {
    background: rgba(15, 23, 42, 0.55);
  }
</style>
