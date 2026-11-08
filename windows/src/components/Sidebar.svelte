<script lang="ts">
  import { History, Sparkles } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
  import WorkspaceSwitcher from './WorkspaceSwitcher.svelte';

  let membersExpanded = $state(false);

  const roleLabels: Record<string, string> = {
    owner: '所有者',
    editor: '可编辑',
    viewer: '只读'
  };

  const visibleMembers = $derived(
    membersExpanded ? store.members : store.members.slice(0, 5)
  );
  const otherMembers = $derived(
    store.members.filter((member) => member.id !== store.currentUserId)
  );

  function openMembers(): void {
    store.settingsSection = 'members';
    store.settingsOpen = true;
  }
</script>

<aside class="sidebar">
  <WorkspaceSwitcher />

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
  {#if otherMembers.length === 0}
    <div class="member-empty">
      <span>还没有其他协作成员</span>
      <button onclick={openMembers}>邀请成员</button>
    </div>
  {:else}
    {#each visibleMembers as member (member.id)}
      <button
        class="member-row"
        title="{member.name} · {member.email}"
        onclick={openMembers}
      >
        <span class="member-avatar" style="background:{member.color}">{member.initial}</span>
        <span class="member-name">
          {member.name}
          {#if member.id === store.currentUserId && member.name !== '我'}<span class="me-tag">我</span>{/if}
        </span>
        <span
          class="role-badge"
          class:owner={member.role === 'owner'}
          class:editor={member.role === 'editor'}
          class:viewer={member.role === 'viewer'}
        >
          {roleLabels[member.role] ?? member.role}
        </span>
      </button>
    {/each}
    {#if store.members.length > 5}
      <button class="member-more" onclick={() => (membersExpanded = !membersExpanded)}>
        {membersExpanded ? '收起成员' : `查看全部 ${store.members.length} 人`}
      </button>
    {/if}
  {/if}

  <button class="ai-entry" onclick={() => (store.aiOpen = true)}>
    <Sparkles size="14" class="spark" />
    <span>AI 助手</span>
    <span class="ai-hint">本地运行</span>
  </button>
</aside>

<style>
  .sidebar {
    width: var(--sidebar-w, 180px);
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
    width: 100%;
    padding: 4px 10px;
    border: none;
    border-radius: var(--radius-sm);
    background: transparent;
    font-size: var(--text-base);
    color: var(--color-text-2);
    text-align: left;
    white-space: nowrap;
  }

  .member-row:hover {
    background: var(--color-overlay);
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
    background: var(--color-warning-50);
    color: var(--color-warning-fg);
  }

  .role-badge.editor {
    background: var(--color-primary-50);
    color: var(--color-primary);
  }

  .role-badge.viewer {
    background: var(--color-border-2);
    color: var(--color-text-3);
  }

  .me-tag {
    margin-left: 4px;
    padding: 0 5px;
    border-radius: var(--radius-full);
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-size: 9px;
  }

  .member-empty {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 6px 10px;
    font-size: var(--text-sm);
    color: var(--color-text-3);
  }

  .member-empty button {
    align-self: flex-start;
    padding: 3px 9px;
    border: 1px dashed var(--color-border);
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--color-primary);
    font-size: var(--text-sm);
  }

  .member-more {
    align-self: flex-start;
    margin: 2px 0 0 10px;
    padding: 2px 0;
    border: none;
    background: transparent;
    color: var(--color-primary);
    font-size: var(--text-sm);
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
