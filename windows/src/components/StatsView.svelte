<script lang="ts">
  import { Activity, CheckCircle2, Clock3, Sparkles } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
  import { isOverdue } from '../lib/utils';

  const total = $derived(store.tasks.length);
  const done = $derived(store.tasks.filter((t) => t.status === 'done').length);
  const pct = $derived(total === 0 ? 0 : Math.round((done / total) * 100));
  const overdue = $derived(store.tasks.filter((t) => isOverdue(t)).length);
  const aiCount = $derived(store.tasks.filter((t) => t.source === 'ai').length);

  const priorityCounts = $derived({
    high: store.tasks.filter((t) => t.priority === 'high').length,
    mid: store.tasks.filter((t) => t.priority === 'mid').length,
    low: store.tasks.filter((t) => t.priority === 'low').length
  });

  const listStats = $derived(
    store.lists
      .filter((list) => !list.smart)
      .map((list) => {
        const tasks = store.tasks.filter((t) => t.listId === list.id);
        return {
          ...list,
          total: tasks.length,
          done: tasks.filter((t) => t.status === 'done').length
        };
      })
  );

  const maxListTotal = $derived(Math.max(1, ...listStats.map((item) => item.total)));
  const maxPriority = $derived(Math.max(1, priorityCounts.high, priorityCounts.mid, priorityCounts.low));
</script>

<div class="main-scroll">
  <div class="stats-head">
    <div>
      <h2>统计视图</h2>
      <p>数据来自投影结果：完成率按「已完成 / 全部任务」实时计算，不使用写死百分比。</p>
    </div>
    <span class="fresh-chip"><Activity size="12" />实时</span>
  </div>

  <div class="stat-cards">
    <div class="stat-card accent">
      <div class="ring" style="background: conic-gradient(var(--color-primary) 0 {pct}%, #e2e8f0 {pct}% 100%);">
        <div class="ring-inner">{pct}%</div>
      </div>
      <div class="stat-card-text">
        <span class="stat-label">整体完成率</span>
        <strong>{done} / {total} 完成</strong>
        <span class="stat-hint">含已完成与待办任务</span>
      </div>
    </div>

    <div class="stat-card">
      <span class="stat-icon success"><CheckCircle2 size="16" /></span>
      <div class="stat-card-text">
        <span class="stat-label">已完成</span>
        <strong>{done} 项</strong>
        <span class="stat-hint">勾选后移入「已完成」分组</span>
      </div>
    </div>

    <div class="stat-card">
      <span class="stat-icon danger"><Clock3 size="16" /></span>
      <div class="stat-card-text">
        <span class="stat-label">已逾期</span>
        <strong>{overdue} 项</strong>
        <span class="stat-hint">截止时间早于当前时间</span>
      </div>
    </div>

    <div class="stat-card">
      <span class="stat-icon ai"><Sparkles size="16" /></span>
      <div class="stat-card-text">
        <span class="stat-label">AI 创建</span>
        <strong>{aiCount} 项</strong>
        <span class="stat-hint">source=ai，与人工操作在数据层等价</span>
      </div>
    </div>
  </div>

  <div class="panel-grid">
    <section class="chart-card">
      <div class="chart-head">
        <h3>清单分布</h3>
        <span>任务数 / 已完成</span>
      </div>
      <div class="bar-list">
        {#each listStats as item (item.id)}
          <div class="bar-row">
            <span class="bar-name">
              <span class="bar-dot" style="background:{item.color}"></span>
              {item.name}
            </span>
            <div class="bar-track">
              <span class="bar-fill" style="width:{(item.total / maxListTotal) * 100}%;background:{item.color}"></span>
              <span class="bar-done" style="width:{(item.done / maxListTotal) * 100}%"></span>
            </div>
            <span class="bar-value">{item.total} · {item.done}</span>
          </div>
        {/each}
      </div>
    </section>

    <section class="chart-card">
      <div class="chart-head">
        <h3>优先级分布</h3>
        <span>按任务数量</span>
      </div>
      <div class="priority-bars">
        <div class="priority-bar-row">
          <span class="bar-name"><span class="priority-dot high"></span>高</span>
          <div class="bar-track">
            <span class="bar-fill" style="width:{(priorityCounts.high / maxPriority) * 100}%;background:var(--color-danger)"></span>
          </div>
          <span class="bar-value">{priorityCounts.high}</span>
        </div>
        <div class="priority-bar-row">
          <span class="bar-name"><span class="priority-dot mid"></span>中</span>
          <div class="bar-track">
            <span class="bar-fill" style="width:{(priorityCounts.mid / maxPriority) * 100}%;background:var(--color-warning)"></span>
          </div>
          <span class="bar-value">{priorityCounts.mid}</span>
        </div>
        <div class="priority-bar-row">
          <span class="bar-name"><span class="priority-dot low"></span>低</span>
          <div class="bar-track">
            <span class="bar-fill" style="width:{(priorityCounts.low / maxPriority) * 100}%;background:var(--color-primary)"></span>
          </div>
          <span class="bar-value">{priorityCounts.low}</span>
        </div>
      </div>

      <div class="stats-note">原型阶段统计数据来自内存投影；接入 Rust 后由 `get_stats` 命令返回，前端不自行合并。</div>
    </section>
  </div>
</div>

<style>
  .main-scroll {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: var(--space-4) var(--space-5) var(--space-6);
  }

  .stats-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: var(--space-4);
    padding: var(--space-2) 2px var(--space-4);
  }

  .stats-head h2 {
    margin: 0;
    font-size: var(--text-lg);
    font-weight: var(--weight-bold);
  }

  .stats-head p {
    margin: 6px 0 0;
    max-width: 580px;
    color: var(--color-text-2);
    font-size: var(--text-base);
    line-height: 1.65;
  }

  .fresh-chip {
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
    flex-shrink: 0;
  }

  .stat-cards {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: var(--space-3);
  }

  .stat-card {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    min-width: 0;
    padding: var(--space-4);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    background: var(--color-card);
    box-shadow: var(--shadow-sm);
  }

  .stat-card.accent {
    background: linear-gradient(135deg, #eff6ff 0%, #ecfdf5 100%);
    border-color: #e0e7ff;
  }

  .ring {
    width: 58px;
    height: 58px;
    flex-shrink: 0;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .ring-inner {
    width: 44px;
    height: 44px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--color-card);
    font-size: var(--text-base);
    font-weight: var(--weight-bold);
    color: var(--color-text);
  }

  .stat-icon {
    width: 34px;
    height: 34px;
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border-radius: var(--radius-sm);
  }

  .stat-icon.success {
    background: var(--color-success-50);
    color: var(--color-success);
  }

  .stat-icon.danger {
    background: #fef2f2;
    color: var(--color-danger);
  }

  .stat-icon.ai {
    background: var(--color-ai-bg);
    color: var(--color-ai-fg);
  }

  .stat-card-text {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .stat-label {
    font-size: var(--text-sm);
    color: var(--color-text-3);
  }

  .stat-card-text strong {
    font-size: var(--text-lg);
    font-weight: var(--weight-bold);
    color: var(--color-text);
    white-space: nowrap;
  }

  .stat-hint {
    font-size: var(--text-xs);
    color: var(--color-text-3);
    line-height: 1.4;
  }

  .panel-grid {
    display: grid;
    grid-template-columns: 1.15fr 1fr;
    gap: var(--space-3);
    margin-top: var(--space-4);
  }

  .chart-card {
    padding: var(--space-4);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    background: var(--color-card);
  }

  .chart-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: var(--space-3);
    margin-bottom: var(--space-4);
  }

  .chart-head h3 {
    margin: 0;
    font-size: var(--text-md);
    font-weight: var(--weight-semibold);
  }

  .chart-head span {
    font-size: var(--text-xs);
    color: var(--color-text-3);
  }

  .bar-list {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .bar-row,
  .priority-bar-row {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }

  .bar-name {
    width: 54px;
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: var(--text-sm);
    color: var(--color-text-2);
  }

  .bar-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    flex-shrink: 0;
  }

  .bar-track {
    position: relative;
    flex: 1;
    height: 8px;
    border-radius: var(--radius-full);
    background: var(--color-border-2);
    overflow: hidden;
  }

  .bar-fill {
    position: absolute;
    inset: 0 auto 0 0;
    border-radius: var(--radius-full);
    opacity: 0.45;
    transition: width var(--duration-progress) var(--ease-standard);
  }

  .bar-done {
    position: absolute;
    inset: 0 auto 0 0;
    border-radius: var(--radius-full);
    background: var(--color-success);
    transition: width var(--duration-progress) var(--ease-standard);
  }

  .bar-value {
    width: 44px;
    flex-shrink: 0;
    text-align: right;
    font-family: var(--font-mono);
    font-size: 10.5px;
    color: var(--color-text-3);
  }

  .priority-bars {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
  }

  .priority-bar-row .bar-name {
    width: 44px;
  }

  .priority-bar-row .bar-track {
    height: 10px;
  }

  .stats-note {
    margin-top: var(--space-5);
    padding: var(--space-3);
    border: 1px dashed var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-bg);
    color: var(--color-text-3);
    font-size: var(--text-sm);
    line-height: 1.65;
  }

  @media (max-width: 1080px) {
    .stat-cards {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .panel-grid {
      grid-template-columns: 1fr;
    }
  }

  :global(html[data-theme='dark']) .stat-card.accent {
    border-color: rgba(59, 130, 246, 0.25);
    background: rgba(59, 130, 246, 0.1);
  }

  :global(html[data-theme='dark']) .stat-icon.danger {
    background: rgba(239, 68, 68, 0.12);
  }
</style>
