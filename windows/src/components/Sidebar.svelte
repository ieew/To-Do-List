<script lang="ts">
  import { ChevronDown, History, Sparkles } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
</script>

<aside class="sidebar">
  <button class="workspace-switch" title="切换工作区">
    <span class="workspace-avatar">我</span>
    <span class="workspace-name">我的空间</span>
    <ChevronDown size="12" class="workspace-caret" />
  </button>

  <div class="side-section-label">清单</div>
  {#each store.lists as list (list.id)}
    <button
      class="side-item"
      class:is-active={store.activeListId === list.id && !store.globalHistory}
      onclick={() => store.setActiveList(list.id)}
    >
      <span class="dot" style="--dot:{list.color}"></span>
      <span class="item-name">{list.name}</span>
      <span class="count">{store.countForList(list.id)}</span>
    </button>
  {/each}

  <div class="side-section-label">标签</div>
  {#each store.sidebarTags as tag (tag.id)}
    <button
      class="side-item"
      class:is-active={store.activeTagId === tag.id}
      onclick={() => store.setTagFilter(tag.id)}
    >
      <span class="dot" style="--dot:{tag.fg}"></span>
      <span class="item-name">{tag.name}</span>
      <span class="count">{store.countForTag(tag.id)}</span>
    </button>
  {/each}

  <div class="side-divider"></div>

  <button class="side-item" class:is-active={store.globalHistory} onclick={() => store.showGlobalHistory()}>
    <History size="13" class="side-icon" />
    <span class="item-name">历史</span>
    <span class="count">全部</span>
  </button>

  <div class="side-divider"></div>

  <div class="side-section-label">成员 · {store.members.length}</div>
  {#each store.members as member (member.id)}
    <div class="member-row" title="{member.name} · {member.email}">
      <span class="member-avatar" style="background:{member.color}">{member.initial}</span>
      <span class="member-name">{member.name}</span>
      <span class="role-badge" class:owner={member.role === 'owner'} class:editor={member.role === 'editor'}>
        {member.role}
      </span>
    </div>
  {/each}

  <button class="ai-entry" onclick={() => (store.aiOpen = true)}>
    <Sparkles size="14" class="spark" />
    <span>AI 助手</span>
    <span class="ai-hint">本地运行</span>
  </button>
</aside>

<style>
  .sidebar {
    width: var(--sidebar-w, 198px);
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: var(--space-4) var(--space-3);
    background: var(--color-sidebar);
    border-right: 1px solid var(--color-border);
    overflow-y: auto;
    overflow-x: hidden;
    transition: width var(--duration-fast) var(--ease-out);
  }

  .workspace-switch {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) 10px;
    margin-bottom: var(--space-3);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-card);
    text-align: left;
    min-width: 0;
  }

  .workspace-avatar {
    width: 24px;
    height: 24px;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 7px;
    background: linear-gradient(135deg, var(--color-primary), var(--color-purple));
    color: #fff;
    font-size: var(--text-sm);
    font-weight: var(--weight-bold);
  }

  .workspace-name {
    flex: 1;
    min-width: 0;
    font-size: var(--text-base);
    font-weight: var(--weight-semibold);
    color: var(--color-text);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .side-section-label {
    padding: 10px 10px 4px;
    font-size: 10.5px;
    font-weight: var(--weight-semibold);
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--color-text-3);
    white-space: nowrap;
  }

  .side-item {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: 7px 10px;
    border: none;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--color-text-2);
    font-size: var(--text-base);
    text-align: left;
    white-space: nowrap;
    transition:
      background var(--duration-fast) var(--ease-out),
      color var(--duration-fast) var(--ease-out);
  }

  .side-item:hover {
    background: #eef2f7;
  }

  .side-item.is-active {
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-weight: var(--weight-semibold);
  }

  .item-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .dot {
    width: 8px;
    height: 8px;
    flex-shrink: 0;
    border-radius: 50%;
    background: var(--dot, var(--color-primary));
  }

  .count {
    flex-shrink: 0;
    padding: 0 6px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-full);
    background: var(--color-card);
    color: var(--color-text-3);
    font-size: 10.5px;
    line-height: 16px;
  }

  .side-divider {
    height: 1px;
    flex-shrink: 0;
    margin: 10px;
    background: var(--color-border-2);
  }

  .member-row {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: 4px 10px;
    font-size: var(--text-base);
    color: var(--color-text-2);
    white-space: nowrap;
  }

  .member-avatar {
    width: 22px;
    height: 22px;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    color: #fff;
    font-size: var(--text-xs);
    font-weight: var(--weight-semibold);
  }

  .member-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .role-badge {
    flex-shrink: 0;
    padding: 1px 6px;
    border-radius: var(--radius-full);
    background: var(--color-border-2);
    color: var(--color-text-2);
    font-size: 9.5px;
    font-weight: var(--weight-medium);
  }

  .role-badge.owner {
    background: #fef3c7;
    color: #b45309;
  }

  .role-badge.editor {
    background: var(--color-primary-50);
    color: var(--color-primary);
  }

  .ai-entry {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin-top: auto;
    padding: 9px 12px;
    border: none;
    border-radius: var(--radius-md);
    background: var(--color-ai-bg);
    color: #5b21b6;
    font-size: var(--text-base);
    font-weight: var(--weight-semibold);
    text-align: left;
    white-space: nowrap;
  }

  .ai-hint {
    margin-left: auto;
    font-size: var(--text-xs);
    opacity: 0.72;
  }

  :global(html[data-theme='dark']) .side-item:hover {
    background: var(--color-card);
  }

  :global(html[data-theme='dark']) .side-item.is-active {
    background: var(--color-primary-50);
    color: #60a5fa;
  }

  :global(html[data-theme='dark']) .role-badge.owner {
    background: rgba(245, 158, 11, 0.16);
    color: #fcd34d;
  }

  :global(html[data-theme='dark']) .ai-entry {
    background: rgba(139, 92, 246, 0.16);
    color: #ddd6fe;
    box-shadow: inset 0 0 0 1px rgba(139, 92, 246, 0.24);
  }
</style>
