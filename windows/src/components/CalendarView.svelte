<script lang="ts">
  import { CalendarDays, ChevronLeft, ChevronRight, Sparkles } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
  import type { Task } from '../lib/types';
  import { isOverdue, sameDay } from '../lib/utils';

  type CalMode = 'week' | 'month';
  const WEEKDAY = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
  const MAX_CELL_TASKS = 3;

  let mode = $state<CalMode>('week');
  let anchor = $state(new Date());
  let dragOverKey = $state<string | null>(null);

  function startOfWeek(date: Date): Date {
    const d = new Date(date);
    const offset = (d.getDay() + 6) % 7;
    d.setDate(d.getDate() - offset);
    d.setHours(0, 0, 0, 0);
    return d;
  }

  function addDays(date: Date, days: number): Date {
    const d = new Date(date);
    d.setDate(d.getDate() + days);
    return d;
  }

  function dayKey(date: Date): string {
    return `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`;
  }

  const days = $derived.by(() => {
    if (mode === 'week') {
      const start = startOfWeek(anchor);
      return Array.from({ length: 7 }, (_, index) => addDays(start, index));
    }
    const first = new Date(anchor.getFullYear(), anchor.getMonth(), 1);
    const start = startOfWeek(first);
    return Array.from({ length: 42 }, (_, index) => addDays(start, index));
  });

  const rangeLabel = $derived.by(() => {
    if (mode === 'month') {
      return `${anchor.getFullYear()} 年 ${anchor.getMonth() + 1} 月`;
    }
    const start = startOfWeek(anchor);
    const end = addDays(start, 6);
    return `${start.getMonth() + 1}月${start.getDate()}日 – ${end.getMonth() + 1}月${end.getDate()}日`;
  });

  function tasksOf(day: Date): Task[] {
    return store.viewTasks
      .filter((task) => task.dueAt && sameDay(new Date(task.dueAt), day))
      .sort((a, b) => new Date(a.dueAt ?? 0).getTime() - new Date(b.dueAt ?? 0).getTime());
  }

  const rangeTasks = $derived(
    store.viewTasks.filter((task) => {
      if (!task.dueAt) return false;
      return days.some((day) => sameDay(new Date(task.dueAt as string), day));
    })
  );
  const overdue = $derived(store.viewTasks.filter((t) => isOverdue(t)).length);

  function shift(delta: number): void {
    if (mode === 'week') {
      anchor = addDays(anchor, delta * 7);
    } else {
      anchor = new Date(anchor.getFullYear(), anchor.getMonth() + delta, 1);
    }
  }

  function goToday(): void {
    anchor = new Date();
  }

  function handleDragStart(event: DragEvent, task: Task): void {
    event.dataTransfer?.setData('text/plain', task.id);
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
  }

  function handleDrop(event: DragEvent, day: Date): void {
    event.preventDefault();
    dragOverKey = null;
    const taskId = event.dataTransfer?.getData('text/plain');
    if (taskId) void store.rescheduleTask(taskId, day.toISOString());
  }
</script>

<div class="main-scroll">
  <div class="calendar-head">
    <div>
      <h2>日历视图</h2>
      <p>按截止时间排布；把任务拖到另一天即可改期，保留原时间，无截止时间默认 09:00。</p>
    </div>
    <div class="calendar-chips">
      <div class="segmented" role="tablist" aria-label="日历视图切换">
        <button
          class:is-active={mode === 'week'}
          role="tab"
          aria-selected={mode === 'week'}
          onclick={() => (mode = 'week')}
        >
          周
        </button>
        <button
          class:is-active={mode === 'month'}
          role="tab"
          aria-selected={mode === 'month'}
          onclick={() => (mode = 'month')}
        >
          月
        </button>
      </div>
      <span class="stat-chip">
        <CalendarDays size="12" />
        {rangeLabel}
      </span>
      <span class="stat-chip" class:is-danger={overdue > 0}>
        逾期 {overdue} 项
      </span>
    </div>
  </div>

  <div class="calendar-nav">
    <button class="nav-btn" aria-label="上一段时间" onclick={() => shift(-1)}>
      <ChevronLeft size="14" />
    </button>
    <button class="nav-today" onclick={goToday}>今天</button>
    <button class="nav-btn" aria-label="下一段时间" onclick={() => shift(1)}>
      <ChevronRight size="14" />
    </button>
    <span class="nav-hint">当前视图共 {rangeTasks.length} 项</span>
  </div>

  {#if mode === 'week'}
    <div class="week-grid">
      {#each days as day, index (day.toISOString())}
        {@const dayTasks = tasksOf(day)}
        <div
          class="day-column"
          class:is-today={sameDay(day, new Date())}
          class:is-drop={dragOverKey === dayKey(day)}
          role="group"
          aria-label="拖放改期：{WEEKDAY[index]} {day.getMonth() + 1}/{day.getDate()}"
          ondragover={(event) => {
            event.preventDefault();
            dragOverKey = dayKey(day);
          }}
          ondragleave={() => (dragOverKey = null)}
          ondrop={(event) => handleDrop(event, day)}
        >
          <div class="day-head">
            <span class="day-name">{WEEKDAY[index]}</span>
            <span class="day-date">{day.getMonth() + 1}/{day.getDate()}</span>
          </div>
          <div class="day-body">
            {#each dayTasks as task (task.id)}
              <button
                class="calendar-task"
                class:is-done={task.status === 'done'}
                draggable="true"
                onclick={() => store.openTask(task.id)}
                ondragstart={(event) => handleDragStart(event, task)}
                title={task.title}
              >
                <span class="priority-dot {task.priority}"></span>
                <span class="calendar-task-title">{task.title}</span>
                {#if task.source === 'ai'}
                  <Sparkles size="9" class="ai-spark" />
                {/if}
              </button>
            {:else}
              <span class="day-empty">无安排</span>
            {/each}
          </div>
        </div>
      {/each}
    </div>
  {:else}
    <div class="month-grid">
      {#each WEEKDAY as label (label)}
        <div class="month-weekday">{label}</div>
      {/each}
      {#each days as day (day.toISOString())}
        {@const dayTasks = tasksOf(day)}
        <div
          class="month-cell"
          class:is-outside={day.getMonth() !== anchor.getMonth()}
          class:is-today={sameDay(day, new Date())}
          class:is-drop={dragOverKey === dayKey(day)}
          role="group"
          aria-label="拖放改期：{day.getMonth() + 1}月{day.getDate()}日"
          ondragover={(event) => {
            event.preventDefault();
            dragOverKey = dayKey(day);
          }}
          ondragleave={() => (dragOverKey = null)}
          ondrop={(event) => handleDrop(event, day)}
        >
          <div class="month-cell-head">
            <span>{day.getDate()}</span>
            {#if dayTasks.length > 0}
              <span class="month-count">{dayTasks.length}</span>
            {/if}
          </div>
          <div class="month-cell-body">
            {#each dayTasks.slice(0, MAX_CELL_TASKS) as task (task.id)}
              <button
                class="calendar-task month-task"
                class:is-done={task.status === 'done'}
                draggable="true"
                onclick={() => store.openTask(task.id)}
                ondragstart={(event) => handleDragStart(event, task)}
                title={task.title}
              >
                <span class="priority-dot {task.priority}"></span>
                <span class="calendar-task-title">{task.title}</span>
              </button>
            {/each}
            {#if dayTasks.length > MAX_CELL_TASKS}
              <span class="month-more">还有 {dayTasks.length - MAX_CELL_TASKS} 项</span>
            {/if}
          </div>
        </div>
      {/each}
    </div>
  {/if}

  <div class="calendar-note">
    拖拽改期只修改截止时间，不会自动平移提醒时间；需要同步调整提醒时请到详情抽屉里修改。
  </div>
</div>

<style>
  .main-scroll {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: var(--space-4) var(--space-5) var(--space-6);
  }

  .calendar-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: var(--space-4);
    padding: var(--space-2) 2px var(--space-4);
  }

  .calendar-head h2 {
    margin: 0;
    font-size: var(--text-lg);
    font-weight: var(--weight-bold);
    color: var(--color-text);
  }

  .calendar-head p {
    margin: 6px 0 0;
    max-width: 560px;
    font-size: var(--text-base);
    line-height: 1.65;
    color: var(--color-text-2);
  }

  .calendar-chips {
    display: flex;
    gap: var(--space-2);
    flex-shrink: 0;
  }

  .stat-chip {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 4px 9px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-full);
    background: var(--color-card);
    color: var(--color-text-2);
    font-size: var(--text-sm);
    font-weight: var(--weight-medium);
    white-space: nowrap;
  }

  .stat-chip.is-danger {
    border-color: #fecaca;
    background: #fef2f2;
    color: var(--color-danger);
  }

  .week-grid {
    display: grid;
    grid-template-columns: repeat(7, minmax(118px, 1fr));
    gap: var(--space-2);
    min-width: 820px;
    overflow-x: auto;
    padding-bottom: var(--space-2);
  }

  .day-column {
    border: 1px solid var(--color-border-2);
    border-radius: var(--radius-md);
    background: var(--color-card);
    min-height: 340px;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .day-column.is-today {
    border-color: #bfdbfe;
    box-shadow: inset 0 0 0 1px var(--color-primary-100);
  }

  .day-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    padding: 9px 10px;
    border-bottom: 1px solid var(--color-border-2);
    background: var(--color-sidebar);
  }

  .day-column.is-today .day-head {
    background: var(--color-primary-50);
  }

  .day-name {
    font-size: var(--text-sm);
    font-weight: var(--weight-semibold);
    color: var(--color-text-2);
  }

  .day-date {
    font-family: var(--font-mono);
    font-size: var(--text-xs);
    color: var(--color-text-3);
  }

  .day-body {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 5px;
    padding: var(--space-2);
  }

  .calendar-task {
    display: flex;
    align-items: center;
    gap: 5px;
    width: 100%;
    padding: 6px 7px;
    border: 1px solid var(--color-border-2);
    border-radius: 7px;
    background: var(--color-bg);
    color: var(--color-text);
    font-size: var(--text-sm);
    text-align: left;
    transition:
      border-color var(--duration-fast) var(--ease-out),
      background var(--duration-fast) var(--ease-out);
  }

  .calendar-task:hover {
    border-color: #bfdbfe;
    background: var(--color-primary-50);
  }

  .calendar-task.is-done {
    opacity: 0.62;
  }

  .calendar-task.is-done .calendar-task-title {
    text-decoration: line-through;
    color: var(--color-text-3);
  }

  .calendar-task-title {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .ai-spark {
    color: var(--color-ai-fg);
    flex-shrink: 0;
  }

  .day-empty {
    padding: var(--space-2);
    font-size: var(--text-sm);
    color: var(--color-text-3);
    text-align: center;
  }

  .calendar-note {
    margin-top: var(--space-4);
    padding: var(--space-3);
    border: 1px dashed var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-bg);
    color: var(--color-text-3);
    font-size: var(--text-sm);
    line-height: 1.6;
  }

  .calendar-task[draggable='true'] {
    cursor: grab;
  }

  .calendar-nav {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin-bottom: var(--space-3);
  }

  .nav-btn,
  .nav-today {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    height: 26px;
    padding: 0 8px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-card);
    color: var(--color-text-2);
    font-size: var(--text-sm);
  }

  .nav-btn {
    width: 26px;
    padding: 0;
  }

  .nav-btn:hover,
  .nav-today:hover {
    border-color: #bfdbfe;
    color: var(--color-primary);
    background: var(--color-primary-50);
  }

  .nav-hint {
    margin-left: auto;
    font-size: var(--text-sm);
    color: var(--color-text-3);
  }

  .month-grid {
    display: grid;
    grid-template-columns: repeat(7, minmax(0, 1fr));
    gap: 6px;
  }

  .month-weekday {
    padding: 4px 6px;
    font-size: var(--text-xs);
    font-weight: var(--weight-semibold);
    color: var(--color-text-3);
  }

  .month-cell {
    min-height: 104px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 6px;
    border: 1px solid var(--color-border-2);
    border-radius: var(--radius-sm);
    background: var(--color-card);
    transition:
      border-color var(--duration-fast) var(--ease-out),
      background var(--duration-fast) var(--ease-out);
  }

  .month-cell.is-outside {
    opacity: 0.55;
  }

  .month-cell.is-today {
    border-color: #bfdbfe;
    box-shadow: inset 0 0 0 1px var(--color-primary-100);
  }

  .month-cell.is-drop,
  .day-column.is-drop {
    border-color: var(--color-primary);
    background: var(--color-primary-50);
  }

  .month-cell-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: var(--text-sm);
    color: var(--color-text-2);
  }

  .month-count {
    padding: 0 5px;
    border-radius: var(--radius-full);
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-size: 9.5px;
    line-height: 15px;
  }

  .month-cell-body {
    display: flex;
    flex-direction: column;
    gap: 3px;
    min-height: 0;
  }

  .month-task {
    gap: 4px;
    padding: 3px 5px;
    border-radius: 5px;
    font-size: 10.5px;
  }

  .month-more {
    padding-left: 2px;
    font-size: 9.5px;
    color: var(--color-text-3);
  }

  :global(html[data-theme='dark']) .stat-chip.is-danger {
    border-color: rgba(239, 68, 68, 0.4);
    background: rgba(239, 68, 68, 0.12);
  }
</style>
