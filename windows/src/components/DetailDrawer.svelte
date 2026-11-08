<script lang="ts">
  import { CheckSquare, Sparkles, X } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
  import type { Priority, Task } from '../lib/types';
  import { cloneTask, fromLocalInput, toLocalInput } from '../lib/utils';
  import HistoryTimeline from './HistoryTimeline.svelte';

  let draft = $state<Task | null>(null);
  let lastKey = '';
  let confirmDelete = $state(false);
  let newSubtask = $state('');
  let subInput = $state<HTMLInputElement | null>(null);

  $effect(() => {
    const key = `${store.selectedTaskId ?? ''}:${store.drawerOpen ? 'open' : 'closed'}`;
    if (key === lastKey) return;
    lastKey = key;
    confirmDelete = false;
    newSubtask = '';
    const task = store.taskById(store.selectedTaskId);
    draft = task && store.drawerOpen ? cloneTask(task) : null;
  });

  const historyOps = $derived(
    store.selectedTaskId ? store.history.filter((op) => op.taskId === store.selectedTaskId) : []
  );

  const doneSubtasks = $derived(draft ? draft.subtasks.filter((s) => s.done).length : 0);

  const unusedTags = $derived(
    draft ? store.tags.filter((tag) => !draft?.tagIds.includes(tag.id)) : []
  );

  const priorities: { value: Priority; label: string }[] = [
    { value: 'high', label: '高' },
    { value: 'mid', label: '中' },
    { value: 'low', label: '低' }
  ];

  function save(): void {
    if (!draft) return;
    store.saveDraft(draft);
    confirmDelete = false;
  }

  function requestDelete(): void {
    if (!draft) return;
    if (!confirmDelete) {
      confirmDelete = true;
      return;
    }
    store.deleteTask(draft.id);
  }

  function toggleDraftStatus(): void {
    if (!draft) return;
    draft.status = draft.status === 'done' ? 'todo' : 'done';
  }

  function toggleSubtask(subtaskId: string): void {
    if (!draft) return;
    store.toggleSubtask(draft.id, subtaskId);
    const subtask = draft.subtasks.find((s) => s.id === subtaskId);
    if (subtask) subtask.done = !subtask.done;
  }

  async function addSubtask(): Promise<void> {
    if (!draft) return;
    const created = await store.addSubtask(draft.id, newSubtask);
    if (created) {
      draft.subtasks.push({ ...created });
      newSubtask = '';
      subInput?.focus();
    }
  }

  function removeSubtask(subtaskId: string): void {
    if (!draft) return;
    store.removeSubtask(draft.id, subtaskId);
    draft.subtasks = draft.subtasks.filter((s) => s.id !== subtaskId);
  }

  function setDue(event: Event): void {
    if (!draft) return;
    draft.dueAt = fromLocalInput((event.currentTarget as HTMLInputElement).value);
  }

  function setRemind(event: Event): void {
    if (!draft) return;
    draft.remindAt = fromLocalInput((event.currentTarget as HTMLInputElement).value);
  }

  function addTag(event: Event): void {
    if (!draft) return;
    const select = event.currentTarget as HTMLSelectElement;
    if (select.value && !draft.tagIds.includes(select.value)) {
      draft.tagIds.push(select.value);
    }
    select.value = '';
  }

  function removeTag(tagId: string): void {
    if (!draft) return;
    draft.tagIds = draft.tagIds.filter((id) => id !== tagId);
  }

  function setPriority(value: Priority): void {
    if (draft) draft.priority = value;
  }

  function visibleTagIds(task: Task): string[] {
    const listName = store.listName(task.listId);
    return task.tagIds.filter((tagId) => store.tagName(tagId) !== listName);
  }

  function setList(event: Event): void {
    if (draft) draft.listId = (event.currentTarget as HTMLSelectElement).value;
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (!store.drawerOpen) return;
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
      event.preventDefault();
      save();
    }
  }
</script>

<svelte:window onkeydown={handleKeydown} />

<aside class="detail-pane" class:open={store.drawerOpen} aria-hidden={!store.drawerOpen} inert={!store.drawerOpen}>
  <div class="detail-inner" role="dialog" aria-modal="true" aria-labelledby="detail-title">
    <div class="detail-head">
      <div class="detail-tabs" role="tablist" aria-label="详情面板">
        <button
          class="detail-tab"
          class:is-active={store.drawerTab === 'detail'}
          role="tab"
          aria-selected={store.drawerTab === 'detail'}
          onclick={() => (store.drawerTab = 'detail')}
        >
          详情
        </button>
        <button
          class="detail-tab"
          class:is-active={store.drawerTab === 'history'}
          role="tab"
          aria-selected={store.drawerTab === 'history'}
          onclick={() => (store.drawerTab = 'history')}
        >
          历史
          <span class="tab-count">{historyOps.length}</span>
        </button>
      </div>
      <button class="icon-button" title="关闭详情 (Esc)" aria-label="关闭详情" onclick={() => store.closeDrawer()}>
        <X size="15" />
      </button>
    </div>

    {#if store.drawerTab === 'history'}
      <div class="detail-panel history-panel">
        <HistoryTimeline ops={historyOps} />
      </div>
    {:else if draft}
      <div class="detail-panel">
        <div class="detail-header">
          <button
            class="checkbox"
            class:is-checked={draft.status === 'done'}
            role="checkbox"
            aria-checked={draft.status === 'done'}
            aria-label="切换完成状态"
            onclick={toggleDraftStatus}
          ></button>
          <input id="detail-title" class="detail-title-input" bind:value={draft.title} aria-label="任务标题" />
        </div>

        <div class="detail-meta-row">
          {#if draft.source === 'ai'}
            <span class="ai-badge"><Sparkles size="10" />由 AI 创建</span>
          {/if}
          <span class="list-chip">
            <span class="list-dot" style="background:{store.listById(draft.listId)?.color ?? '#3B82F6'}"></span>
            {store.listName(draft.listId)}
          </span>
          {#each visibleTagIds(draft) as tagId (tagId)}
            {@const tag = store.tagById(tagId)}
            {#if tag}
              <span class="tag-chip" style="--tag-bg:{tag.bg};--tag-fg:{tag.fg}">
                <span class="tag-dot"></span>
                {tag.name}
                <button class="tag-remove" aria-label="移除标签 {tag.name}" onclick={() => removeTag(tagId)}>
                  <X size="9" />
                </button>
              </span>
            {/if}
          {/each}
          <select class="tag-add-select" value="" onchange={addTag} aria-label="添加标签">
            <option value="">＋ 标签</option>
            {#each unusedTags as tag (tag.id)}
              <option value={tag.id}>{tag.name}</option>
            {/each}
          </select>
        </div>

        <div class="field-group">
          <div class="field-row">
            <span class="field-label">优先级</span>
            <div class="priority-options">
              {#each priorities as item (item.value)}
                <button
                  class="priority-option"
                  class:is-active={draft.priority === item.value}
                  onclick={() => setPriority(item.value)}
                >
                  <span class="priority-dot {item.value}"></span>
                  {item.label}
                </button>
              {/each}
            </div>
          </div>

          <label class="field-row">
            <span class="field-label">截止</span>
            <input class="field-input field-input-sm" type="datetime-local" value={toLocalInput(draft.dueAt)} oninput={setDue} />
          </label>

          <label class="field-row">
            <span class="field-label">提醒</span>
            <input class="field-input field-input-sm" type="datetime-local" value={toLocalInput(draft.remindAt)} oninput={setRemind} />
          </label>

          <label class="field-row">
            <span class="field-label">清单</span>
            <select class="field-select field-input-sm" value={draft.listId} onchange={setList}>
              {#each store.lists.filter((list) => !list.smart) as list (list.id)}
                <option value={list.id}>{list.name}</option>
              {/each}
            </select>
          </label>
        </div>

        <div class="detail-section">
          <div class="detail-section-title">
            <span>子任务 · {doneSubtasks}/{draft.subtasks.length}</span>
            <button class="add-btn" onclick={() => subInput?.focus()}>＋ 添加</button>
          </div>
          <div class="subtask-list">
            {#each draft.subtasks as subtask (subtask.id)}
              <div class="subtask" class:is-done={subtask.done}>
                <button
                  class="mini-check"
                  class:is-checked={subtask.done}
                  role="checkbox"
                  aria-checked={subtask.done}
                  aria-label={subtask.text}
                  onclick={() => toggleSubtask(subtask.id)}
                ></button>
                <span class="subtask-text">{subtask.text}</span>
                <button class="subtask-remove" aria-label="删除子任务" onclick={() => removeSubtask(subtask.id)}>
                  <X size="10" />
                </button>
              </div>
            {/each}
            <div class="subtask-add-row">
              <CheckSquare size="13" class="subtask-add-icon" />
              <input
                class="subtask-add-input"
                placeholder="添加子任务，回车确认"
                bind:value={newSubtask}
                bind:this={subInput}
                onkeydown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault();
                    addSubtask();
                  }
                }}
              />
            </div>
          </div>
        </div>

        <div class="detail-section">
          <div class="detail-section-title"><span>备注</span></div>
          <textarea class="field-textarea notes-input" placeholder="点击添加备注…" bind:value={draft.notes}></textarea>
        </div>

        <div class="detail-actions">
          <button class="btn btn-danger" onclick={requestDelete}>
            {confirmDelete ? '确认删除' : '删除'}
          </button>
          <button class="btn btn-primary" onclick={save}>保存</button>
        </div>
        {#if confirmDelete}
          <div class="delete-hint">再次点击「确认删除」将从当前工作区移除该任务。</div>
        {/if}
      </div>
    {/if}
  </div>
</aside>

<style>
  .detail-pane {
    width: 0;
    flex-shrink: 0;
    overflow: hidden;
    background: var(--color-card);
    border-left: 1px solid transparent;
    transition:
      width 280ms var(--ease-standard),
      border-color 280ms var(--ease-standard);
    z-index: var(--z-drawer);
  }

  .detail-pane.open {
    width: var(--drawer-w);
    border-left-color: var(--color-border);
  }

  .detail-inner {
    width: var(--drawer-w);
    height: 100%;
    display: flex;
    flex-direction: column;
  }

  .detail-head {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3) var(--space-2) var(--space-5);
    border-bottom: 1px solid var(--color-border-2);
  }

  .detail-tabs {
    flex: 1;
    display: flex;
    gap: 2px;
  }

  .detail-tab {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 6px 10px;
    border: none;
    border-radius: 6px;
    background: transparent;
    color: var(--color-text-2);
    font-size: var(--text-base);
    font-weight: var(--weight-medium);
    transition:
      background var(--duration-fast) var(--ease-out),
      color var(--duration-fast) var(--ease-out);
  }

  .detail-tab:hover {
    background: var(--color-border-2);
    color: var(--color-text);
  }

  .detail-tab.is-active {
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-weight: var(--weight-semibold);
  }

  .tab-count {
    padding: 0 4px;
    border-radius: var(--radius-full);
    background: var(--color-border-2);
    color: var(--color-text-3);
    font-size: 9.5px;
    line-height: 14px;
  }

  .detail-tab.is-active .tab-count {
    background: var(--color-card);
    color: var(--color-primary);
  }

  .detail-panel {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    padding: 18px var(--space-5) var(--space-6);
    overflow-y: auto;
  }

  .detail-header {
    display: flex;
    align-items: flex-start;
    gap: 10px;
  }

  .detail-title-input {
    flex: 1;
    min-width: 0;
    padding: 2px 4px;
    border: 1px solid transparent;
    border-radius: 6px;
    background: transparent;
    color: var(--color-text);
    font-family: var(--font-sans);
    font-size: var(--text-lg);
    font-weight: var(--weight-semibold);
    line-height: 1.4;
  }

  .detail-title-input:hover {
    border-color: var(--color-border-2);
  }

  .detail-title-input:focus {
    border-color: var(--color-primary);
    outline: none;
    box-shadow: 0 0 0 3px var(--color-primary-50);
  }

  .detail-meta-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px;
  }

  .list-chip {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    height: 16px;
    padding: 0 7px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-full);
    background: var(--color-card);
    color: var(--color-text-2);
    font-size: var(--text-sm);
    white-space: nowrap;
  }

  .list-dot {
    width: 5px;
    height: 5px;
    border-radius: 50%;
  }

  .tag-remove {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 12px;
    height: 12px;
    padding: 0;
    margin-right: -3px;
    border: none;
    border-radius: 50%;
    background: transparent;
    color: currentColor;
    opacity: 0.6;
  }

  .tag-remove:hover {
    background: rgba(15, 23, 42, 0.12);
    opacity: 1;
  }

  .tag-add-select {
    height: 18px;
    padding: 0 4px;
    border: 1px dashed var(--color-border);
    border-radius: var(--radius-full);
    background: transparent;
    color: var(--color-text-3);
    font-size: 10px;
    cursor: pointer;
  }

  .field-group {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3);
    border: 1px solid var(--color-border-2);
    border-radius: var(--radius-md);
    background: var(--color-sidebar);
  }

  .field-row {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-height: 28px;
    font-size: var(--text-base);
    color: var(--color-text-2);
  }

  .field-label {
    width: 48px;
    flex-shrink: 0;
    color: var(--color-text-3);
    font-size: 11.5px;
  }

  .field-input-sm {
    flex: 1;
    min-width: 0;
    padding: 4px 8px;
    font-size: 11.5px;
  }

  .priority-options {
    display: flex;
    gap: 4px;
    flex: 1;
  }

  .priority-option {
    flex: 1;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 4px;
    padding: 4px 0;
    border: 1px solid var(--color-border);
    border-radius: 6px;
    background: var(--color-card);
    color: var(--color-text-2);
    font-size: var(--text-sm);
    font-weight: var(--weight-medium);
  }

  .priority-option.is-active {
    border-color: #bfdbfe;
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-weight: var(--weight-semibold);
  }

  .detail-section {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .detail-section-title {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 11px;
    font-weight: var(--weight-semibold);
    letter-spacing: 0.05em;
    text-transform: uppercase;
    color: var(--color-text-3);
  }

  .add-btn {
    padding: 0;
    border: none;
    background: transparent;
    color: var(--color-primary);
    font-size: var(--text-sm);
    font-weight: var(--weight-medium);
    text-transform: none;
    letter-spacing: 0;
  }

  .subtask-list {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .subtask {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: 5px 6px;
    border-radius: 6px;
    font-size: var(--text-base);
    color: var(--color-text);
  }

  .subtask:hover {
    background: var(--color-border-2);
  }

  .subtask.is-done .mini-check {
    border-color: var(--color-success);
    background: var(--color-success);
  }

  .subtask.is-done .mini-check::after {
    content: '';
    width: 3px;
    height: 6px;
    border: solid #fff;
    border-width: 0 1.5px 1.5px 0;
    transform: rotate(45deg) translate(-0.5px, -0.5px);
  }

  .subtask-text {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .subtask.is-done .subtask-text {
    color: var(--color-text-3);
    text-decoration: line-through;
  }

  .subtask-remove {
    flex-shrink: 0;
    width: 18px;
    height: 18px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: none;
    border-radius: 4px;
    background: transparent;
    color: var(--color-text-3);
    opacity: 0;
  }

  .subtask:hover .subtask-remove {
    opacity: 1;
  }

  .subtask-remove:hover {
    background: #fef2f2;
    color: var(--color-danger);
  }

  .subtask-add-row {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: 4px 6px;
    border: 1px dashed transparent;
    border-radius: 6px;
  }

  .subtask-add-row:focus-within {
    border-color: var(--color-border);
    background: var(--color-card);
  }

  .subtask-add-icon {
    color: var(--color-text-3);
    flex-shrink: 0;
  }

  .subtask-add-input {
    flex: 1;
    min-width: 0;
    border: none;
    outline: none;
    background: transparent;
    color: var(--color-text);
    font-size: var(--text-base);
  }

  .subtask-add-input::placeholder {
    color: var(--color-text-3);
  }

  .notes-input {
    min-height: 64px;
    font-size: var(--text-base);
    background: var(--color-sidebar);
  }

  .detail-actions {
    display: flex;
    gap: var(--space-2);
    margin-top: auto;
    padding-top: var(--space-3);
    border-top: 1px solid var(--color-border-2);
  }

  .detail-actions .btn {
    flex: 1;
  }

  .delete-hint {
    margin-top: -6px;
    padding: 6px 8px;
    border-radius: 6px;
    background: #fef2f2;
    color: var(--color-danger);
    font-size: 10.5px;
    line-height: 1.5;
  }

  .history-panel {
    gap: var(--space-3);
  }

  :global(html[data-theme='dark']) .delete-hint {
    background: rgba(239, 68, 68, 0.12);
  }

  :global(html[data-theme='dark']) .subtask-remove:hover {
    background: rgba(239, 68, 68, 0.12);
  }
</style>
