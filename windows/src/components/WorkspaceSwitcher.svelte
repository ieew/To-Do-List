<script lang="ts">
  import { Check, ChevronDown, MoreHorizontal, Pencil, Plus, Trash2, Users } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';

  let open = $state(false);
  let creating = $state(false);
  let newName = $state('');
  let busy = $state(false);
  let nameInput = $state<HTMLInputElement | null>(null);

  let menuForId = $state<string | null>(null);
  let menuPos = $state({ left: 0, top: 0 });
  let menuEl = $state<HTMLDivElement | null>(null);
  let renamingId = $state<string | null>(null);
  let renameValue = $state('');
  let renameInput = $state<HTMLInputElement | null>(null);
  let confirmingDeleteId = $state<string | null>(null);

  const MENU_WIDTH = 172;
  const MENU_HEIGHT = 116;

  const activeName = $derived(store.activeWorkspace?.name ?? '工作区');

  function startCreating(): void {
    creating = true;
    menuForId = null;
    renamingId = null;
    confirmingDeleteId = null;
    setTimeout(() => nameInput?.focus(), 0);
  }

  function toggle(): void {
    open = !open;
    if (!open) resetTransient();
  }

  function resetTransient(): void {
    creating = false;
    newName = '';
    menuForId = null;
    renamingId = null;
    renameValue = '';
    confirmingDeleteId = null;
    busy = false;
  }

  function closePopover(): void {
    open = false;
    resetTransient();
  }

  function closeMenu(): void {
    menuForId = null;
  }

  async function select(id: string): Promise<void> {
    closePopover();
    await store.setActiveWorkspace(id);
  }

  async function create(): Promise<void> {
    if (!newName.trim() || busy) return;
    busy = true;
    const created = await store.createWorkspace(newName);
    busy = false;
    if (created) closePopover();
  }

  function clampMenu(left: number, top: number): void {
    const margin = 8;
    const maxLeft = Math.max(margin, window.innerWidth - MENU_WIDTH - margin);
    const nextLeft = Math.min(Math.max(margin, left), maxLeft);
    const nextTop =
      top + MENU_HEIGHT > window.innerHeight - margin
        ? Math.max(margin, top - MENU_HEIGHT - 8)
        : top;
    menuPos = { left: nextLeft, top: nextTop };
  }

  function focusFirstMenuItem(): void {
    setTimeout(() => {
      menuEl?.querySelector<HTMLButtonElement>('.ws-menu-item:not(:disabled)')?.focus();
    }, 0);
  }

  function openMenuAtElement(id: string, element: HTMLElement): void {
    renamingId = null;
    confirmingDeleteId = null;
    menuForId = id;
    const rect = element.getBoundingClientRect();
    clampMenu(rect.right - MENU_WIDTH, rect.bottom + 4);
    focusFirstMenuItem();
  }

  function openMenu(id: string, event: MouseEvent): void {
    event.stopPropagation();
    if (menuForId === id) {
      closeMenu();
      return;
    }
    openMenuAtElement(id, event.currentTarget as HTMLElement);
  }

  function openMenuFromKeyboard(id: string, event: KeyboardEvent): void {
    event.preventDefault();
    event.stopPropagation();
    openMenuAtElement(id, event.currentTarget as HTMLElement);
  }

  /** 右键工作区行与更多按钮共用同一个菜单，位置跟随鼠标。 */
  function openMenuAtCursor(id: string, event: MouseEvent): void {
    event.preventDefault();
    event.stopPropagation();
    renamingId = null;
    confirmingDeleteId = null;
    menuForId = id;
    clampMenu(event.clientX, event.clientY);
    focusFirstMenuItem();
  }

  function onMenuKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.stopPropagation();
      closeMenu();
      return;
    }
    if (event.key === 'Tab') {
      closeMenu();
      return;
    }
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    event.preventDefault();
    const items = Array.from(
      menuEl?.querySelectorAll<HTMLButtonElement>('.ws-menu-item:not(:disabled)') ?? []
    );
    if (!items.length) return;
    const index = items.findIndex((item) => item === document.activeElement);
    const next =
      event.key === 'ArrowDown'
        ? index < 0
          ? 0
          : (index + 1) % items.length
        : index < 0
          ? items.length - 1
          : (index - 1 + items.length) % items.length;
    items[next]?.focus();
  }

  function renameFromMenu(): void {
    const workspace = store.workspaces.find((item) => item.id === menuForId);
    if (!workspace) return;
    closeMenu();
    confirmingDeleteId = null;
    renamingId = workspace.id;
    renameValue = workspace.name;
    setTimeout(() => {
      renameInput?.focus();
      renameInput?.select();
    }, 0);
  }

  function cancelRename(): void {
    renamingId = null;
    renameValue = '';
  }

  async function submitRename(): Promise<void> {
    const id = renamingId;
    if (!id || busy) return;
    const clean = renameValue.trim();
    if (!clean) {
      store.showToast('工作区名称不能为空', 'error');
      return;
    }
    busy = true;
    await store.renameWorkspace(id, clean);
    busy = false;
    const updated = store.workspaces.find((workspace) => workspace.id === id);
    if (updated && updated.name === clean) {
      closePopover();
    }
  }

  function deleteFromMenu(): void {
    const id = menuForId;
    const workspace = store.workspaces.find((item) => item.id === id);
    if (!id || !workspace) return;
    closeMenu();
    renamingId = null;
    confirmingDeleteId = id;
  }

  async function confirmDelete(id: string): Promise<void> {
    if (busy) return;
    busy = true;
    const deleted = await store.deleteWorkspace(id);
    busy = false;
    if (deleted) closePopover();
  }

  function manageMembers(): void {
    closePopover();
    store.settingsSection = 'members';
    store.settingsOpen = true;
  }
</script>

<div class="ws-switch">
  <button
    type="button"
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
    <button
      type="button"
      class="ws-backdrop"
      aria-label="关闭工作区菜单"
      onclick={closePopover}
    ></button>
    <div class="ws-pop" role="menu" aria-label="工作区">
      <div class="ws-pop-label">切换工作区</div>
      {#each store.workspaces as workspace (workspace.id)}
        {#if renamingId === workspace.id}
          <form
            class="ws-rename"
            onsubmit={(event) => {
              event.preventDefault();
              void submitRename();
            }}
          >
            <input
              class="ws-input"
              bind:value={renameValue}
              bind:this={renameInput}
              maxlength="24"
              aria-label="重命名工作区"
              onkeydown={(event) => {
                if (event.key === 'Escape') {
                  event.stopPropagation();
                  cancelRename();
                }
              }}
            />
            <div class="ws-create-actions">
              <button type="button" class="ws-mini" onclick={cancelRename}>取消</button>
              <button
                type="submit"
                class="ws-mini primary"
                disabled={!renameValue.trim() || busy}
              >
                保存
              </button>
            </div>
          </form>
        {:else}
          <div class="ws-row" class:is-active={workspace.id === store.activeWorkspaceId}>
            <button
              type="button"
              class="ws-item"
              role="menuitemradio"
              aria-checked={workspace.id === store.activeWorkspaceId}
              onclick={() => select(workspace.id)}
              oncontextmenu={(event) => openMenuAtCursor(workspace.id, event)}
            >
              <span class="ws-dot"></span>
              <span class="ws-item-name">{workspace.name}</span>
              {#if workspace.id === store.activeWorkspaceId}
                <Check size="12" />
              {/if}
            </button>
            <button
              type="button"
              class="ws-more"
              aria-label="工作区操作：{workspace.name}"
              aria-haspopup="menu"
              aria-expanded={menuForId === workspace.id}
              onclick={(event) => openMenu(workspace.id, event)}
              onkeydown={(event) => {
                if (event.key === 'ArrowDown') openMenuFromKeyboard(workspace.id, event);
              }}
            >
              <MoreHorizontal size="14" />
            </button>
          </div>
        {/if}

        {#if confirmingDeleteId === workspace.id}
          <div class="ws-confirm" role="alert">
            <p>
              删除工作区「{workspace.name}」？其中的清单与任务将从侧栏移除，且无法在界面中恢复。
            </p>
            <div class="ws-create-actions">
              <button
                type="button"
                class="ws-mini"
                onclick={() => (confirmingDeleteId = null)}
              >
                取消
              </button>
              <button
                type="button"
                class="ws-mini danger"
                disabled={busy}
                onclick={() => void confirmDelete(workspace.id)}
              >
                确认删除
              </button>
            </div>
          </div>
        {/if}
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
        <button type="button" class="ws-action" role="menuitem" onclick={startCreating}>
          <Plus size="13" />
          <span>新建工作区</span>
        </button>
      {/if}

      <div class="ws-divider"></div>
      <button type="button" class="ws-action" role="menuitem" onclick={manageMembers}>
        <Users size="13" />
        <span>管理成员 · {store.members.length}</span>
      </button>
    </div>
  {/if}

  {#if menuForId}
    <button
      type="button"
      class="ws-menu-backdrop"
      aria-label="关闭工作区操作菜单"
      onclick={closeMenu}
    ></button>
    <div
      class="ws-menu"
      style="left:{menuPos.left}px;top:{menuPos.top}px"
      role="menu"
      aria-label="工作区操作"
      tabindex="-1"
      bind:this={menuEl}
      onkeydown={onMenuKeydown}
    >
      <button type="button" class="ws-menu-item" role="menuitem" onclick={renameFromMenu}>
        <Pencil size="13" />
        <span>重命名</span>
      </button>
      <div class="ws-menu-divider"></div>
      <button
        type="button"
        class="ws-menu-item danger"
        role="menuitem"
        disabled={!store.canDeleteWorkspace}
        title={!store.canDeleteWorkspace ? '至少需要保留一个工作区' : undefined}
        onclick={deleteFromMenu}
      >
        <Trash2 size="13" />
        <span>删除工作区</span>
      </button>
      {#if !store.canDeleteWorkspace}
        <p class="ws-menu-hint">至少需要保留一个工作区</p>
      {/if}
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
    max-height: min(70vh, 420px);
    overflow-y: auto;
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

  .ws-row {
    display: flex;
    align-items: center;
    gap: 2px;
    border-radius: var(--radius-sm);
  }

  .ws-row:hover {
    background: var(--color-overlay);
  }

  .ws-row.is-active {
    background: var(--color-primary-50);
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

  .ws-item {
    flex: 1;
    min-width: 0;
  }

  .ws-row.is-active .ws-item {
    color: var(--color-primary);
    font-weight: var(--weight-semibold);
  }

  .ws-action:hover {
    background: var(--color-overlay);
  }

  .ws-more {
    width: 22px;
    height: 22px;
    margin-right: 4px;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    border: none;
    border-radius: 6px;
    background: transparent;
    color: var(--color-text-3);
    opacity: 0;
  }

  .ws-row:hover .ws-more,
  .ws-row:focus-within .ws-more,
  .ws-row.is-active .ws-more,
  .ws-more[aria-expanded='true'] {
    opacity: 1;
  }

  .ws-more:hover {
    background: var(--color-card);
    color: var(--color-text);
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

  .ws-rename,
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

  .ws-mini.danger {
    border-color: var(--color-danger);
    background: var(--color-danger);
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

  .ws-confirm {
    margin: 2px 0 6px;
    padding: 8px;
    border: 1px solid var(--color-danger-border);
    border-radius: var(--radius-sm);
    background: var(--color-danger-50);
  }

  .ws-confirm p {
    margin: 0;
    color: var(--color-text-2);
    font-size: var(--text-sm);
    line-height: 1.45;
  }

  .ws-menu-backdrop {
    position: fixed;
    inset: 0;
    z-index: calc(var(--z-popover) + 1);
    border: none;
    background: transparent;
    cursor: default;
  }

  .ws-menu {
    position: fixed;
    z-index: calc(var(--z-popover) + 2);
    width: 172px;
    padding: 5px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-card);
    box-shadow: var(--shadow-lg);
  }

  .ws-menu-item {
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

  .ws-menu-item:hover:not(:disabled),
  .ws-menu-item:focus-visible {
    background: var(--color-overlay);
  }

  .ws-menu-item.danger {
    color: var(--color-danger);
  }

  .ws-menu-item.danger:hover:not(:disabled) {
    background: var(--color-danger-50);
  }

  .ws-menu-item:disabled {
    opacity: 0.45;
    cursor: not-allowed;
  }

  .ws-menu-divider {
    height: 1px;
    margin: var(--space-1) 4px;
    background: var(--color-border-2);
  }

  .ws-menu-hint {
    margin: 0;
    padding: 2px 8px 4px;
    color: var(--color-text-3);
    font-size: var(--text-sm);
  }
</style>
