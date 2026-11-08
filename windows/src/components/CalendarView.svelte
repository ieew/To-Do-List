<script lang="ts">
  import { CalendarDays, Sparkles } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
  import type { Task } from '../lib/types';
  import { isOverdue, sameDay } from '../lib/utils';

  const WEEKDAY = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
  const now = new Date();

  const weekStart = (() => {
    const d = new Date(now);
    const offset = (d.getDay() + 6) % 7;
    d.setDate(d.getDate() - offset);
    d.setHours(0, 0, 0, 0);
    return d;
  })();

  const days = Array.from({ length: 7 }, (_, index) => {
    const d = new Date(weekStart);
    d.setDate(d.getDate() + index);
    return d;
  });

  function tasksOf(day: Date): Task[] {
    return store.tasks
      .filter((task) => task.dueAt && sameDay(new Date(task.dueAt), day))
      .sort((a, b) => new Date(a.dueAt ?? 0).getTime() - new Date(b.dueAt ?? 0).getTime());
  }

  const weekTasks = $derived(
    store.tasks.filter((task) => {
      if (!task.dueAt) return false;
      const due = new Date(task.dueAt);
      return days.some((day) => sameDay(due, day));
    })
  );
  const overdue = $derived(store.tasks.filter((t) => isOverdue(t, now)).length);
</script>

<div class="main-scroll">
  <div class="calendar-head">
    <div>
      <h2>日历视图 · 本周</h2>
      <p>周视图用于规划时间分布；拖拽改期将在后续版本开放，当前点击任务可打开详情。</p>
    </div>
    <div class="calendar-chips">
      <span class="stat-chip">
        <CalendarDays size="12" />
        本周 {weekTasks.length} 项
      </span>
      <span class="stat-chip" class:is-danger={overdue > 0}>
        逾期 {overdue} 项
      </span>
    </div>
  </div>

  <div class="week-grid">
    {#each days as day, index (day.toISOString())}
      {@const dayTasks = tasksOf(day)}
      <div class="day-column" class:is-today={sameDay(day, now)}>
        <div class="day-head">
          <span class="day-name">{WEEKDAY[index]}</span>
          <span class="day-date">{day.getMonth() + 1}/{day.getDate()}</span>
        </div>
        <div class="day-body">
          {#each dayTasks as task (task.id)}
            <button
              class="calendar-task"
              class:is-done={task.status === 'done'}
              onclick={() => store.openTask(task.id)}
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

  <div class="calendar-note">
    本周视图内共 {weekTasks.length} 条任务，按截止时间分布。MVP 提供只读周视图，拖拽改期将在后续版本开放。
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

  :global(html[data-theme='dark']) .stat-chip.is-danger {
    border-color: rgba(239, 68, 68, 0.4);
    background: rgba(239, 68, 68, 0.12);
  }
</style>
