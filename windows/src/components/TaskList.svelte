<script lang="ts">
  import { CheckSquare, ChevronDown, Plus, Search } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
  import TaskRow from './TaskRow.svelte';

  const groups = $derived(store.groups);
  const searchActive = $derived(store.search.trim().length > 0);
  const emptySearch = $derived(
    searchActive && groups.length === 1 && groups[0].key === 'search' && groups[0].tasks.length === 0
  );
  const emptyScope = $derived(!searchActive && !store.hasScopedTasks);
  const emptyText = $derived(
    store.activeTagId
      ? '这个标签还没有任务'
      : store.activeListId === store.allListId
        ? '还没有未完成的任务'
        : store.activeListId === store.todayListId
          ? '今天没有进行中的任务'
          : '这个清单还没有任务'
  );
  const resultCount = $derived(groups.reduce((sum, group) => sum + group.tasks.length, 0));
</script>

{#if store.loading}
  <div class="skeleton-list" aria-label="正在加载任务">
    {#each [0, 1, 2] as row (row)}
      <div class="skeleton-row">
        <div class="skeleton skeleton-check"></div>
        <div class="skeleton-lines">
          <div class="skeleton skeleton-line" style="width:{72 - row * 9}%"></div>
          <div class="skeleton skeleton-line-sm"></div>
        </div>
      </div>
    {/each}
  </div>
{:else if emptySearch}
  <div class="empty-state">
    <Search size="24" class="empty-icon" />
    <strong>没有找到匹配的任务</strong>
    <button class="empty-action" onclick={() => (store.search = '')}>清除搜索</button>
  </div>
{:else if emptyScope}
  <div class="empty-state">
    <CheckSquare size="24" class="empty-icon" />
    <strong>{emptyText}</strong>
    <button class="empty-action" onclick={() => (store.quickAddOpen = true)}>＋ 添加一个</button>
  </div>
{:else}
  {#if searchActive}
    <div class="search-summary" aria-live="polite">
      在全部任务中搜索「{store.search.trim()}」 · {resultCount} 条结果
    </div>
  {/if}

  {#each groups as group (group.key)}
    <section class="task-group">
      <div class="group-title">
        {#if group.key !== 'search'}
          <button
            aria-expanded={!store.collapsedGroups[group.key]}
            onclick={() => store.toggleGroup(group.key)}
          >
            <span class="chev" class:collapsed={store.collapsedGroups[group.key]}>
              <ChevronDown size="13" />
            </span>
            {group.title}
          </button>
          <span class="count">
            {group.key === 'done' ? `${group.tasks.length} 项` : `${group.tasks.length} 项待办`}
          </span>
        {:else}
          <span class="search-group-title">{group.title}</span>
          <span class="count">{group.tasks.length} 条</span>
        {/if}
      </div>

      {#if group.key === 'search' || !store.collapsedGroups[group.key]}
        {#each group.tasks as task (task.id)}
          <TaskRow
            {task}
            active={store.selectedTaskId === task.id && store.drawerOpen}
            onopen={(t) => store.openTask(t.id)}
            ontoggle={(t) => store.toggleTask(t.id)}
            oncontext={(event, t) => store.openContextMenu(event.clientX, event.clientY, t.id)}
            onreorder={(draggedId, targetId) => store.reorderTask(draggedId, targetId)}
          />
        {/each}
      {/if}
    </section>
  {/each}

  {#if !searchActive}
    <button class="quick-add" onclick={() => (store.quickAddOpen = true)}>
      <Plus size="14" />
      <span>添加一个新待办…</span>
    </button>
  {/if}
{/if}

<style>
  .skeleton-list {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding-top: var(--space-2);
  }

  .skeleton-row {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    padding: 10px var(--space-3);
  }

  .skeleton-check {
    width: 20px;
    height: 20px;
    border-radius: 50%;
    flex-shrink: 0;
  }

  .skeleton-lines {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding-top: 2px;
  }

  .skeleton-line {
    height: 12px;
  }

  .skeleton-line-sm {
    height: 9px;
    width: 38%;
    border-radius: 6px;
    background: linear-gradient(
      90deg,
      var(--color-border-2) 0%,
      var(--color-border) 50%,
      var(--color-border-2) 100%
    );
    background-size: 200% 100%;
    animation: skeleton-pulse 1.2s ease-in-out infinite;
  }

  .search-summary {
    padding: 2px 2px 8px;
    font-size: var(--text-base);
    color: var(--color-text-2);
  }

  .search-group-title {
    font-size: var(--text-base);
    font-weight: var(--weight-semibold);
    color: var(--color-text-2);
  }

  .quick-add {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    width: 100%;
    margin-top: var(--space-3);
    padding: 10px var(--space-3);
    border: 1px dashed var(--color-border);
    border-radius: var(--radius-md);
    background: transparent;
    color: var(--color-text-3);
    font-size: var(--text-base);
    text-align: left;
    transition:
      border-color var(--duration-fast) var(--ease-out),
      color var(--duration-fast) var(--ease-out),
      background var(--duration-fast) var(--ease-out);
  }

  .quick-add:hover {
    border-color: var(--color-primary);
    background: var(--color-primary-50);
    color: var(--color-primary);
  }
</style>
