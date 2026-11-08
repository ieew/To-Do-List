<script lang="ts">
  import { CalendarClock, History, Pin, Trash2 } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';

  function close(): void {
    store.closeContextMenu();
  }

  function run(action: () => void): void {
    action();
    close();
  }

  const position = $derived.by(() => {
    const menu = store.contextMenu;
    if (!menu || typeof window === 'undefined') return { left: 0, top: 0 };
    return {
      left: Math.min(menu.x, window.innerWidth - 200),
      top: Math.min(menu.y, window.innerHeight - 190)
    };
  });

  const task = $derived(store.contextMenu ? store.taskById(store.contextMenu.taskId) : undefined);
</script>

<svelte:window onclick={close} onkeydown={(event) => event.key === 'Escape' && close()} />

{#if store.contextMenu && task}
  <div
    class="context-menu"
    style="left:{position.left}px;top:{position.top}px"
    role="menu"
    tabindex="-1"
    onclick={(event) => event.stopPropagation()}
    onkeydown={(event) => event.stopPropagation()}
  >
    <div class="menu-title">{task.title}</div>
    <button class="menu-item" role="menuitem" onclick={() => run(() => store.postponeTask(task.id))}>
      <CalendarClock size="14" />
      延期到明天
    </button>
    <button class="menu-item" role="menuitem" onclick={() => run(() => store.openFloatingStickyWindow(task.id))}>
      <Pin size="14" />
      钉为便利贴
    </button>
    <button class="menu-item" role="menuitem" onclick={() => run(() => store.openTask(task.id, 'history'))}>
      <History size="14" />
      查看历史
    </button>
    <div class="menu-divider"></div>
    <button class="menu-item danger" role="menuitem" onclick={() => run(() => store.deleteTask(task.id))}>
      <Trash2 size="14" />
      删除任务
    </button>
  </div>
{/if}

<style>
  .context-menu {
    position: fixed;
    z-index: var(--z-popover);
    width: 188px;
    padding: 5px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-card);
    box-shadow: var(--shadow-lg);
    animation: menu-in var(--duration-fast) var(--ease-out);
  }

  .menu-title {
    padding: 6px 8px;
    color: var(--color-text-3);
    font-size: 10.5px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .menu-item {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    width: 100%;
    padding: 7px 8px;
    border: none;
    border-radius: 6px;
    background: transparent;
    color: var(--color-text-2);
    font-size: var(--text-base);
    text-align: left;
  }

  .menu-item:hover {
    background: var(--color-border-2);
    color: var(--color-text);
  }

  .menu-item.danger {
    color: var(--color-danger);
  }

  .menu-item.danger:hover {
    background: #fef2f2;
  }

  .menu-divider {
    height: 1px;
    margin: 4px 6px;
    background: var(--color-border-2);
  }

  @keyframes menu-in {
    from {
      opacity: 0;
      transform: translateY(-4px) scale(0.98);
    }
    to {
      opacity: 1;
      transform: translateY(0) scale(1);
    }
  }

  :global(html[data-theme='dark']) .menu-item.danger:hover {
    background: rgba(239, 68, 68, 0.12);
  }
</style>
