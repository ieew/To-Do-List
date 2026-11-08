<script lang="ts">
  import { History, Sparkles } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
  import type { HistoryOp } from '../lib/types';

  interface Props {
    ops: HistoryOp[];
    showTask?: boolean;
  }

  let { ops, showTask = false }: Props = $props();

  type Filter = 'all' | 'ai' | 'mine';
  let filter = $state<Filter>('all');

  const filtered = $derived(
    ops.filter((op) => {
      if (filter === 'ai') return op.source === 'ai';
      if (filter === 'mine') return op.source === 'human' && op.actor === '我';
      return true;
    })
  );
</script>

<div class="history-header">
  <span>{showTask ? '全部任务的时间线' : '此任务的全部变更'}</span>
  <div class="history-filters" role="tablist" aria-label="历史筛选">
    <button class:is-active={filter === 'all'} role="tab" aria-selected={filter === 'all'} onclick={() => (filter = 'all')}>
      全部
    </button>
    <button class:is-active={filter === 'ai'} role="tab" aria-selected={filter === 'ai'} onclick={() => (filter = 'ai')}>
      仅 AI
    </button>
    <button class:is-active={filter === 'mine'} role="tab" aria-selected={filter === 'mine'} onclick={() => (filter = 'mine')}>
      仅我
    </button>
  </div>
</div>

<div class="history-note">
  日志只追加：历史记录不可删除，只能以更高 HLC 的反向 op 撤销。AI 操作与手动操作在数据层等价。
</div>

{#if filtered.length === 0}
  <div class="empty-state history-empty">
    <History size="24" class="empty-icon" />
    <strong>{showTask ? '还没有任何变更记录' : '这条任务还没有变更记录'}</strong>
  </div>
{:else}
  <ol class="history-list">
    {#each filtered as op (op.id)}
      <li class="history-item" class:ai={op.source === 'ai'} class:system={op.system} aria-label={op.source === 'ai' ? 'AI 操作' : undefined}>
        <span class="history-dot"></span>
        <div class="history-top">
          <span class="history-time">{op.timeLabel}</span>
          {#if op.source === 'ai'}
            <span class="ai-badge"><Sparkles size="10" />AI</span>
          {:else}
            <span class="history-actor">{op.actor}</span>
          {/if}
          {#if showTask}
            <button class="task-link" title="打开任务详情" onclick={() => store.openTask(op.taskId, 'detail')}>
              {op.taskTitle}
            </button>
          {/if}
        </div>

        <div class="history-action">{op.action}</div>

        {#if op.diff}
          <div class="history-diff">
            {#if op.diff.kind === 'snapshot'}
              <span class="diff-meta">{op.diff.text}</span>
            {:else if op.diff.kind === 'change'}
              <span class="diff-key">{op.diff.text}</span><span class="diff-old">{op.diff.oldText}</span>
              <span class="diff-arrow">→</span><span class="diff-new">{op.diff.newText}</span>
            {:else if op.diff.kind === 'add'}
              <span class="diff-key">{op.diff.text}</span><span class="diff-new">{op.diff.newText}</span>
            {:else if op.diff.kind === 'remove'}
              <span class="diff-key">{op.diff.text}</span><span class="diff-remove">{op.diff.newText}</span>
            {:else}
              <span class="diff-key">{op.diff.text}</span><span class="diff-new">{op.diff.newText}</span>
            {/if}
          </div>
        {/if}

        <div class="history-meta">
          {op.id} · source={op.source}{#if op.model} · model: {op.model}{/if}{#if op.undone} · 已撤销{/if}
        </div>

        {#if op.undoable}
          <button class="history-undo" disabled={op.undone} onclick={() => store.undoOp(op)}>
            {op.undone ? '已生成撤销 op' : '撤销此操作'}
          </button>
        {/if}
      </li>
    {/each}
  </ol>
{/if}

<style>
  .history-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
    font-size: 11.5px;
    color: var(--color-text-3);
  }

  .history-filters {
    display: flex;
    gap: 4px;
    flex-shrink: 0;
  }

  .history-filters button {
    padding: 2px 8px;
    border: none;
    border-radius: var(--radius-full);
    background: var(--color-border-2);
    color: var(--color-text-2);
    font-size: 10.5px;
    font-weight: var(--weight-medium);
    white-space: nowrap;
  }

  .history-filters button.is-active {
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-weight: var(--weight-semibold);
  }

  .history-note {
    margin-top: var(--space-2);
    padding: var(--space-2) 10px;
    border: 1px dashed var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-bg);
    color: var(--color-text-3);
    font-size: 10.5px;
    line-height: 1.6;
  }

  .history-list {
    position: relative;
    display: flex;
    flex-direction: column;
    margin: var(--space-4) 0 0;
    padding: 0 0 0 4px;
    list-style: none;
  }

  .history-item {
    position: relative;
    margin-left: 5px;
    padding: 0 0 var(--space-5) 22px;
    border-left: 1.5px solid var(--color-border);
  }

  .history-item:last-child {
    padding-bottom: 0;
    border-left-color: transparent;
  }

  .history-dot {
    position: absolute;
    top: 1px;
    left: -6px;
    width: 11px;
    height: 11px;
    border: 2px solid var(--color-primary);
    border-radius: 50%;
    background: var(--color-card);
  }

  .history-item.ai .history-dot {
    border-color: var(--color-purple);
    background: #f5f3ff;
  }

  .history-item.system .history-dot {
    border-color: var(--color-text-3);
    background: var(--color-bg);
  }

  .history-top {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 3px;
  }

  .history-time {
    font-size: 10.5px;
    font-weight: var(--weight-medium);
    color: var(--color-text-3);
  }

  .history-actor {
    font-size: 10.5px;
    font-weight: var(--weight-semibold);
    color: var(--color-text-2);
  }

  .task-link {
    max-width: 150px;
    padding: 0 6px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-full);
    background: var(--color-card);
    color: var(--color-text-2);
    font-size: 10px;
    line-height: 15px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .task-link:hover {
    border-color: #bfdbfe;
    color: var(--color-primary);
  }

  .history-action {
    font-size: var(--text-base);
    font-weight: var(--weight-medium);
    color: var(--color-text);
  }

  .history-diff {
    display: inline-block;
    max-width: 100%;
    margin-top: 4px;
    padding: 3px 8px;
    border: 1px solid var(--color-border-2);
    border-radius: 6px;
    background: var(--color-bg);
    color: var(--color-text-2);
    font-family: var(--font-mono);
    font-size: var(--text-sm);
    line-height: 1.5;
    overflow-wrap: anywhere;
  }

  .diff-key,
  .diff-meta {
    color: var(--color-text-3);
  }

  .diff-old {
    color: var(--color-text-3);
    text-decoration: line-through;
  }

  .diff-new {
    color: var(--color-success);
    font-weight: var(--weight-semibold);
  }

  .diff-remove {
    color: var(--color-danger);
    font-weight: var(--weight-semibold);
  }

  .diff-arrow {
    margin: 0 3px;
    color: var(--color-text-3);
  }

  .history-meta {
    margin-top: 4px;
    font-family: var(--font-mono);
    font-size: 9.5px;
    color: var(--color-text-3);
    overflow-wrap: anywhere;
  }

  .history-undo {
    margin-top: 7px;
    padding: 3px 10px;
    border: 1px solid var(--color-border);
    border-radius: 6px;
    background: var(--color-card);
    color: var(--color-text-2);
    font-size: 10.5px;
    font-weight: var(--weight-medium);
    transition:
      color var(--duration-fast) var(--ease-out),
      border-color var(--duration-fast) var(--ease-out),
      background var(--duration-fast) var(--ease-out);
  }

  .history-undo:hover {
    border-color: #fca5a5;
    background: #fef2f2;
    color: var(--color-danger);
  }

  .history-undo:disabled {
    border-color: var(--color-border-2);
    background: var(--color-bg);
    color: var(--color-text-3);
    cursor: default;
  }

  .history-empty {
    padding-top: var(--space-6);
  }

  :global(html[data-theme='dark']) .history-item.ai .history-dot {
    background: rgba(139, 92, 246, 0.2);
  }

  :global(html[data-theme='dark']) .history-undo:hover {
    background: rgba(239, 68, 68, 0.12);
  }
</style>
