<script lang="ts">
  import {
    AlertTriangle,
    ArrowLeft,
    Check,
    ClipboardPaste,
    Copy,
    ExternalLink,
    Image,
    Loader2,
    Sparkles,
    Wand2,
    X
  } from 'lucide-svelte';
  import { readText, writeText } from '@tauri-apps/plugin-clipboard-manager';
  import { openUrl } from '@tauri-apps/plugin-opener';
  import { store } from '../lib/store.svelte';
  import { errorMessage, ipc, isTauri } from '../lib/ipc';
  import type { AiIntentResponse, AiOperation, Priority } from '../lib/types';
  import { formatDueShort } from '../lib/utils';

  type AiMode = 'api' | 'web';
  type Step = 'input' | 'preview' | 'confirm';

  // 网页版 DeepSeek 桥接：源码完整保留，默认隐藏；恢复时改为 true。
  const SHOW_WEB_BRIDGE = false;
  const MAX_IMAGES = 5;
  const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
  const MAX_EDGE = 1568;

  let prompt = $state('');
  let mode = $state<AiMode>('api');
  let step = $state<Step>('input');
  let operations = $state<AiOperation[]>([]);
  let warnings = $state<string[]>([]);
  let reply = $state('');
  let rawOutput = $state('');
  let parseError = $state('');
  let generating = $state(false);
  let parsing = $state(false);
  let copying = $state(false);
  let copyState = $state<'idle' | 'copied'>('idle');
  let images = $state<{ id: number; name: string; dataUrl: string }[]>([]);
  let fileInput = $state<HTMLInputElement | null>(null);
  let lastOpen = false;

  const chips = ['规划今晚的学习', '整理本周工作重点', '把大目标拆成三步'];
  const destructive = $derived(
    operations.filter((op) => op.op === 'set_status' || op.op === 'delete_task')
  );
  const destructiveBlocked = $derived(
    destructive.length > 0 && !store.settings.aiAllowDestructive
  );
  const needsConfirm = $derived(
    operations.length >= store.settings.aiConfirmThreshold || destructive.length > 0
  );
  const canCallApi = $derived(store.settings.aiEnabled && store.aiKeyConfigured);

  $effect(() => {
    if (store.aiOpen === lastOpen) return;
    lastOpen = store.aiOpen;
    if (store.aiOpen) {
      prompt = '';
      mode = SHOW_WEB_BRIDGE && !store.aiKeyConfigured ? 'web' : 'api';
      step = 'input';
      operations = [];
      warnings = [];
      reply = '';
      rawOutput = '';
      parseError = '';
      copyState = 'idle';
      generating = false;
      parsing = false;
      copying = false;
      images = [];
    }
  });

  $effect(() => {
    void prompt;
    copyState = 'idle';
  });

  function close(): void {
    store.aiOpen = false;
  }

  function switchMode(next: AiMode): void {
    if (next === 'web' && !SHOW_WEB_BRIDGE) return;
    if (mode === next) return;
    mode = next;
    step = 'input';
    operations = [];
    warnings = [];
    rawOutput = '';
    parseError = '';
  }

  function priorityText(priority: Priority): string {
    return priority === 'high' ? '高' : priority === 'mid' ? '中' : '低';
  }

  function fieldLabel(field: string): string {
    const map: Record<string, string> = {
      title: '标题',
      notes: '备注',
      start_at: '开始时间',
      startAt: '开始时间',
      due_at: '截止时间',
      dueAt: '截止时间',
      remind_at: '提醒时间',
      remindAt: '提醒时间',
      priority: '优先级',
      list_id: '清单',
      listId: '清单',
      status: '状态'
    };
    return map[field] ?? field;
  }

  function displayPatch(field: string, value: unknown): string {
    if (value === null || value === undefined) return '清空';
    if (field === 'priority') return priorityText(value as Priority);
    if (/^(due_at|start_at|remind_at|dueAt|startAt|remindAt)$/.test(field)) {
      return typeof value === 'string' ? formatDueShort(value) : String(value);
    }
    if (field === 'status') return value === 'done' ? '已完成' : '未完成';
    if (field === 'list_id' || field === 'listId') return store.listName(String(value));
    return String(value);
  }

  function viewOf(op: AiOperation): { kind: string; title: string; detail: string; danger?: boolean } {
    switch (op.op) {
      case 'create_task': {
        const parts = [
          store.listName(op.listId),
          priorityText(op.priority),
          op.dueAt ? formatDueShort(op.dueAt) : '',
          op.tagIds.map((id) => `#${store.tagName(id)}`).join(' '),
          op.subtasks.length ? `${op.subtasks.length} 个子任务` : ''
        ].filter(Boolean);
        return { kind: '新建任务', title: op.title, detail: parts.join(' · ') };
      }
      case 'update_fields': {
        const changes = Object.entries(op.patch)
          .map(([key, value]) => `${fieldLabel(key)} → ${displayPatch(key, value)}`)
          .join('；');
        const cleared = op.clearFields.length
          ? `；清空 ${op.clearFields.map(fieldLabel).join('、')}`
          : '';
        return {
          kind: '修改任务',
          title: store.taskById(op.taskId)?.title ?? op.taskId,
          detail: changes + cleared,
          danger: 'status' in op.patch
        };
      }
      case 'add_subtasks':
        return {
          kind: '添加子任务',
          title: store.taskById(op.taskId)?.title ?? op.taskId,
          detail: op.subtasks.join('、')
        };
      case 'set_subtask_done':
        return {
          kind: op.done ? '完成子任务' : '恢复子任务',
          title: store.taskById(op.taskId)?.title ?? op.taskId,
          detail: op.subtaskId
        };
      case 'add_tags':
      case 'remove_tags':
        return {
          kind: op.op === 'add_tags' ? '添加标签' : '移除标签',
          title: store.taskById(op.taskId)?.title ?? op.taskId,
          detail: op.tagIds.map((id) => `#${store.tagName(id)}`).join(' ')
        };
      case 'set_status':
        return {
          kind: '改完成状态',
          title: store.taskById(op.taskId)?.title ?? op.taskId,
          detail: op.status === 'done' ? '标记为已完成' : '恢复为待办',
          danger: true
        };
      case 'delete_task':
        return {
          kind: '删除任务',
          title: store.taskById(op.taskId)?.title ?? op.taskId,
          detail: '删除后可通过历史撤销',
          danger: true
        };
      default:
        return { kind: '未知操作', title: '', detail: '' };
    }
  }

  function applyResponse(response: AiIntentResponse): void {
    if (!response.operations.length) {
      parseError = '没有解析到可执行的操作';
      store.showToast(parseError, 'error');
      return;
    }
    operations = response.operations;
    warnings = response.warnings ?? [];
    reply = response.reply ?? '';
    parseError = '';
    step = 'preview';
  }

  async function generate(): Promise<void> {
    if (!store.settings.aiEnabled) {
      store.showToast('AI 助手已关闭，请先在设置中开启', 'error');
      return;
    }
    if (!store.aiKeyConfigured) {
      store.showToast('请先在设置中配置 API 密钥', 'error');
      return;
    }
    if (!prompt.trim()) {
      store.showToast('先描述你想让 AI 做什么', 'error');
      return;
    }
    generating = true;
    parseError = '';
    try {
      const response = isTauri
        ? await ipc.aiPlan(
            prompt,
            store.activeWorkspaceId,
            images.map((item) => item.dataUrl)
          )
        : previewIntent(prompt);
      applyResponse(response);
    } catch (error) {
      store.showToast(errorMessage(error), 'error');
    } finally {
      generating = false;
    }
  }

  async function parseOutput(): Promise<void> {
    if (!rawOutput.trim()) {
      parseError = '请先粘贴模型输出';
      return;
    }
    parsing = true;
    parseError = '';
    try {
      const response = isTauri
        ? await ipc.aiParseIntent(rawOutput, store.activeWorkspaceId)
        : previewIntent(rawOutput);
      applyResponse(response);
    } catch (error) {
      parseError = errorMessage(error);
    } finally {
      parsing = false;
    }
  }

  async function pasteAndParse(): Promise<void> {
    try {
      const text = isTauri ? await readText() : await navigator.clipboard.readText();
      if (!text || !text.trim()) {
        store.showToast('剪贴板为空', 'error');
        return;
      }
      rawOutput = text;
      await parseOutput();
    } catch {
      store.showToast('无法读取剪贴板，请手动粘贴模型输出', 'error');
    }
  }

  async function copyPrompt(openAfter: boolean): Promise<void> {
    if (!prompt.trim()) {
      store.showToast('先描述你想让 AI 做什么', 'error');
      return;
    }
    copying = true;
    try {
      const text = isTauri
        ? await ipc.aiBuildIntentPrompt(prompt, store.activeWorkspaceId)
        : prompt;
      const copied = await copyToClipboard(text);
      if (!copied) {
        store.showToast('复制失败，请手动选择提示词', 'error');
        return;
      }
      copyState = 'copied';
      if (openAfter) {
        store.showToast('提示词已复制，正在打开 DeepSeek', 'ok');
        await openDeepSeek();
      } else {
        store.showToast('提示词已复制', 'ok');
      }
    } catch (error) {
      store.showToast(errorMessage(error), 'error');
    } finally {
      copying = false;
    }
  }

  async function copyToClipboard(text: string): Promise<boolean> {
    try {
      if (isTauri) {
        await writeText(text);
      } else {
        await navigator.clipboard.writeText(text);
      }
      return true;
    } catch {
      try {
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        const copied = document.execCommand('copy');
        document.body.removeChild(textarea);
        return copied;
      } catch {
        return false;
      }
    }
  }

  async function openDeepSeek(): Promise<void> {
    try {
      if (isTauri) {
        await openUrl('https://chat.deepseek.com/');
      } else {
        window.open('https://chat.deepseek.com/', '_blank', 'noopener,noreferrer');
      }
    } catch {
      store.showToast('打开浏览器失败，请手动访问 chat.deepseek.com', 'error');
    }
  }

  function continueFromPreview(): void {
    if (needsConfirm) {
      step = 'confirm';
      return;
    }
    void confirmWrite();
  }

  async function confirmWrite(): Promise<void> {
    if (destructiveBlocked) {
      store.showToast('请先在设置中允许 AI 修改完成状态或删除任务', 'error');
      return;
    }
    const ok = await store.applyAiOperations(operations);
    if (ok) close();
  }

  function backToInput(): void {
    step = 'input';
    operations = [];
    warnings = [];
    parseError = '';
  }

  function openKeySettings(): void {
    store.aiOpen = false;
    store.settingsSection = 'ai';
    store.settingsOpen = true;
  }

  /* ---------------------------- 图片输入 ---------------------------- */

  function pickImages(): void {
    if (!store.settings.aiImageInput) {
      store.showToast('图片输入已在设置中关闭', 'info');
      return;
    }
    fileInput?.click();
  }

  async function onFiles(event: Event): Promise<void> {
    const input = event.currentTarget as HTMLInputElement;
    if (input.files) await addFiles(Array.from(input.files));
    input.value = '';
  }

  async function addFiles(files: File[]): Promise<void> {
    for (const file of files) {
      if (images.length >= MAX_IMAGES) {
        store.showToast(`最多添加 ${MAX_IMAGES} 张图片`, 'error');
        break;
      }
      if (!file.type.startsWith('image/')) continue;
      const dataUrl = await compress(file);
      if (!dataUrl) {
        store.showToast('图片过大或无法读取，请换一张', 'error');
        continue;
      }
      images = [
        ...images,
        { id: Date.now() + Math.random(), name: file.name || '粘贴的图片', dataUrl }
      ];
    }
  }

  async function compress(file: File): Promise<string | null> {
    try {
      const bitmap = await createImageBitmap(file);
      const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(bitmap.width * scale));
      canvas.height = Math.max(1, Math.round(bitmap.height * scale));
      const context = canvas.getContext('2d');
      if (!context) return null;
      context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      bitmap.close?.();
      const blob = await new Promise<Blob | null>((resolve) =>
        canvas.toBlob(resolve, 'image/jpeg', 0.85)
      );
      if (!blob || blob.size > MAX_IMAGE_BYTES) return null;
      return await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result));
        reader.onerror = () => reject(reader.error);
        reader.readAsDataURL(blob);
      });
    } catch {
      return null;
    }
  }

  function removeImage(id: number): void {
    images = images.filter((item) => item.id !== id);
  }

  function handlePaste(event: ClipboardEvent): void {
    if (!store.aiOpen) return;
    const files = Array.from(event.clipboardData?.files ?? []);
    if (files.length) {
      event.preventDefault();
      void addFiles(files);
    }
  }

  /* -------------------------- 浏览器预览 -------------------------- */

  function previewIntent(text: string): AiIntentResponse {
    const fallbackList = store.lists.find((list) => !list.smart)?.id ?? store.todayListId;
    return {
      intentVersion: 1,
      reply: '浏览器预览模式：未调用模型，仅演示操作预览。',
      operations: [
        {
          op: 'create_task',
          tempId: 't1',
          title: `预览：${text.trim().slice(0, 40) || '新目标'}`,
          notes: '浏览器预览模式：不会写入 op_log。',
          priority: store.settings.defaultPriority,
          listId: fallbackList,
          dueAt: null,
          tagIds: [],
          subtasks: []
        }
      ],
      warnings: []
    };
  }
</script>

<svelte:window onkeydown={(event) => event.key === 'Escape' && store.aiOpen && close()} />

{#if store.aiOpen}
  <div
    class="modal-backdrop"
    role="presentation"
    onclick={(event) => event.target === event.currentTarget && close()}
    onpaste={handlePaste}
  >
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="ai-title">
      <div class="modal-head">
        <div class="modal-title">
          <span class="modal-icon"><Sparkles size="14" /></span>
          <h3 id="ai-title">AI 助手</h3>
          {#if step !== 'input'}
            <span class="step-chip">{step === 'preview' ? '草案预览' : '确认写入'}</span>
          {/if}
        </div>
        <button class="icon-button" aria-label="关闭" onclick={close}><X size="15" /></button>
      </div>

      {#if step === 'input'}
        {#if SHOW_WEB_BRIDGE}
          <div class="mode-tabs" role="tablist" aria-label="AI 通道">
            <button class:is-active={mode === 'api'} role="tab" aria-selected={mode === 'api'} onclick={() => switchMode('api')}>
              API 直连
            </button>
            <button class:is-active={mode === 'web'} role="tab" aria-selected={mode === 'web'} onclick={() => switchMode('web')}>
              网页版 DeepSeek
            </button>
          </div>
        {/if}

        {#if !store.settings.aiEnabled}
          <div class="banner warning">AI 助手已关闭，请先在设置中开启。</div>
        {:else if mode === 'api' && !store.aiKeyConfigured}
          <div class="banner warning">
            还没有配置 API 密钥。
            <button class="link-btn" onclick={openKeySettings}>去设置密钥</button>
          </div>
        {/if}

        <div class="modal-body">
          <label class="form-row">
            <span class="form-label">想让 AI 做什么</span>
            <textarea
              class="field-textarea prompt-input"
              placeholder="例如：把「准备季度汇报」拆成 3 个任务，今晚和明天各做一部分"
              bind:value={prompt}
            ></textarea>
          </label>

          <div class="chips">
            {#each chips as chip (chip)}
              <button class="chip" onclick={() => (prompt = chip)}>{chip}</button>
            {/each}
          </div>

          {#if mode === 'api'}
            <div class="image-row">
              <button class="btn" onclick={pickImages}>
                <Image size="13" />
                添加图片
              </button>
              <span class="image-hint">
                {store.settings.aiImageInput
                  ? `最多 ${MAX_IMAGES} 张，单张不超过 5MB；只发送给你配置的模型端点。`
                  : '图片输入已在设置中关闭。'}
              </span>
              <input
                class="hidden-file"
                type="file"
                accept="image/*"
                multiple
                bind:this={fileInput}
                onchange={onFiles}
              />
            </div>

            {#if images.length > 0}
              <div class="thumbs">
                {#each images as image (image.id)}
                  <div class="thumb">
                    <img src={image.dataUrl} alt={image.name} />
                    <button class="thumb-remove" aria-label="移除图片" onclick={() => removeImage(image.id)}>
                      <X size="10" />
                    </button>
                  </div>
                {/each}
              </div>
            {/if}

            <p class="privacy-note">
              图片仅用于本次解析，不写入 op_log、不落盘、不参与同步。
            </p>
          {:else}
            <div class="web-steps">
              <div class="web-step">
                <span class="step-index">1</span>
                <div class="step-body">
                  <div class="step-title">复制提示词并发送给 DeepSeek</div>
                  <div class="step-actions">
                    <button class="btn btn-primary" disabled={!prompt.trim() || copying} onclick={() => copyPrompt(true)}>
                      <Copy size="13" />复制并打开
                    </button>
                    <button class="btn" disabled={!prompt.trim() || copying} onclick={() => copyPrompt(false)}>
                      仅复制
                    </button>
                    <button class="btn" onclick={openDeepSeek}>
                      <ExternalLink size="13" />打开网页
                    </button>
                  </div>
                  {#if copyState === 'copied'}
                    <div class="banner ok">已复制。请在 DeepSeek 网页粘贴并发送，再把模型输出复制回来。</div>
                  {/if}
                </div>
              </div>
              <div class="web-step">
                <span class="step-index">2</span>
                <div class="step-body">
                  <div class="step-title">粘贴模型输出并解析</div>
                  <textarea class="field-textarea" placeholder="粘贴模型返回的 JSON…" bind:value={rawOutput}></textarea>
                  {#if parseError}
                    <div class="banner danger">{parseError}</div>
                  {/if}
                  <div class="step-actions">
                    <button class="btn" disabled={parsing} onclick={pasteAndParse}>
                      <ClipboardPaste size="13" />从剪贴板粘贴并解析
                    </button>
                    <button class="btn btn-primary" disabled={parsing || !rawOutput.trim()} onclick={parseOutput}>
                      {#if parsing}<Loader2 size="13" class="spin" />{:else}<Check size="13" />{/if}
                      解析并预览
                    </button>
                  </div>
                </div>
              </div>
            </div>
          {/if}
        </div>

        <div class="modal-actions">
          <button class="btn" onclick={close}>取消</button>
          {#if mode === 'api'}
            <button class="btn btn-primary" disabled={!canCallApi || generating} onclick={generate}>
              {#if generating}
                <Loader2 size="14" class="spin" />
                生成中…
              {:else}
                <Wand2 size="14" />
                生成操作草案
              {/if}
            </button>
          {/if}
        </div>
      {:else if step === 'preview'}
        <div class="modal-body">
          {#if reply}
            <div class="reply">{reply}</div>
          {/if}

          {#if warnings.length}
            <div class="warn-list">
              {#each warnings as warning (warning)}
                <div class="warn-item"><AlertTriangle size="12" />{warning}</div>
              {/each}
            </div>
          {/if}

          {#if destructive.length}
            <div class="banner danger">
              本次包含 {destructive.length} 个危险操作（改完成状态 / 删除任务）。
              {#if destructiveBlocked}
                当前设置已关闭危险操作，执行前请先在设置里开启。
              {/if}
            </div>
          {/if}

          <div class="op-list">
            {#each operations as op, index (`${op.op}-${index}`)}
              {@const view = viewOf(op)}
              <div class="op-item" class:danger={view.danger}>
                <span class="op-kind">{view.kind}</span>
                <div class="op-main">
                  <div class="op-title">{view.title}</div>
                  <div class="op-detail">{view.detail || '—'}</div>
                </div>
              </div>
            {/each}
          </div>
        </div>

        <div class="modal-actions">
          <button class="btn" onclick={backToInput}>
            <ArrowLeft size="13" />返回
          </button>
          <button class="btn btn-primary" disabled={destructiveBlocked} onclick={continueFromPreview}>
            执行 {operations.length} 项操作
          </button>
        </div>
      {:else}
        <div class="modal-body">
          <div class="confirm-title">AI 想执行 {operations.length} 项操作</div>
          <div class="op-list compact">
            {#each operations.slice(0, 5) as op, index (`${op.op}-${index}`)}
              {@const view = viewOf(op)}
              <div class="op-item" class:danger={view.danger}>
                <span class="op-kind">{view.kind}</span>
                <div class="op-main">
                  <div class="op-title">{view.title}</div>
                  <div class="op-detail">{view.detail || '—'}</div>
                </div>
              </div>
            {/each}
            {#if operations.length > 5}
              <div class="more">还有 {operations.length - 5} 项…</div>
            {/if}
          </div>
          {#if destructive.length}
            <div class="banner danger">
              删除与完成状态修改会写入 op，执行后可在 6 秒内点「撤销」整体回滚。
            </div>
          {/if}
        </div>

        <div class="modal-actions">
          <button class="btn" onclick={() => (step = 'preview')}>取消</button>
          <button class="btn btn-primary" disabled={destructiveBlocked} onclick={confirmWrite}>
            确认执行
          </button>
        </div>
      {/if}
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
    width: 560px;
    max-width: 100%;
    max-height: 86vh;
    display: flex;
    flex-direction: column;
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
    padding: var(--space-4) var(--space-5) var(--space-3);
  }

  .modal-title {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }

  .modal-title h3 {
    margin: 0;
    font-size: var(--text-lg);
    font-weight: var(--weight-semibold);
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

  .step-chip {
    padding: 1px 8px;
    border-radius: var(--radius-full);
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-size: var(--text-xs);
    font-weight: var(--weight-medium);
  }

  .mode-tabs {
    display: flex;
    gap: 4px;
    padding: 0 var(--space-5);
    margin-bottom: var(--space-2);
  }

  .mode-tabs button {
    padding: 6px 10px;
    border: none;
    border-bottom: 2px solid transparent;
    border-radius: 6px 6px 0 0;
    background: transparent;
    color: var(--color-text-2);
    font-size: var(--text-base);
  }

  .mode-tabs button.is-active {
    border-bottom-color: var(--color-primary);
    color: var(--color-primary);
    font-weight: var(--weight-semibold);
  }

  .modal-body {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-5) var(--space-4);
  }

  .modal-actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-2);
    padding: var(--space-3) var(--space-5) var(--space-4);
    border-top: 1px solid var(--color-border-2);
  }

  .form-row {
    display: flex;
    flex-direction: column;
    gap: 5px;
  }

  .form-label {
    font-size: var(--text-sm);
    font-weight: var(--weight-medium);
    color: var(--color-text-3);
  }

  .prompt-input {
    min-height: 96px;
  }

  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .chip {
    padding: 3px 9px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-full);
    background: var(--color-card);
    color: var(--color-text-2);
    font-size: var(--text-sm);
  }

  .chip:hover {
    border-color: #bfdbfe;
    background: var(--color-primary-50);
    color: var(--color-primary);
  }

  .image-row {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    flex-wrap: wrap;
  }

  .image-hint {
    font-size: var(--text-sm);
    color: var(--color-text-3);
    line-height: 1.5;
  }

  .hidden-file {
    display: none;
  }

  .thumbs {
    display: flex;
    gap: var(--space-2);
    flex-wrap: wrap;
  }

  .thumb {
    position: relative;
    width: 64px;
    height: 64px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    overflow: hidden;
    background: var(--color-bg);
  }

  .thumb img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .thumb-remove {
    position: absolute;
    top: 2px;
    right: 2px;
    width: 16px;
    height: 16px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: none;
    border-radius: 50%;
    background: rgba(15, 23, 42, 0.62);
    color: #fff;
  }

  .privacy-note {
    margin: 0;
    font-size: var(--text-xs);
    color: var(--color-text-3);
  }

  .banner {
    padding: 8px 10px;
    border-radius: var(--radius-sm);
    font-size: var(--text-sm);
    line-height: 1.55;
  }

  .banner.warning {
    border: 1px solid #fcd34d;
    background: #fef3c7;
    color: #92400e;
  }

  .banner.danger {
    border: 1px solid #fca5a5;
    background: #fef2f2;
    color: #991b1b;
  }

  .banner.ok {
    border: 1px solid #a7f3d0;
    background: #ecfdf5;
    color: #047857;
  }

  .link-btn {
    border: none;
    background: transparent;
    color: inherit;
    font-weight: var(--weight-semibold);
    text-decoration: underline;
  }

  .reply {
    padding: 8px 10px;
    border-radius: var(--radius-sm);
    background: var(--color-bg);
    color: var(--color-text-2);
    font-size: var(--text-sm);
    line-height: 1.6;
  }

  .warn-list {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .warn-item {
    display: flex;
    align-items: flex-start;
    gap: 5px;
    color: #b45309;
    font-size: var(--text-sm);
    line-height: 1.5;
  }

  .op-list {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .op-item {
    display: flex;
    align-items: flex-start;
    gap: var(--space-2);
    padding: 8px 10px;
    border: 1px solid var(--color-border-2);
    border-radius: var(--radius-sm);
    background: var(--color-card);
  }

  .op-item.danger {
    border-color: #fca5a5;
    background: #fff7f7;
  }

  .op-kind {
    flex-shrink: 0;
    padding: 1px 7px;
    border-radius: var(--radius-full);
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-size: var(--text-xs);
    font-weight: var(--weight-semibold);
    white-space: nowrap;
  }

  .op-item.danger .op-kind {
    background: #fee2e2;
    color: var(--color-danger);
  }

  .op-main {
    min-width: 0;
  }

  .op-title {
    font-size: var(--text-base);
    font-weight: var(--weight-medium);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .op-detail {
    margin-top: 2px;
    color: var(--color-text-3);
    font-size: var(--text-sm);
    line-height: 1.5;
    overflow-wrap: anywhere;
  }

  .more {
    color: var(--color-text-3);
    font-size: var(--text-sm);
  }

  .confirm-title {
    font-size: var(--text-md);
    font-weight: var(--weight-semibold);
  }

  .web-steps {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .web-step {
    display: flex;
    gap: var(--space-2);
  }

  .step-index {
    width: 20px;
    height: 20px;
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-size: var(--text-xs);
    font-weight: var(--weight-semibold);
  }

  .step-body {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .step-title {
    font-size: var(--text-base);
    font-weight: var(--weight-medium);
  }

  .step-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  :global(.spin) {
    animation: spin 0.9s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
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
      transform: scale(0.97);
    }
    to {
      opacity: 1;
      transform: scale(1);
    }
  }
</style>
