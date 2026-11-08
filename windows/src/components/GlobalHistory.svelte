<script lang="ts">
  import { ArrowLeft, History } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
  import HistoryTimeline from './HistoryTimeline.svelte';
</script>

<div class="history-view">
  <div class="history-head">
    <button class="back-btn" onclick={() => (store.globalHistory = false)}>
      <ArrowLeft size="14" />
      <span>任务列表</span>
    </button>
    <div class="history-title">
      <History size="16" />
      <h2>全局历史</h2>
      <span class="history-count">{store.history.length} 条 op</span>
    </div>
  </div>

  <div class="history-scroll">
    <HistoryTimeline ops={store.history} showTask />
  </div>
</div>

<style>
  .history-view {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    background: var(--color-card);
  }

  .history-head {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: 14px var(--space-5);
    border-bottom: 1px solid var(--color-border-2);
  }

  .back-btn {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    height: 28px;
    padding: 0 9px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-card);
    color: var(--color-text-2);
    font-size: var(--text-base);
    font-weight: var(--weight-medium);
    flex-shrink: 0;
  }

  .back-btn:hover {
    border-color: #bfdbfe;
    color: var(--color-primary);
    background: var(--color-primary-50);
  }

  .history-title {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
    color: var(--color-text-2);
  }

  .history-title h2 {
    margin: 0;
    font-size: var(--text-lg);
    font-weight: var(--weight-bold);
    color: var(--color-text);
  }

  .history-count {
    padding: 1px 8px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-full);
    background: var(--color-border-2);
    color: var(--color-text-3);
    font-size: var(--text-xs);
    white-space: nowrap;
  }

  .history-scroll {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: var(--space-4) var(--space-5) var(--space-6);
  }
</style>
