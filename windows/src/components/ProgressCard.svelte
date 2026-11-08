<script lang="ts">
  import { store } from '../lib/store.svelte';

  const progress = $derived(store.progress);
</script>

<div class="progress-card">
  <div class="progress-card-top">
    <div class="progress-title">
      <strong>今日进度</strong>
      <span class="scope">{store.activeTagId ? `#${store.tagName(store.activeTagId)}` : store.activeListName}</span>
    </div>
    <span class="progress-value">{progress.done} / {progress.total} 完成 · {progress.pct}%</span>
  </div>
  <div class="progress-bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow={progress.pct}>
    <div class="progress-fill" style="width:{progress.pct}%"></div>
  </div>
</div>

<style>
  .progress-card {
    padding: 14px var(--space-4);
    margin-bottom: 18px;
    border: 1px solid #e0e7ff;
    border-radius: var(--radius-md);
    background: linear-gradient(135deg, #eff6ff 0%, #ecfdf5 100%);
  }

  .progress-card-top {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: var(--space-3);
    margin-bottom: 10px;
  }

  .progress-title {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-width: 0;
  }

  .progress-title strong {
    font-size: var(--text-md);
    font-weight: var(--weight-semibold);
    color: var(--color-text);
  }

  .scope {
    padding: 1px 7px;
    border-radius: var(--radius-full);
    background: rgba(255, 255, 255, 0.72);
    color: var(--color-text-2);
    font-size: var(--text-xs);
    font-weight: var(--weight-medium);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .progress-value {
    flex-shrink: 0;
    font-size: var(--text-base);
    font-weight: var(--weight-semibold);
    color: var(--color-text-2);
  }

  :global(html[data-theme='dark']) .progress-card {
    border-color: rgba(59, 130, 246, 0.25);
    background: rgba(59, 130, 246, 0.1);
  }

  :global(html[data-theme='dark']) .scope {
    background: rgba(15, 23, 42, 0.3);
  }
</style>
