<script lang="ts">
  import { History, Plus, Sparkles } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
  import WorkspaceSwitcher from './WorkspaceSwitcher.svelte';

  let membersExpanded = $state(false);
  let adding = $state<'list' | 'tag' | null>(null);
  let addName = $state('');
  let editing = $state<{ kind: 'list' | 'tag'; id: string } | null>(null);
  let editName = $state('');
  let menu = $state<{ kind: 'list' | 'tag'; id: string; x: number; y: number } | null>(null);
  let menuConfirm = $state(false);
  let showHiddenTags = $state(false);

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
  const hiddenTags = $derived(store.tags.filter((tag) => tag.showInSidebar !== true));

  const menuTarget = $derived.by(() => {
    if (!menu) return null;
    return menu.kind === 'list' ? store.listById(menu.id) : store.tagById(menu.id);
  });

  const menuTag = $derived.by(() =>
    menu?.kind === 'tag' ? store.tagById(menu.id) : undefined
  );

  const menuPosition = $derived.by(() => {
    if (!menu || typeof window === 'undefined') return { left: 0, top: 0 };
    return {
      left: Math.min(menu.x, window.innerWidth - 190),
      top: Math.min(menu.y, window.innerHeight - 160)
    };
  });

  function openMembers(): void {
    store.settingsSection = 'members';
    store.settingsOpen = true;
  }

  function startAdd(kind: 'list' | 'tag'): void {
    adding = kind;
    addName = '';
    menu = null;
  }

  async function submitAdd(): Promise<void> {
    const name = addName.trim();
    if (!name || !adding) {
      adding = null;
      return;
    }
    const kind = adding;
    const ok = kind === 'list' ? await store.createList(name) : await store.createTag(name);
    if (ok) {
      adding = null;
      addName = '';
    }
  }

  function startEdit(kind: 'list' | 'tag', id: string, name: string): void {
    editing = { kind, id };
    editName = name;
    menu = null;
  }

  async function submitEdit(): Promise<void> {
    if (!editing) return;
    const target = editing;
    const name = editName.trim();
    editing = null;
    if (!name) return;
    if (target.kind === 'list') {
      await store.renameList(target.id, name);
    } else {
      await store.renameTag(target.id, name);
    }
  }

  function onEditorKey(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      void (editing ? submitEdit() : submitAdd());
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      editing = null;
      adding = null;
    }
  }

  function openMenu(event: MouseEvent, kind: 'list' | 'tag', id: string): void {
    event.preventDefault();
    menu = { kind, id, x: event.clientX, y: event.clientY };
    menuConfirm = false;
  }

  function closeMenu(): void {
    menu = null;
    menuConfirm = false;
  }

  async function toggleTagVisibility(): Promise<void> {
    if (!menu || menu.kind !== 'tag') return;
    const tag = store.tagById(menu.id);
    const id = menu.id;
    closeMenu();
    await store.setTagSidebar(id, tag?.showInSidebar !== true);
  }

  async function deleteFromMenu(): Promise<void> {
    if (!menu) return;
    if (!menuConfirm) {
      menuConfirm = true;
      return;
    }
    const target = { ...menu };
    closeMenu();
    if (target.kind === 'list') {
      await store.deleteList(target.id);
    } else {
      await store.deleteTag(target.id);
    }
  }
</script>

<svelte:window
  onclick={closeMenu}
  onkeydown={(event) => event.key === 'Escape' && closeMenu()}
/>

<aside class="sidebar">
  <WorkspaceSwitcher />

  <div class="side-section-label">
    <span>清单</span>
    <button class="section-add" title="新建清单" onclick={() => startAdd('list')}>
      <Plus size="12" />
    </button>
  </div>
  {#if adding === 'list'}
    <!-- svelte-ignore a11y_autofocus -->
    <div class="inline-editor">
      <input
        class="inline-input"
        autofocus
        placeholder="新清单名称"
        bind:value={addName}
        onkeydown={onEditorKey}
        onblur={() => void submitAdd()}
      />
    </div>
  {/if}
  {#each store.lists as list (list.id)}
    {#if editing?.kind === 'list' && editing.id === list.id}
      <!-- svelte-ignore a11y_autofocus -->
      <div class="inline-editor">
        <input
          class="inline-input"
          autofocus
          bind:value={editName}
          onkeydown={onEditorKey}
          onblur={() => void submitEdit()}
        />
      </div>
    {:else}
      <button
        class="side-item"
        class:is-active={store.activeListId === list.id && !store.activeTagId && !store.globalHistory}
        onclick={() => store.setActiveList(list.id)}
        oncontextmenu={(event) => openMenu(event, 'list', list.id)}
      >
        <span class="dot" style="--dot:{list.color}"></span>
        <span class="item-name">{list.name}</span>
        <span class="count">{store.countForList(list.id)}</span>
      </button>
    {/if}
  {/each}
  <button
    class="side-item"
    class:is-active={store.activeListId === store.allListId && !store.activeTagId && !store.globalHistory}
    onclick={() => store.setActiveList(store.allListId)}
  >
    <span class="dot" style="--dot:#64748b"></span>
    <span class="item-name">全部</span>
    <span class="count">{store.countForList(store.allListId)}</span>
  </button>

  <div class="side-section-label">
    <span>标签</span>
    <button class="section-add" title="新建标签" onclick={() => startAdd('tag')}>
      <Plus size="12" />
    </button>
  </div>
  {#if adding === 'tag'}
    <!-- svelte-ignore a11y_autofocus -->
    <div class="inline-editor">
      <input
        class="inline-input"
        autofocus
        placeholder="新标签名称"
        bind:value={addName}
        onkeydown={onEditorKey}
        onblur={() => void submitAdd()}
      />
    </div>
  {/if}
  {#each store.sidebarTags as tag (tag.id)}
    {#if editing?.kind === 'tag' && editing.id === tag.id}
      <!-- svelte-ignore a11y_autofocus -->
      <div class="inline-editor">
        <input
          class="inline-input"
          autofocus
          bind:value={editName}
          onkeydown={onEditorKey}
          onblur={() => void submitEdit()}
        />
      </div>
    {:else}
      <button
        class="side-item"
        class:is-active={store.activeTagId === tag.id}
        onclick={() => store.setTagFilter(tag.id)}
        oncontextmenu={(event) => openMenu(event, 'tag', tag.id)}
      >
        <span class="dot" style="--dot:{tag.fg}"></span>
        <span class="item-name">{tag.name}</span>
        <span class="count">{store.countForTag(tag.id)}</span>
      </button>
    {/if}
  {/each}
  {#if hiddenTags.length > 0}
    <button class="hidden-tags-toggle" onclick={() => (showHiddenTags = !showHiddenTags)}>
      {showHiddenTags ? '收起已隐藏标签' : `显示 ${hiddenTags.length} 个已隐藏标签`}
    </button>
    {#if showHiddenTags}
      {#each hiddenTags as tag (tag.id)}
        <button
          class="side-item is-muted"
          onclick={() => store.setTagFilter(tag.id)}
          oncontextmenu={(event) => openMenu(event, 'tag', tag.id)}
        >
          <span class="dot" style="--dot:{tag.fg}"></span>
          <span class="item-name">{tag.name}</span>
          <span class="count">隐藏</span>
        </button>
      {/each}
    {/if}
  {/if}

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

  {#if menu && menuTarget}
    {@const activeMenu = menu}
    <div
      class="side-menu"
      style="left:{menuPosition.left}px;top:{menuPosition.top}px"
      role="menu"
      tabindex="-1"
      onclick={(event) => event.stopPropagation()}
      onkeydown={(event) => event.stopPropagation()}
    >
      <div class="side-menu-title">{menuTarget.name}</div>
      <button
        class="side-menu-item"
        role="menuitem"
        onclick={() => startEdit(activeMenu.kind, activeMenu.id, menuTarget.name)}
      >
        重命名
      </button>
      {#if activeMenu.kind === 'tag'}
        <button class="side-menu-item" role="menuitem" onclick={toggleTagVisibility}>
          {menuTag?.showInSidebar === true ? '从侧栏隐藏' : '显示于侧栏'}
        </button>
      {/if}
      <div class="side-menu-divider"></div>
      <button class="side-menu-item danger" role="menuitem" onclick={deleteFromMenu}>
        {menuConfirm ? '再次点击确认删除' : '删除'}
      </button>
    </div>
  {/if}
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
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;
    padding: 10px 10px 4px;
    font-size: 10.5px;
    font-weight: var(--weight-semibold);
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--color-text-3);
    white-space: nowrap;
  }

  .section-add {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border: none;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--color-text-3);
    cursor: pointer;
    opacity: 0.65;
  }

  .section-add:hover {
    background: var(--color-overlay);
    color: var(--color-text);
    opacity: 1;
  }

  .inline-editor {
    padding: 1px 10px 3px;
  }

  .inline-input {
    width: 100%;
    padding: 5px 8px;
    border: 1px solid var(--color-primary);
    border-radius: var(--radius-sm);
    background: var(--color-card);
    color: var(--color-text);
    font-size: var(--text-base);
    outline: none;
  }

  .hidden-tags-toggle {
    margin: 2px 10px;
    padding: 2px 0;
    border: none;
    background: transparent;
    color: var(--color-text-3);
    font-size: 11px;
    text-align: left;
    cursor: pointer;
  }

  .hidden-tags-toggle:hover {
    color: var(--color-text-2);
  }

  .side-item.is-muted {
    opacity: 0.55;
  }

  .side-menu {
    position: fixed;
    z-index: var(--z-popover);
    width: 180px;
    padding: 5px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-card);
    box-shadow: var(--shadow-lg);
    animation: menu-in var(--duration-fast) var(--ease-out);
  }

  .side-menu-title {
    padding: 6px 8px;
    color: var(--color-text-3);
    font-size: 10.5px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .side-menu-item {
    display: flex;
    align-items: center;
    width: 100%;
    padding: 6px 8px;
    border: none;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--color-text-2);
    font-size: var(--text-sm);
    text-align: left;
    cursor: pointer;
  }

  .side-menu-item:hover {
    background: var(--color-overlay);
    color: var(--color-text);
  }

  .side-menu-item.danger {
    color: #dc2626;
  }

  .side-menu-item.danger:hover {
    background: #fef2f2;
  }

  .side-menu-divider {
    height: 1px;
    margin: 4px 6px;
    background: var(--color-border-2);
  }

  @keyframes menu-in {
    from {
      opacity: 0;
      transform: translateY(-2px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
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
