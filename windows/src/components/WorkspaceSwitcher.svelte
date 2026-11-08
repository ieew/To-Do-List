<script lang="ts">
  import { Check, ChevronDown, Plus, Users } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';

  let open = $state(false);
  let creating = $state(false);
  let newName = $state('');
  let busy = $state(false);
  let nameInput = $state<HTMLInputElement | null>(null);

  function startCreating(): void {
    creating = true;
    setTimeout(() => nameInput?.focus(), 0);
  }

  const activeName = $derived(store.activeWorkspace?.name ?? '工作区');

  function toggle(): void {
    open = !open;
    if (!open) {
      creating = false;
      newName = '';
    }
  }

  async function select(id: string): Promise<void> {
    open = false;
    await store.setActiveWorkspace(id);
  }

  async function create(): Promise<void> {
    if (!newName.trim() || busy) return;
    busy = true;
    const created = await store.createWorkspace(newName);
    busy = false;
    if (created) {
      creating = false;
      newName = '';
      open = false;
    }
  }

  function manageMembers(): void {
    open = false;
    store.settingsSection = 'members';
    store.settingsOpen = true;
  }
</script>

<div class="ws-switch">
  <button
    class="ws-btn"
    onclick={toggle}
    aria-haspopup="menu"
    aria-expanded={open}
    title="切换工作区"
  >
    <span class="ws-avatar">{activeName.slice(0, 1)}</span>
    <span class="ws-name">{activeName}</span>
    <ChevronDown size="12" />
  </button>

  {#if open}
    <button class="ws-backdrop" aria-label="关闭工作区菜单" onclick={() => (open = false)}></button>
    <div class="ws-pop" role="menu" aria-label="工作区">
      <div class="ws-pop-label">切换工作区</div>
      {#each store.workspaces as workspace (workspace.id)}
        <button
          class="ws-item"
          class:is-active={workspace.id === store.activeWorkspaceId}
          role="menuitemradio"
          aria-checked={workspace.id === store.activeWorkspaceId}
          onclick={() => select(workspace.id)}
        >
          <span class="ws-dot"></span>
          <span class="ws-item-name">{workspace.name}</span>
          {#if workspace.id === store.activeWorkspaceId}
            <Check size="12" />
          {/if}
        </button>
      {/each}

      {#if creating}
        <form
          class="ws-create"
          onsubmit={(event) => {
            event.preventDefault();
            void create();
          }}
        >
          <input
            class="ws-input"
            placeholder="工作区名称"
            bind:value={newName}
            bind:this={nameInput}
            maxlength="24"
          />
          <div class="ws-create-actions">
            <button type="button" class="ws-mini" onclick={() => (creating = false)}>取消</button>
            <button type="submit" class="ws-mini primary" disabled={!newName.trim() || busy}>
              创建
            </button>
          </div>
        </form>
      {:else}
        <button class="ws-action" role="menuitem" onclick={startCreating}>
          <Plus size="13" />
          <span>新建工作区</span>
        </button>
      {/if}

      <div class="ws-divider"></div>
      <button class="ws-action" role="menuitem" onclick={manageMembers}>
        <Users size="13" />
        <span>管理成员 · {store.members.length}</span>
      </button>
    </div>
  {/if}
</div>

<style>
  .ws-switch {
    position: relative;
    margin-bottom: var(--space-3);
  }

  .ws-btn {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    width: 100%;
    padding: var(--space-2) 10px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-card);
    text-align: left;
    min-width: 0;
  }

  .ws-btn:hover {
    border-color: var(--color-primary-100);
  }

  .ws-avatar {
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

  .ws-name {
    flex: 1;
    min-width: 0;
    font-size: var(--text-base);
    font-weight: var(--weight-semibold);
    color: var(--color-text);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .ws-backdrop {
    position: fixed;
    inset: 0;
    z-index: var(--z-popover);
    border: none;
    background: transparent;
    cursor: default;
  }

  .ws-pop {
    position: absolute;
    top: calc(100% + 4px);
    left: 0;
    right: 0;
    z-index: calc(var(--z-popover) + 1);
    padding: var(--space-2);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    background: var(--color-card);
    box-shadow: var(--shadow-lg);
  }

  .ws-pop-label {
    padding: 2px 8px 6px;
    font-size: 10.5px;
    font-weight: var(--weight-semibold);
    letter-spacing: 0.06em;
    color: var(--color-text-3);
    text-transform: uppercase;
  }

  .ws-item,
  .ws-action {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    width: 100%;
    padding: 7px 8px;
    border: none;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--color-text-2);
    font-size: var(--text-base);
    text-align: left;
  }

  .ws-item:hover,
  .ws-action:hover {
    background: var(--color-overlay);
  }

  .ws-item.is-active {
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-weight: var(--weight-semibold);
  }

  .ws-dot {
    width: 7px;
    height: 7px;
    flex-shrink: 0;
    border-radius: 50%;
    background: currentColor;
    opacity: 0.7;
  }

  .ws-item-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .ws-create {
    padding: 4px 2px;
  }

  .ws-input {
    width: 100%;
    padding: 6px 8px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-card);
    color: var(--color-text);
    font-size: var(--text-base);
  }

  .ws-input:focus-visible {
    outline: 2px solid var(--color-primary-50);
    border-color: var(--color-primary);
  }

  .ws-create-actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-2);
    margin-top: var(--space-2);
  }

  .ws-mini {
    padding: 4px 10px;
    border: 1px solid var(--color-border);
    border-radius: 6px;
    background: var(--color-card);
    color: var(--color-text-2);
    font-size: var(--text-sm);
  }

  .ws-mini.primary {
    border-color: var(--color-primary);
    background: var(--color-primary);
    color: #fff;
  }

  .ws-mini:disabled {
    opacity: 0.5;
  }

  .ws-divider {
    height: 1px;
    margin: var(--space-1) 4px;
    background: var(--color-border-2);
  }
</style>
