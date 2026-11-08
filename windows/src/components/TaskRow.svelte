<script lang="ts">
  import { Bell, CheckSquare, Sparkles } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
  import type { Task } from '../lib/types';
  import { formatDueShort, isOverdue, isUrgent, priorityLabel } from '../lib/utils';

  interface Props {
    task: Task;
    active: boolean;
    onopen: (task: Task) => void;
    ontoggle: (task: Task) => void;
    oncontext: (event: MouseEvent, task: Task) => void;
    onreorder: (draggedId: string, targetId: string) => void;
  }

  let { task, active, onopen, ontoggle, oncontext, onreorder }: Props = $props();

  let dragging = $state(false);
  let dragOver = $state(false);

  const doneSubtasks = $derived(task.subtasks.filter((s) => s.done).length);
  const urgent = $derived(isUrgent(task));
  const overdue = $derived(isOverdue(task));
  const dueText = $derived(
    task.dueAt
      ? overdue
        ? `${formatDueShort(task.dueAt)} · 已逾期`
        : `${formatDueShort(task.dueAt)}${urgent ? ' · 紧急' : ''}`
      : ''
  );

  function handleCheck(event: MouseEvent): void {
    event.stopPropagation();
    ontoggle(task);
  }

  function handleContext(event: MouseEvent): void {
    event.preventDefault();
    event.stopPropagation();
    oncontext(event, task);
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      onopen(task);
      return;
    }
    if (event.key === ' ') {
      event.preventDefault();
      ontoggle(task);
      return;
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const rows = Array.from(document.querySelectorAll<HTMLElement>('.task-row'));
      const index = rows.indexOf(event.currentTarget as HTMLElement);
      const next = event.key === 'ArrowDown' ? index + 1 : index - 1;
      rows[next]?.focus();
    }
  }

  function handleDragStart(event: DragEvent): void {
    dragging = true;
    event.dataTransfer?.setData('text/plain', task.id);
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
  }

  function handleDragOver(event: DragEvent): void {
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
    dragOver = true;
  }

  function handleDrop(event: DragEvent): void {
    event.preventDefault();
    dragOver = false;
    const draggedId = event.dataTransfer?.getData('text/plain');
    if (draggedId && draggedId !== task.id) onreorder(draggedId, task.id);
  }

  function handleDragEnd(): void {
    dragging = false;
    dragOver = false;
  }

  function remindText(iso: string): string {
    return formatDueShort(iso).replace(' 截止', '');
  }
</script>

<div
  class="task-row"
  class:is-done={task.status === 'done'}
  class:is-active={active}
  class:is-dragging={dragging}
  class:is-drag-over={dragOver}
  role="button"
  tabindex="0"
  draggable="true"
  aria-label={task.title}
  onclick={() => onopen(task)}
  onkeydown={handleKeydown}
  oncontextmenu={handleContext}
  ondragstart={handleDragStart}
  ondragover={handleDragOver}
  ondragleave={() => (dragOver = false)}
  ondrop={handleDrop}
  ondragend={handleDragEnd}
>
  <button
    class="checkbox"
    class:is-checked={task.status === 'done'}
    role="checkbox"
    aria-checked={task.status === 'done'}
    aria-label={task.status === 'done' ? `标记「${task.title}」为未完成` : `完成「${task.title}」`}
    onclick={handleCheck}
  ></button>

  <div class="task-body">
    <div class="task-title">
      <span class="title-text">{task.title}</span>
      {#if task.source === 'ai'}
        <span class="ai-badge" title="由 AI 创建，数据层与手动操作等价">
          <Sparkles size="10" />
          AI
        </span>
      {/if}
    </div>

    <div class="task-meta">
      <span class="meta-item" title="优先级">
        <span class="priority-dot {task.priority}"></span>
        {priorityLabel(task.priority)}
      </span>

      {#if task.dueAt}
        <span class="meta-item" class:urgent={urgent || overdue} title="截止时间">
          {dueText}
        </span>
      {/if}

      {#if task.remindAt}
        <span class="meta-item" title="提醒时间">
          <Bell size="11" />
          {remindText(task.remindAt)} 提醒
        </span>
      {/if}

      {#each task.tagIds as tagId (tagId)}
        {@const tag = store.tagById(tagId)}
        {#if tag}
          <span class="tag-chip" style="--tag-bg:{tag.bg};--tag-fg:{tag.fg}">
            <span class="tag-dot"></span>
            {tag.name}
          </span>
        {/if}
      {/each}

      {#if task.subtasks.length > 0}
        <span class="subtask-progress" title="子任务进度">
          <CheckSquare size="10" />
          {doneSubtasks} / {task.subtasks.length}
        </span>
      {/if}
    </div>
  </div>
</div>

<style>
  .task-row {
    position: relative;
    display: flex;
    align-items: flex-start;
    gap: 10px;
    padding: 10px var(--space-3);
    border: 1px solid transparent;
    border-radius: var(--radius-md);
    cursor: pointer;
    transition:
      background var(--duration-fast) var(--ease-out),
      border-color var(--duration-fast) var(--ease-out),
      opacity var(--duration-fast) var(--ease-out);
  }

  .task-row:hover {
    background: var(--color-bg);
    border-color: var(--color-border-2);
  }

  .task-row.is-active {
    border-color: #bfdbfe;
    background: var(--color-primary-50);
    box-shadow: inset 3px 0 0 var(--color-primary);
  }

  .task-row.is-dragging {
    opacity: 0.45;
  }

  .task-row.is-drag-over::before {
    content: '';
    position: absolute;
    top: -1px;
    left: 8px;
    right: 8px;
    height: 2px;
    border-radius: 2px;
    background: var(--color-primary);
  }

  .task-body {
    flex: 1;
    min-width: 0;
  }

  .task-title {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
    font-size: var(--text-md);
    font-weight: var(--weight-medium);
    line-height: 1.4;
    color: var(--color-text);
  }

  .title-text {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .task-meta {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-top: var(--space-1);
    font-size: var(--text-sm);
    line-height: 1.5;
    color: var(--color-text-3);
  }

  .meta-item {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    white-space: nowrap;
  }

  .meta-item.urgent {
    color: var(--color-danger);
    font-weight: var(--weight-semibold);
  }

  .subtask-progress {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 0 6px;
    border-radius: var(--radius-full);
    background: var(--color-border-2);
    color: var(--color-text-3);
    font-size: 10.5px;
    white-space: nowrap;
  }

  .task-row.is-done .title-text {
    color: var(--color-text-3);
    text-decoration: line-through;
  }

  .task-row.is-done .task-title,
  .task-row.is-done .task-meta {
    opacity: 0.86;
  }

  :global(html[data-theme='dark']) .task-row:hover {
    background: var(--color-bg);
    border-color: var(--color-border);
  }

  :global(html[data-theme='dark']) .task-row.is-active {
    border-color: rgba(59, 130, 246, 0.35);
    background: var(--color-primary-50);
  }

  @media (max-width: 1040px) {
    .task-meta {
      gap: 6px;
    }
  }
</style>
