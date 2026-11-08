<script lang="ts">
  import { Search, Plus, X } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
</script>

<div class="main-toolbar">
  <label class="search-box" class:has-value={!!store.search}>
    <Search size="14" class="search-icon" />
    <input id="search-input" placeholder="搜索任务…" bind:value={store.search} spellcheck="false" />
    {#if store.search}
      <button class="clear-btn" aria-label="清除搜索" onclick={() => (store.search = '')}>
        <X size="12" />
      </button>
    {:else}
      <span class="search-hint">Ctrl F</span>
    {/if}
  </label>

  <button class="btn btn-primary" title="新建任务 (Ctrl+N)" onclick={() => (store.quickAddOpen = true)}>
    <Plus size="15" />
    <span>新建</span>
  </button>

  <div class="segmented view-toggle" role="tablist" aria-label="视图切换">
    <button class:is-active={store.view === 'today'} role="tab" aria-selected={store.view === 'today'} onclick={() => (store.view = 'today')}>
      今天
    </button>
    <button class:is-active={store.view === 'calendar'} role="tab" aria-selected={store.view === 'calendar'} onclick={() => (store.view = 'calendar')}>
      日历
    </button>
    <button class:is-active={store.view === 'stats'} role="tab" aria-selected={store.view === 'stats'} onclick={() => (store.view = 'stats')}>
      统计
    </button>
  </div>
</div>

<style>
  .main-toolbar {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 14px var(--space-5);
    border-bottom: 1px solid var(--color-border-2);
    background: var(--color-card);
  }

  .search-box {
    flex: 1;
    min-width: 60px;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    height: 32px;
    padding: 0 var(--space-3);
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    background: var(--color-border-2);
    color: var(--color-text-3);
    transition:
      border-color var(--duration-fast) var(--ease-out),
      box-shadow var(--duration-fast) var(--ease-out),
      background var(--duration-fast) var(--ease-out);
  }

  .search-box:focus-within {
    border-color: var(--color-primary);
    background: var(--color-card);
    box-shadow: 0 0 0 3px var(--color-primary-50);
  }

  .search-box input {
    flex: 1;
    min-width: 0;
    border: none;
    outline: none;
    background: transparent;
    color: var(--color-text);
    font-size: var(--text-base);
  }

  .search-box input::placeholder {
    color: var(--color-text-3);
  }

  .search-hint {
    flex-shrink: 0;
    font-family: var(--font-mono);
    font-size: 9.5px;
    color: var(--color-text-3);
    border: 1px solid var(--color-border);
    border-radius: 4px;
    padding: 0 4px;
    line-height: 15px;
    opacity: 0.9;
  }

  .clear-btn {
    flex-shrink: 0;
    width: 20px;
    height: 20px;
    padding: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: none;
    border-radius: 50%;
    background: var(--color-border);
    color: var(--color-text-2);
  }

  .clear-btn:hover {
    background: var(--color-text-3);
    color: #fff;
  }

  .view-toggle {
    flex-shrink: 0;
  }

  @media (max-width: 1040px) {
    .search-hint {
      display: none;
    }
  }
</style>
