<script lang="ts">
  import { Plus, X } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
  import type { Priority } from '../lib/types';
  import { defaultDueAt, defaultStartAt, fromLocalInput, toLocalInput } from '../lib/utils';

  let title = $state('');
  let listId = $state('');
  let priority = $state<Priority>('mid');
  let start = $state('');
  let due = $state('');
  let titleInput = $state<HTMLInputElement | null>(null);
  let lastOpen = false;

  $effect(() => {
    if (store.quickAddOpen === lastOpen) return;
    lastOpen = store.quickAddOpen;
    if (store.quickAddOpen) {
      title = '';
      listId =
        store.activeListId === store.todayListId
          ? store.lists.find((list) => !list.smart)?.id ?? store.todayListId
          : store.activeListId;
      priority = store.settings.defaultPriority;
      start = toLocalInput(defaultStartAt());
      due = toLocalInput(defaultDueAt());
      setTimeout(() => titleInput?.focus(), 30);
    }
  });

  const priorities: { value: Priority; label: string }[] = [
    { value: 'high', label: '高' },
    { value: 'mid', label: '中' },
    { value: 'low', label: '低' }
  ];

  function close(): void {
    store.quickAddOpen = false;
  }

  function submit(): void {
    if (!title.trim()) {
      store.showToast('先写一个任务标题', 'error');
      return;
    }
    store.createTask({
      title,
      priority,
      listId,
      startAt: fromLocalInput(start),
      dueAt: fromLocalInput(due),
      source: 'human'
    });
    store.showToast('已创建任务');
    close();
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (!store.quickAddOpen) return;
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      submit();
    }
  }
</script>

<svelte:window onkeydown={handleKeydown} />

{#if store.quickAddOpen}
  <div class="modal-backdrop" role="presentation" onclick={(event) => event.target === event.currentTarget && close()}>
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="quick-add-title">
      <div class="modal-head">
        <div class="modal-title">
          <span class="modal-icon"><Plus size="14" /></span>
          <h3 id="quick-add-title">新建任务</h3>
        </div>
        <button class="icon-button" aria-label="关闭" onclick={close}><X size="15" /></button>
      </div>

      <div class="modal-body">
        <label class="form-row">
          <span class="form-label">标题</span>
          <input
            class="field-input"
            placeholder="要做什么？"
            bind:value={title}
            bind:this={titleInput}
          />
        </label>

        <div class="form-grid">
          {#if store.lists.some((list) => !list.smart)}
            <label class="form-row">
              <span class="form-label">清单</span>
              <select class="field-select" bind:value={listId}>
                {#each store.lists.filter((list) => !list.smart) as list (list.id)}
                  <option value={list.id}>{list.name}</option>
                {/each}
              </select>
            </label>
          {/if}

          <label class="form-row">
            <span class="form-label">开始</span>
            <input class="field-input" type="datetime-local" bind:value={start} />
          </label>

          <label class="form-row">
            <span class="form-label">截止</span>
            <input class="field-input" type="datetime-local" bind:value={due} />
          </label>
        </div>

        <div class="form-row">
          <span class="form-label">优先级</span>
          <div class="segmented">
            {#each priorities as item (item.value)}
              <button class:is-active={priority === item.value} onclick={() => (priority = item.value)}>
                <span class="priority-dot {item.value}"></span>
                {item.label}
              </button>
            {/each}
          </div>
        </div>

        <p class="modal-note">创建后写入 op 管道：业务表 + op_log + local_hlc + pending_ops（原型使用内存投影）。</p>
      </div>

      <div class="modal-actions">
        <button class="btn" onclick={close}>取消</button>
        <button class="btn btn-primary" onclick={submit}>
          <Plus size="14" />
          创建任务
        </button>
      </div>
    </div>
  </div>
{/if}

<style>
  .modal-backdrop {
    position: absolute;
    inset: 0;
    z-index: var(--z-modal);
    display: flex;
    align-items: center;
    justify-content: center;
    padding: var(--space-6);
    background: rgba(15, 23, 42, 0.42);
    backdrop-filter: blur(2px);
    animation: fade-in var(--duration-base) var(--ease-standard);
  }

  .modal {
    width: 440px;
    max-width: 100%;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    background: var(--color-card);
    box-shadow: var(--shadow-xl);
    overflow: hidden;
    animation: pop-in var(--duration-base) var(--ease-standard);
  }

  .modal-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
    padding: var(--space-4) var(--space-5) 0;
  }

  .modal-title {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }

  .modal-icon {
    width: 26px;
    height: 26px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border-radius: var(--radius-sm);
    background: var(--color-primary-50);
    color: var(--color-primary);
  }

  .modal-title h3 {
    margin: 0;
    font-size: var(--text-lg);
    font-weight: var(--weight-semibold);
  }

  .modal-body {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    padding: var(--space-4) var(--space-5) var(--space-5);
  }

  .form-row {
    display: flex;
    flex-direction: column;
    gap: 5px;
    min-width: 0;
  }

  .form-label {
    font-size: var(--text-sm);
    font-weight: var(--weight-medium);
    color: var(--color-text-3);
  }

  .form-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--space-3);
  }

  .segmented {
    align-self: flex-start;
  }

  .segmented button {
    display: inline-flex;
    align-items: center;
    gap: 5px;
  }

  .modal-note {
    margin: 0;
    padding: var(--space-2) 10px;
    border-radius: 6px;
    background: var(--color-bg);
    color: var(--color-text-3);
    font-size: 10.5px;
    line-height: 1.6;
  }

  .modal-actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-2);
    padding: 0 var(--space-5) var(--space-4);
  }

  @keyframes fade-in {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }

  @keyframes pop-in {
    from {
      opacity: 0;
      transform: scale(0.97) translateY(4px);
    }
    to {
      opacity: 1;
      transform: scale(1) translateY(0);
    }
  }
</style>
