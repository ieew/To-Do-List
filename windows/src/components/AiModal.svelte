<script lang="ts">
  import { Image, Loader2, Sparkles, Wand2, X } from 'lucide-svelte';
  import { store } from '../lib/store.svelte';
  import type { AiPlanItem } from '../lib/types';
  import { plusDaysAt, todayAt } from '../lib/utils';

  let prompt = $state('');
  let step = $state<'input' | 'preview'>('input');
  let generating = $state(false);
  let plan = $state<AiPlanItem[]>([]);
  let lastOpen = false;

  const chips = ['规划今晚的学习', '整理本周工作重点', '把大目标拆成三步'];

  $effect(() => {
    if (store.aiOpen === lastOpen) return;
    lastOpen = store.aiOpen;
    if (store.aiOpen) {
      prompt = '';
      step = 'input';
      plan = [];
      generating = false;
    }
  });

  function buildPlan(text: string): AiPlanItem[] {
    const clean = text.trim();
    if (/学习|复习|读书|课程/.test(clean)) {
      return [
        { title: '整理今晚学习计划', priority: 'high', listId: 'study', dueAt: todayAt(20, 0) },
        { title: '复习第一章要点并做笔记', priority: 'mid', listId: 'study', dueAt: todayAt(21, 0) },
        { title: '完成 10 道练习题', priority: 'low', listId: 'study', dueAt: plusDaysAt(1, 9, 30) }
      ];
    }
    if (/工作|周报|汇报|会议|项目/.test(clean)) {
      return [
        { title: '汇总本周工作进展', priority: 'high', listId: 'work', dueAt: todayAt(18, 0) },
        { title: '整理待跟进事项与风险', priority: 'mid', listId: 'work', dueAt: plusDaysAt(1, 10, 0) },
        { title: '预约下周评审时间', priority: 'low', listId: 'work', dueAt: plusDaysAt(2, 14, 0) }
      ];
    }
    return [
      { title: `拆解目标：${clean.slice(0, 14) || '新目标'}`, priority: 'high', listId: 'work', dueAt: todayAt(18, 0) },
      { title: '为任务补充截止时间与提醒', priority: 'mid', listId: 'work', dueAt: plusDaysAt(1, 10, 0) },
      { title: '检查依赖并标记风险', priority: 'low', listId: 'work', dueAt: plusDaysAt(2, 10, 0) }
    ];
  }

  function generate(): void {
    if (!prompt.trim()) {
      store.showToast('先描述你想让 AI 做什么', 'error');
      return;
    }
    generating = true;
    setTimeout(() => {
      plan = buildPlan(prompt);
      generating = false;
      step = 'preview';
    }, 700);
  }

  function attachImage(): void {
    store.showToast('原型暂未接入本地图片选择，图片不会写入 op_log', 'info');
  }

  function confirmWrite(): void {
    store.aiCreateTasks(plan);
    store.aiOpen = false;
  }

  function close(): void {
    store.aiOpen = false;
  }
</script>

{#if store.aiOpen}
  <div class="modal-backdrop" role="presentation" onclick={(event) => event.target === event.currentTarget && close()}>
    <div class="ai-modal" role="dialog" aria-modal="true" aria-labelledby="ai-title">
      <div class="ai-head">
        <div class="ai-title">
          <span class="spark-orb"><Sparkles size="15" /></span>
          <div>
            <h3 id="ai-title">AI 助手</h3>
            <p>本地模型 · {store.settings.aiModel}</p>
          </div>
        </div>
        <button class="icon-button" aria-label="关闭" onclick={close}><X size="15" /></button>
      </div>

      {#if step === 'input'}
        <div class="ai-body">
          <textarea
            class="ai-textarea"
            placeholder="描述你想完成的事，例如「帮我规划今晚的学习」，AI 只生成普通 op…"
            bind:value={prompt}
          ></textarea>

          <div class="prompt-chips">
            {#each chips as chip (chip)}
              <button class="prompt-chip" onclick={() => (prompt = chip)}>{chip}</button>
            {/each}
          </div>

          <div class="ai-toolbar">
            <button class="attach-btn" onclick={attachImage}>
              <Image size="13" />
              图片输入
            </button>
            <span class="endpoint" title="{store.settings.aiEndpoint}">
              {store.settings.aiEndpoint.replace('http://', '').replace('https://', '')}
            </span>
          </div>

          <p class="ai-note">
            AI 在客户端运行，产物是普通 op，<span class="mono">source="ai"</span>，不参与裁决；服务端不感知来源。输出必须通过意图 schema 校验。
          </p>
        </div>

        <div class="ai-actions">
          <button class="btn" onclick={close}>取消</button>
          <button class="btn btn-primary" disabled={generating} onclick={generate}>
            {#if generating}
              <Loader2 size="14" class="spin" />
              生成中…
            {:else}
              <Wand2 size="14" />
              生成草案
            {/if}
          </button>
        </div>
      {:else}
        <div class="ai-body">
          <div class="plan-summary">
            <Sparkles size="14" />
            <span>AI 想创建 {plan.length} 条任务（低于批量确认阈值 {store.settings.aiConfirmThreshold} 条）</span>
          </div>
          <ul class="plan-list">
            {#each plan as item, index (index)}
              <li>
                <span class="plan-index">{index + 1}</span>
                <div class="plan-text">
                  <span class="plan-title">{item.title}</span>
                  <span class="plan-meta">
                    {store.listName(item.listId)} · {item.priority === 'high' ? '高' : item.priority === 'mid' ? '中' : '低'}优先级
                  </span>
                </div>
              </li>
            {/each}
          </ul>
          <p class="ai-note">确认后走正常 op 管道，写入后可在历史中撤销；不会先写后撤。</p>
        </div>

        <div class="ai-actions">
          <button class="btn" onclick={() => (step = 'input')}>返回修改</button>
          <button class="btn btn-primary" onclick={confirmWrite}>
            <Sparkles size="14" />
            写入 {plan.length} 条任务
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

  .ai-modal {
    width: 480px;
    max-width: 100%;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    background: var(--color-card);
    box-shadow: var(--shadow-xl);
    overflow: hidden;
    animation: pop-in var(--duration-base) var(--ease-standard);
  }

  .ai-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-4) var(--space-5) var(--space-3);
    border-bottom: 1px solid var(--color-border-2);
    background: linear-gradient(135deg, rgba(237, 233, 254, 0.7), rgba(219, 234, 254, 0.7));
  }

  .ai-title {
    display: flex;
    align-items: center;
    gap: var(--space-3);
  }

  .spark-orb {
    width: 32px;
    height: 32px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border-radius: var(--radius-sm);
    background: var(--color-ai-bg);
    color: var(--color-ai-fg);
  }

  .ai-title h3 {
    margin: 0;
    font-size: var(--text-lg);
    font-weight: var(--weight-semibold);
    color: var(--color-text);
  }

  .ai-title p {
    margin: 2px 0 0;
    font-family: var(--font-mono);
    font-size: 10px;
    color: var(--color-text-2);
  }

  .ai-body {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    padding: var(--space-4) var(--space-5);
  }

  .ai-textarea {
    min-height: 104px;
    resize: vertical;
    padding: 10px 12px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-bg);
    color: var(--color-text);
    font-size: var(--text-base);
    line-height: 1.65;
    outline: none;
  }

  .ai-textarea:focus {
    border-color: var(--color-primary);
    box-shadow: 0 0 0 3px var(--color-primary-50);
  }

  .prompt-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .prompt-chip {
    padding: 4px 9px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-full);
    background: var(--color-card);
    color: var(--color-text-2);
    font-size: var(--text-sm);
  }

  .prompt-chip:hover {
    border-color: #bfdbfe;
    background: var(--color-primary-50);
    color: var(--color-primary);
  }

  .ai-toolbar {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding-top: 2px;
  }

  .attach-btn {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 4px 9px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-card);
    color: var(--color-text-2);
    font-size: var(--text-sm);
  }

  .attach-btn:hover {
    border-color: #bfdbfe;
    color: var(--color-primary);
  }

  .endpoint {
    margin-left: auto;
    max-width: 190px;
    padding: 2px 7px;
    border-radius: var(--radius-full);
    background: var(--color-border-2);
    color: var(--color-text-3);
    font-family: var(--font-mono);
    font-size: 9.5px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .ai-note {
    margin: 0;
    padding: var(--space-2) 10px;
    border-radius: 6px;
    background: var(--color-bg);
    color: var(--color-text-3);
    font-size: 10.5px;
    line-height: 1.65;
  }

  .ai-actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-2);
    padding: 0 var(--space-5) var(--space-4);
  }

  .plan-summary {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 10px;
    border: 1px solid #ddd6fe;
    border-radius: var(--radius-sm);
    background: rgba(245, 243, 255, 0.8);
    color: #6d28d9;
    font-size: var(--text-base);
    font-weight: var(--weight-medium);
  }

  .plan-list {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .plan-list li {
    display: flex;
    align-items: flex-start;
    gap: var(--space-2);
    padding: 8px 10px;
    border: 1px solid var(--color-border-2);
    border-radius: var(--radius-sm);
    background: var(--color-bg);
  }

  .plan-index {
    width: 18px;
    height: 18px;
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-family: var(--font-mono);
    font-size: 9.5px;
    font-weight: var(--weight-semibold);
  }

  .plan-text {
    display: flex;
    flex-direction: column;
    gap: 1px;
    min-width: 0;
  }

  .plan-title {
    font-size: var(--text-base);
    font-weight: var(--weight-medium);
    color: var(--color-text);
  }

  .plan-meta {
    font-size: 10px;
    color: var(--color-text-3);
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

  :global(html[data-theme='dark']) .plan-summary {
    border-color: rgba(139, 92, 246, 0.32);
    background: rgba(139, 92, 246, 0.12);
    color: #ddd6fe;
  }
</style>
