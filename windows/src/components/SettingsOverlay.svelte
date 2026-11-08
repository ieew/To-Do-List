<script lang="ts">
  import {
    Bell,
    ChevronLeft,
    Eye,
    EyeOff,
    Info,
    Keyboard,
    ListTodo,
    Palette,
    RefreshCw,
    Sparkles,
    Users
  } from 'lucide-svelte';
  import { isTauri } from '../lib/ipc';
  import { store } from '../lib/store.svelte';
  import type { Priority, Role, Settings } from '../lib/types';

  type SectionId =
    | 'appearance'
    | 'tasks'
    | 'reminders'
    | 'ai'
    | 'sync'
    | 'members'
    | 'shortcuts'
    | 'about';

  const sections: { id: SectionId; label: string; icon: typeof Palette }[] = [
    { id: 'appearance', label: '外观', icon: Palette },
    { id: 'tasks', label: '任务与清单', icon: ListTodo },
    { id: 'reminders', label: '提醒', icon: Bell },
    { id: 'ai', label: 'AI 助手', icon: Sparkles },
    { id: 'sync', label: '同步与数据', icon: RefreshCw },
    { id: 'members', label: '成员', icon: Users },
    { id: 'shortcuts', label: '快捷键', icon: Keyboard },
    { id: 'about', label: '关于', icon: Info }
  ];

  let active = $state<SectionId>('appearance');
  let draft = $state<Settings>({ ...store.settings });
  let sidebarDraft = $state(store.sidebarWidth);
  let lastOpen = false;

  let apiKeyInput = $state('');
  let showKey = $state(false);
  let keySaving = $state(false);
  let confirmClearKey = $state(false);

  let inviteName = $state('');
  let inviteEmail = $state('');
  let inviteRole = $state<Role>('editor');
  let inviting = $state(false);
  let confirmRemoveId = $state<string | null>(null);

  const roleLabels: Record<Role, string> = {
    owner: '所有者',
    editor: '可编辑',
    viewer: '只读'
  };

  const priorities: { value: Priority; label: string }[] = [
    { value: 'high', label: '高' },
    { value: 'mid', label: '中' },
    { value: 'low', label: '低' }
  ];

  const shortcutRows = [
    ['Esc', '关闭抽屉 / 对话框'],
    ['Ctrl + N', '新建任务'],
    ['Ctrl + F', '聚焦搜索'],
    ['Ctrl + Shift + T', '主窗口 ⇄ 便利贴'],
    ['Ctrl + ,', '打开设置'],
    ['Ctrl + Enter', '保存详情'],
    ['↑ / ↓', '在任务列表内移动焦点'],
    ['Enter', '打开焦点任务详情'],
    ['Space', '切换焦点任务完成态']
  ];

  const dirty = $derived(
    JSON.stringify(draft) !== JSON.stringify(store.settings) ||
      sidebarDraft !== store.sidebarWidth
  );

  $effect(() => {
    if (store.settingsOpen === lastOpen) return;
    lastOpen = store.settingsOpen;
    if (store.settingsOpen) {
      draft = { ...store.settings };
      sidebarDraft = store.sidebarWidth;
      active = (store.settingsSection as SectionId) || 'appearance';
      apiKeyInput = '';
      showKey = false;
      confirmClearKey = false;
      inviteName = '';
      inviteEmail = '';
      inviteRole = 'editor';
      confirmRemoveId = null;
    } else {
      store.previewTheme(null);
    }
  });

  $effect(() => {
    if (store.settingsOpen) {
      store.previewTheme(draft.theme);
    }
  });

  function close(): void {
    store.previewTheme(null);
    store.settingsOpen = false;
  }

  async function save(): Promise<void> {
    await store.saveSettings({ ...draft });
    store.sidebarWidth = Math.min(300, Math.max(150, sidebarDraft));
    store.previewTheme(null);
  }

  function cancel(): void {
    draft = { ...store.settings };
    sidebarDraft = store.sidebarWidth;
    close();
  }

  async function saveKey(): Promise<void> {
    if (!apiKeyInput.trim() || keySaving) return;
    keySaving = true;
    const ok = await store.setAiKey(apiKeyInput);
    if (ok) {
      apiKeyInput = '';
      showKey = false;
      confirmClearKey = false;
    }
    keySaving = false;
  }

  async function clearKey(): Promise<void> {
    if (!confirmClearKey) {
      confirmClearKey = true;
      return;
    }
    await store.clearAiKey();
    confirmClearKey = false;
    apiKeyInput = '';
  }

  async function changeRole(userId: string, event: Event): Promise<void> {
    const role = (event.currentTarget as HTMLSelectElement).value as Role;
    await store.updateMemberRole(userId, role);
  }

  async function removeMember(userId: string): Promise<void> {
    if (confirmRemoveId !== userId) {
      confirmRemoveId = userId;
      return;
    }
    await store.removeMember(userId);
    confirmRemoveId = null;
  }

  async function invite(): Promise<void> {
    if (!inviteName.trim() || inviting) {
      if (!inviteName.trim()) store.showToast('先填写成员名称', 'error');
      return;
    }
    inviting = true;
    await store.inviteMember(inviteName, inviteEmail, inviteRole);
    inviting = false;
    inviteName = '';
    inviteEmail = '';
  }
</script>

<svelte:window
  onkeydown={(event) => {
    if (!store.settingsOpen) return;
    if ((event.ctrlKey || event.metaKey) && event.key === 's') {
      event.preventDefault();
      save();
    }
  }}
/>

<div class="settings-overlay" class:open={store.settingsOpen} aria-hidden={!store.settingsOpen} inert={!store.settingsOpen}>
  <aside class="settings-nav">
    <div class="settings-nav-head">
      <button class="settings-back" onclick={close}>
        <ChevronLeft size="16" />
        <span>返回</span>
      </button>
    </div>
    <nav class="settings-nav-items" aria-label="设置分类">
      {#each sections as section (section.id)}
        {@const Icon = section.icon}
        <button class="settings-nav-item" class:is-active={active === section.id} onclick={() => (active = section.id)}>
          <Icon size="14" class="nav-icon" />
          <span>{section.label}</span>
        </button>
      {/each}
    </nav>
    <div class="settings-nav-foot">本地优先 · 业务数据存于 SQLite</div>
  </aside>

  <section class="settings-main">
    <header class="settings-header">
      <div class="settings-header-left">
        <h2>{sections.find((section) => section.id === active)?.label}</h2>
        {#if dirty}
          <span class="dirty-badge">未保存</span>
        {/if}
      </div>
      <div class="settings-actions">
        <button class="btn" onclick={cancel}>放弃</button>
        <button class="btn btn-primary" disabled={!dirty} onclick={save}>保存</button>
      </div>
    </header>

    <div class="settings-content">
      {#if active === 'appearance'}
        <div class="settings-panel">
          <p class="panel-desc">视觉参数全部来自设计令牌；切换主题会立即预览，保存后写入本地偏好。</p>

          <div class="setting-group">
            <div class="setting-group-title">主题</div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">颜色模式</div>
                <div class="setting-desc">暗色映射来自 design-tokens.md 1.4，MVP 不跟随系统自动切换。</div>
              </div>
              <div class="setting-control">
                <div class="segmented">
                  <button class:is-active={draft.theme === 'light'} onclick={() => (draft.theme = 'light')}>浅色</button>
                  <button class:is-active={draft.theme === 'dark'} onclick={() => (draft.theme = 'dark')}>暗色</button>
                </div>
              </div>
            </div>
          </div>

          <div class="setting-group">
            <div class="setting-group-title">布局</div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">界面密度</div>
                <div class="setting-desc">紧凑模式会缩小任务行内边距，适合小屏或高负载场景。</div>
              </div>
              <div class="setting-control">
                <div class="segmented">
                  <button class:is-active={draft.density === 'comfortable'} onclick={() => (draft.density = 'comfortable')}>舒适</button>
                  <button class:is-active={draft.density === 'compact'} onclick={() => (draft.density = 'compact')}>紧凑</button>
                </div>
              </div>
            </div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">侧栏宽度</div>
                <div class="setting-desc">也可直接拖动侧栏右边缘，双击手柄恢复 198px。</div>
              </div>
              <div class="setting-control">
                <input type="range" min="150" max="300" step="1" bind:value={sidebarDraft} aria-label="侧栏宽度" />
                <span class="range-value">{sidebarDraft}px</span>
              </div>
            </div>
          </div>

          <div class="info-banner">
            <Info size="13" class="info-icon" />
            <span>原型中侧栏宽度保存在内存；生产实现写入客户端 SQLite 本地偏好表，不进入 op_log。</span>
          </div>
        </div>
      {:else if active === 'tasks'}
        <div class="settings-panel">
          <p class="panel-desc">任务字段与 op 管道保持一致；勾选、编辑都通过命令写入，不在前端直接改投影。</p>

          <div class="setting-group">
            <div class="setting-group-title">默认值</div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">新任务默认优先级</div>
                <div class="setting-desc">快速添加时预选，可在创建前修改。</div>
              </div>
              <div class="setting-control">
                <div class="segmented">
                  {#each priorities as item (item.value)}
                    <button class:is-active={draft.defaultPriority === item.value} onclick={() => (draft.defaultPriority = item.value)}>
                      <span class="priority-dot {item.value}"></span>
                      {item.label}
                    </button>
                  {/each}
                </div>
              </div>
            </div>
          </div>

          <div class="setting-group">
            <div class="setting-group-title">行为</div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">删除前确认</div>
                <div class="setting-desc">详情抽屉中第一次点击删除会进入确认态。</div>
              </div>
              <div class="setting-control">
                <label class="toggle">
                  <input type="checkbox" bind:checked={draft.confirmDelete} />
                  <span class="toggle-track"><span class="toggle-thumb"></span></span>
                </label>
              </div>
            </div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">自动归档已完成任务</div>
                <div class="setting-desc">完成超过 30 天的任务移入归档，仍可通过历史恢复。</div>
              </div>
              <div class="setting-control">
                <label class="toggle">
                  <input type="checkbox" bind:checked={draft.autoArchive} />
                  <span class="toggle-track"><span class="toggle-thumb"></span></span>
                </label>
              </div>
            </div>
          </div>
        </div>
      {:else if active === 'reminders'}
        <div class="settings-panel">
          <p class="panel-desc">提醒是设备本地态：last_fired_at 与 snooze_until 不进 op_log，不同步。</p>

          <div class="setting-group">
            <div class="setting-group-title">通知</div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">桌面通知</div>
                <div class="setting-desc">使用 tauri-plugin-notifications 发送系统通知。</div>
              </div>
              <div class="setting-control">
                <label class="toggle">
                  <input type="checkbox" bind:checked={draft.notifications} />
                  <span class="toggle-track"><span class="toggle-thumb"></span></span>
                </label>
              </div>
            </div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">提前提醒</div>
                <div class="setting-desc">在截止时间前多久触发第一条通知。</div>
              </div>
              <div class="setting-control">
                <select class="field-select compact-select" bind:value={draft.reminderLead}>
                  <option value={5}>5 分钟</option>
                  <option value={10}>10 分钟</option>
                  <option value={15}>15 分钟</option>
                  <option value={30}>30 分钟</option>
                  <option value={60}>1 小时</option>
                </select>
              </div>
            </div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">免打扰</div>
                <div class="setting-desc">开启后 22:00–08:00 只写入托盘角标，不弹通知。</div>
              </div>
              <div class="setting-control">
                <label class="toggle">
                  <input type="checkbox" bind:checked={draft.dndEnabled} />
                  <span class="toggle-track"><span class="toggle-thumb"></span></span>
                </label>
              </div>
            </div>
          </div>
        </div>
      {:else if active === 'ai'}
        <div class="settings-panel">
          <p class="panel-desc">AI 在客户端运行，服务端无模型密钥、不感知来源；输出必须通过意图 schema 校验。</p>

          <div class="setting-group">
            <div class="setting-group-title">模型端点</div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">启用 AI 助手</div>
                <div class="setting-desc">关闭后侧栏入口保留，但不会调用模型。</div>
              </div>
              <div class="setting-control">
                <label class="toggle">
                  <input type="checkbox" bind:checked={draft.aiEnabled} />
                  <span class="toggle-track"><span class="toggle-thumb"></span></span>
                </label>
              </div>
            </div>
            <div class="setting-row vertical">
              <div class="setting-label">
                <div class="setting-title">API 端点</div>
                <div class="setting-desc">兼容 OpenAI Chat Completions 的本地服务，例如 Ollama / LM Studio。</div>
              </div>
              <input class="field-input mono-input" bind:value={draft.aiEndpoint} spellcheck="false" />
            </div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">模型名</div>
                <div class="setting-desc">写入历史 op 的 source_meta.model 字段。</div>
              </div>
              <div class="setting-control">
                <input class="field-input compact-input" bind:value={draft.aiModel} spellcheck="false" />
              </div>
            </div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">批量确认阈值</div>
                <div class="setting-desc">影响 ≥ N 条任务时先弹确认摘要，再写入 op。</div>
              </div>
              <div class="setting-control">
                <input class="field-input narrow-input" type="number" min="1" max="100" bind:value={draft.aiConfirmThreshold} />
                <span class="unit">条</span>
              </div>
            </div>
          </div>

          <div class="setting-group">
            <div class="setting-group-title">密钥</div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">
                  API 密钥
                  <span class="key-status" class:is-set={store.aiKeyConfigured} role="status">
                    {store.aiKeyConfigured ? '已配置' : '未配置'}
                  </span>
                </div>
                <div class="setting-desc">
                  仅存于本机安全存储；保存后不回显，不写入 SQLite、日志或 op_log。
                </div>
              </div>
              <div class="setting-control key-control">
                <div class="key-input-wrap">
                  <input
                    class="field-input compact-input mono-input"
                    type={showKey ? 'text' : 'password'}
                    bind:value={apiKeyInput}
                    placeholder={store.aiKeyConfigured ? '已配置，输入新密钥可替换' : '输入 API 密钥'}
                    aria-label="AI API 密钥"
                    autocomplete="off"
                    spellcheck="false"
                  />
                  <button
                    class="icon-button key-eye"
                    type="button"
                    aria-label={showKey ? '隐藏密钥' : '显示密钥'}
                    aria-pressed={showKey}
                    onclick={() => (showKey = !showKey)}
                  >
                    {#if showKey}<EyeOff size="14" />{:else}<Eye size="14" />{/if}
                  </button>
                </div>
                <div class="key-actions">
                  <button
                    class="btn btn-primary"
                    disabled={!apiKeyInput.trim() || keySaving}
                    onclick={saveKey}
                  >
                    保存密钥
                  </button>
                  {#if store.aiKeyConfigured}
                    <button class="btn danger-outline" onclick={clearKey}>
                      {confirmClearKey ? '确认清除' : '清除密钥'}
                    </button>
                  {/if}
                </div>
                {#if !isTauri}
                  <div class="key-hint">浏览器预览：密钥仅保存在当前会话，刷新后丢失。</div>
                {/if}
              </div>
            </div>
          </div>

          <div class="info-banner warning">
            <Info size="13" class="info-icon" />
            <span>图片只发送至用户自配端点，不写入 op_log、不持久化为任务附件、不同步。</span>
          </div>
        </div>
      {:else if active === 'sync'}
        <div class="settings-panel">
          <p class="panel-desc">同步只推送 pending_ops、按 cursor 拉取增量；seq 只表达交付顺序，因果与冲突由 HLC 裁决。</p>

          <div class="setting-group">
            <div class="setting-group-title">连接</div>
            <div class="setting-row vertical">
              <div class="setting-label">
                <div class="setting-title">服务端地址</div>
                <div class="setting-desc">FastAPI 服务端，接口前缀 /v1。</div>
              </div>
              <input class="field-input mono-input" bind:value={draft.serverUrl} spellcheck="false" />
            </div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">前台同步间隔</div>
                <div class="setting-desc">托盘计时循环与前台同步互相独立。</div>
              </div>
              <div class="setting-control">
                <select class="field-select compact-select" bind:value={draft.syncInterval}>
                  <option value={1}>1 分钟</option>
                  <option value={3}>3 分钟</option>
                  <option value={5}>5 分钟</option>
                  <option value={10}>10 分钟</option>
                </select>
              </div>
            </div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">当前状态</div>
                <div class="setting-desc">标题栏同步状态绑定同步循环，不是静态文案。</div>
              </div>
              <div class="setting-control">
                <span class="status-pill" class:is-syncing={store.syncStatus === 'syncing'}>
                  {store.syncStatus === 'synced' ? '已同步' : store.syncStatus === 'syncing' ? '同步中' : store.syncStatus === 'offline' ? '离线' : '重试中'}
                </span>
                <button class="btn" onclick={() => store.syncNow()}>
                  <RefreshCw size="13" />
                  立即同步
                </button>
              </div>
            </div>
          </div>

          <div class="setting-group">
            <div class="setting-group-title">本地数据</div>
            <div class="setting-row vertical">
              <div class="setting-label">
                <div class="setting-title">SQLite 路径</div>
                <div class="setting-desc">op_log 只追加；projection 由 reducer 确定性投影得出。</div>
              </div>
              <div class="code-path">
                {`<软件目录>\\data\\todo.sqlite3`}
                <button class="copy-btn" onclick={() => store.showToast('路径已复制（原型模拟）')}>复制</button>
              </div>
            </div>
            <div class="setting-row">
              <div class="setting-label">
                <div class="setting-title">WAL 模式</div>
                <div class="setting-desc">读走独立连接，写入保持短事务；备份先 checkpoint 再单文件拷贝。</div>
              </div>
              <div class="setting-control"><span class="status-pill">已启用</span></div>
            </div>
          </div>
        </div>
      {:else if active === 'members'}
        <div class="settings-panel">
          <p class="panel-desc">
            当前工作区「{store.activeWorkspace?.name ?? '未命名'}」的成员来自 membership 投影；
            权限最终仍由服务端 Writer Worker 裁决，客户端不做授权判断。
          </p>

          <div class="setting-group">
            <div class="setting-group-title">工作区成员 · {store.members.length}</div>
            <div class="member-list">
              {#each store.members as member (member.id)}
                <div class="member-item">
                  <span class="member-avatar-lg" style="background:{member.color}">{member.initial}</span>
                  <div class="member-info">
                    <div class="member-name-lg">
                      {member.name}
                      {#if member.id === store.currentUserId}<span class="me-chip">我</span>{/if}
                    </div>
                    <div class="member-email">{member.email}</div>
                  </div>
                  <select
                    class="role-select"
                    value={member.role}
                    disabled={member.role === 'owner'}
                    aria-label="{member.name} 的角色"
                    onchange={(event) => changeRole(member.id, event)}
                  >
                    <option value="owner">所有者</option>
                    <option value="editor">可编辑</option>
                    <option value="viewer">只读</option>
                  </select>
                  {#if member.id !== store.currentUserId}
                    <button
                      class="member-remove"
                      class:confirming={confirmRemoveId === member.id}
                      onclick={() => removeMember(member.id)}
                    >
                      {confirmRemoveId === member.id ? '确认移除' : '移除'}
                    </button>
                  {/if}
                </div>
              {/each}
            </div>
          </div>

          <div class="invite-box">
            <div class="setting-group-title">邀请成员</div>
            <div class="invite-grid">
              <input class="field-input" bind:value={inviteName} placeholder="成员名称" maxlength="20" />
              <input class="field-input" bind:value={inviteEmail} placeholder="邮箱（可选）" />
              <select class="field-select" bind:value={inviteRole} aria-label="邀请角色">
                <option value="editor">可编辑</option>
                <option value="viewer">只读</option>
              </select>
              <button class="btn btn-primary" disabled={!inviteName.trim() || inviting} onclick={invite}>
                添加成员
              </button>
            </div>
            <div class="info-banner">
              <Info size="13" class="info-icon" />
              <span>原型阶段写本地 op_log，未连接服务端；真实邀请由服务端发送。</span>
            </div>
          </div>
        </div>
      {:else if active === 'shortcuts'}
        <div class="settings-panel">
          <p class="panel-desc">所有可点击元素支持 Tab 聚焦；焦点环为 0 0 0 2px primary-50。</p>
          <div class="setting-group">
            <table class="shortcut-table">
              <tbody>
                {#each shortcutRows as [keys, action] (keys)}
                  <tr>
                    <td>{action}</td>
                    <td><span class="kbd">{keys}</span></td>
                  </tr>
                {/each}
              </tbody>
            </table>
          </div>
        </div>
      {:else}
        <div class="settings-panel">
          <div class="about-hero">
            <div class="about-mark">✓</div>
            <div>
              <h3>待办清单</h3>
              <p>Windows 端 UI 原型 · v0.1.0</p>
            </div>
            <span class="build-chip">Tauri 2 · Svelte 5</span>
          </div>

          <div class="setting-group">
            <div class="setting-group-title">架构摘要</div>
            <div class="about-list">
              <div><strong>状态真相</strong><span>op_log 只追加，投影由确定性 reducer 得出。</span></div>
              <div><strong>冲突裁决</strong><span>字段级 LWW；标签与子任务 OR-Set add-wins。</span></div>
              <div><strong>前端约束</strong><span>不持久化业务数据，不实现合并与权限逻辑。</span></div>
              <div><strong>AI</strong><span>只生成普通 op，source=ai 仅用于审计。</span></div>
            </div>
          </div>

          <div class="info-banner">
            <Info size="13" class="info-icon" />
            <span>当前为交互原型，任务数据在内存中；接入 Rust 后所有读写经 typed IPC commands。</span>
          </div>
        </div>
      {/if}
    </div>
  </section>
</div>

<style>
  .settings-overlay {
    position: absolute;
    inset: 0;
    z-index: var(--z-modal);
    display: flex;
    background: var(--color-bg);
    transform: translateX(100%);
    transition: transform var(--duration-slow) var(--ease-standard);
    pointer-events: none;
  }

  .settings-overlay.open {
    transform: translateX(0);
    pointer-events: auto;
  }

  .settings-nav {
    width: 200px;
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    padding: var(--space-3) 10px;
    background: var(--color-sidebar);
    border-right: 1px solid var(--color-border);
    overflow-y: auto;
  }

  .settings-nav-head {
    padding: 4px 6px var(--space-3);
    margin-bottom: var(--space-2);
    border-bottom: 1px solid var(--color-border-2);
  }

  .settings-back {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 6px 8px;
    border: none;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--color-text-2);
    font-size: var(--text-base);
    font-weight: var(--weight-medium);
  }

  .settings-back:hover {
    background: #eef2f7;
    color: var(--color-text);
  }

  .settings-nav-items {
    display: flex;
    flex-direction: column;
    gap: 1px;
  }

  .settings-nav-item {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: 8px 10px;
    border: none;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--color-text-2);
    font-size: var(--text-base);
    text-align: left;
    white-space: nowrap;
  }

  .settings-nav-item:hover {
    background: #eef2f7;
  }

  .settings-nav-item.is-active {
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-weight: var(--weight-semibold);
  }

  .nav-icon {
    flex-shrink: 0;
  }

  .settings-nav-foot {
    margin-top: auto;
    padding: var(--space-3) 8px 4px;
    color: var(--color-text-3);
    font-size: 9.5px;
    line-height: 1.5;
  }

  .settings-main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    background: var(--color-bg);
  }

  .settings-header {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: 14px var(--space-5);
    border-bottom: 1px solid var(--color-border);
    background: var(--color-card);
  }

  .settings-header-left {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
  }

  .settings-header h2 {
    margin: 0;
    font-size: var(--text-lg);
    font-weight: var(--weight-bold);
    letter-spacing: -0.01em;
  }

  .dirty-badge {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 2px 8px;
    border-radius: var(--radius-full);
    background: #fef3c7;
    color: var(--color-warning);
    font-size: 10.5px;
    font-weight: var(--weight-medium);
  }

  .dirty-badge::before {
    content: '';
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: var(--color-warning);
  }

  .settings-actions {
    display: flex;
    gap: var(--space-2);
    flex-shrink: 0;
  }

  .settings-content {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: var(--space-5);
  }

  .settings-panel {
    animation: fade-in var(--duration-base) var(--ease-standard);
  }

  .panel-desc {
    max-width: 640px;
    margin: 0 0 var(--space-4);
    color: var(--color-text-2);
    font-size: var(--text-base);
    line-height: 1.7;
  }

  .setting-group {
    margin-bottom: 14px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    background: var(--color-card);
    overflow: hidden;
  }

  .setting-group-title {
    padding: 12px var(--space-4) 0;
    font-size: 10.5px;
    font-weight: var(--weight-semibold);
    letter-spacing: 0.05em;
    text-transform: uppercase;
    color: var(--color-text-3);
  }

  .setting-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-4);
    padding: 12px var(--space-4);
    border-bottom: 1px solid var(--color-border-2);
  }

  .setting-row:last-child {
    border-bottom: none;
  }

  .setting-row.vertical {
    flex-direction: column;
    align-items: stretch;
    gap: var(--space-2);
  }

  .setting-label {
    flex: 1;
    min-width: 0;
  }

  .setting-title {
    font-size: var(--text-base);
    font-weight: var(--weight-medium);
    line-height: 1.4;
    color: var(--color-text);
  }

  .setting-desc {
    margin-top: 2px;
    color: var(--color-text-3);
    font-size: var(--text-sm);
    line-height: 1.5;
  }

  .setting-control {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    flex-shrink: 0;
  }

  .compact-select {
    width: 118px;
    min-width: 0;
    padding-top: 5px;
    padding-bottom: 5px;
  }

  .compact-input {
    width: 150px;
  }

  .narrow-input {
    width: 70px;
    text-align: right;
  }

  .mono-input {
    font-family: var(--font-mono);
    font-size: 11px;
  }

  .unit {
    font-size: var(--text-sm);
    color: var(--color-text-3);
  }

  .segmented button {
    display: inline-flex;
    align-items: center;
    gap: 5px;
  }

  .range-value {
    min-width: 42px;
    text-align: right;
    font-family: var(--font-mono);
    font-size: 11.5px;
    font-weight: var(--weight-semibold);
    color: var(--color-text-2);
  }

  input[type='range'] {
    width: 110px;
    height: 4px;
    appearance: none;
    border-radius: var(--radius-full);
    background: var(--color-border);
    outline: none;
  }

  input[type='range']::-webkit-slider-thumb {
    appearance: none;
    width: 14px;
    height: 14px;
    border: 2px solid #fff;
    border-radius: 50%;
    background: var(--color-primary);
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
    cursor: pointer;
  }

  .info-banner {
    display: flex;
    gap: var(--space-2);
    margin-bottom: 14px;
    padding: 9px var(--space-3);
    border: 1px solid #bfdbfe;
    border-radius: var(--radius-sm);
    background: var(--color-primary-50);
    color: var(--color-text-2);
    font-size: var(--text-sm);
    line-height: 1.6;
  }

  .info-banner.warning {
    border-color: #fcd34d;
    background: #fef3c7;
    color: #92400e;
  }

  .info-icon {
    flex-shrink: 0;
    margin-top: 1px;
  }

  .status-pill {
    display: inline-flex;
    align-items: center;
    padding: 2px 8px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-full);
    background: var(--color-border-2);
    color: var(--color-text-2);
    font-size: var(--text-sm);
    font-weight: var(--weight-medium);
  }

  .status-pill.is-syncing {
    border-color: #bfdbfe;
    color: var(--color-primary);
  }

  .code-path {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: 7px 11px;
    border: 1px solid var(--color-border);
    border-radius: var(--radius-sm);
    background: var(--color-border-2);
    color: var(--color-text-2);
    font-family: var(--font-mono);
    font-size: 11px;
    word-break: break-all;
  }

  .copy-btn {
    margin-left: auto;
    flex-shrink: 0;
    border: none;
    background: none;
    color: var(--color-primary);
    font-family: var(--font-sans);
    font-size: 10.5px;
    font-weight: var(--weight-semibold);
  }

  .member-list {
    display: flex;
    flex-direction: column;
  }

  .member-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 9px var(--space-4);
    border-bottom: 1px solid var(--color-border-2);
  }

  .member-item:last-child {
    border-bottom: none;
  }

  .member-avatar-lg {
    width: 30px;
    height: 30px;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    color: #fff;
    font-size: var(--text-base);
    font-weight: var(--weight-semibold);
  }

  .member-info {
    flex: 1;
    min-width: 0;
  }

  .member-name-lg {
    font-size: var(--text-base);
    font-weight: var(--weight-medium);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .member-email {
    font-size: 10.5px;
    color: var(--color-text-3);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .role-badge-lg {
    flex-shrink: 0;
    padding: 2px 8px;
    border-radius: var(--radius-full);
    background: var(--color-border-2);
    color: var(--color-text-2);
    font-size: 9.5px;
    font-weight: var(--weight-medium);
  }

  .role-badge-lg.owner {
    background: #fef3c7;
    color: #b45309;
  }

  .role-badge-lg.editor {
    background: var(--color-primary-50);
    color: var(--color-primary);
  }

  .invite-btn {
    margin-bottom: 14px;
  }

  .shortcut-table {
    width: 100%;
    border-collapse: collapse;
  }

  .shortcut-table tr {
    border-bottom: 1px solid var(--color-border-2);
  }

  .shortcut-table tr:last-child {
    border-bottom: none;
  }

  .shortcut-table td {
    padding: 9px var(--space-4);
    font-size: var(--text-base);
    color: var(--color-text-2);
  }

  .shortcut-table td:last-child {
    text-align: right;
  }

  .about-hero {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    margin-bottom: var(--space-4);
    padding: var(--space-4);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-md);
    background: var(--color-card);
  }

  .about-mark {
    width: 44px;
    height: 44px;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: var(--radius-md);
    background: linear-gradient(135deg, var(--color-primary), var(--color-success));
    color: #fff;
    font-size: 20px;
    font-weight: var(--weight-bold);
  }

  .about-hero h3 {
    margin: 0;
    font-size: var(--text-lg);
    font-weight: var(--weight-bold);
  }

  .about-hero p {
    margin: 3px 0 0;
    font-size: var(--text-sm);
    color: var(--color-text-3);
  }

  .build-chip {
    margin-left: auto;
    padding: 3px 9px;
    border-radius: var(--radius-full);
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-size: var(--text-sm);
    font-weight: var(--weight-semibold);
  }

  .about-list {
    display: flex;
    flex-direction: column;
  }

  .about-list div {
    display: flex;
    gap: var(--space-3);
    padding: 9px var(--space-4);
    border-bottom: 1px solid var(--color-border-2);
    font-size: var(--text-base);
  }

  .about-list div:last-child {
    border-bottom: none;
  }

  .about-list strong {
    width: 74px;
    flex-shrink: 0;
    font-weight: var(--weight-semibold);
    color: var(--color-text-2);
  }

  .about-list span {
    color: var(--color-text-3);
    line-height: 1.6;
  }

  .key-status {
    margin-left: 6px;
    padding: 1px 7px;
    border-radius: var(--radius-full);
    background: var(--color-border-2);
    color: var(--color-text-3);
    font-size: 9.5px;
    font-weight: var(--weight-semibold);
  }

  .key-status.is-set {
    background: var(--color-success-50);
    color: var(--color-success);
  }

  .key-control {
    flex-direction: column;
    align-items: stretch;
    gap: var(--space-2);
    min-width: 220px;
  }

  .key-input-wrap {
    display: flex;
    align-items: center;
    gap: var(--space-1);
  }

  .key-eye {
    flex-shrink: 0;
  }

  .key-actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-2);
  }

  .key-hint {
    font-size: 10.5px;
    color: var(--color-text-3);
    text-align: right;
  }

  .danger-outline {
    border-color: var(--color-danger-border);
    background: var(--color-danger-50);
    color: var(--color-danger);
  }

  .role-select {
    flex-shrink: 0;
    padding: 3px 6px;
    border: 1px solid var(--color-border);
    border-radius: 6px;
    background: var(--color-card);
    color: var(--color-text-2);
    font-size: var(--text-sm);
  }

  .member-remove {
    flex-shrink: 0;
    padding: 3px 8px;
    border: 1px solid transparent;
    border-radius: 6px;
    background: transparent;
    color: var(--color-text-3);
    font-size: var(--text-sm);
  }

  .member-remove:hover,
  .member-remove.confirming {
    border-color: var(--color-danger-border);
    background: var(--color-danger-50);
    color: var(--color-danger);
  }

  .me-chip {
    margin-left: 5px;
    padding: 0 5px;
    border-radius: var(--radius-full);
    background: var(--color-primary-50);
    color: var(--color-primary);
    font-size: 9px;
  }

  .invite-box {
    margin-top: var(--space-4);
    padding: var(--space-3) var(--space-4);
    border: 1px dashed var(--color-border);
    border-radius: var(--radius-md);
  }

  .invite-grid {
    display: grid;
    grid-template-columns: 1fr 1.2fr auto auto;
    gap: var(--space-2);
    margin: var(--space-2) 0;
  }

  .invite-grid .field-input,
  .invite-grid .field-select {
    min-width: 0;
  }

  @keyframes fade-in {
    from {
      opacity: 0;
      transform: translateY(6px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }

  :global(html[data-theme='dark']) .settings-back:hover,
  :global(html[data-theme='dark']) .settings-nav-item:hover {
    background: var(--color-card);
    color: var(--color-text);
  }

  :global(html[data-theme='dark']) .info-banner.warning {
    border-color: rgba(245, 158, 11, 0.3);
    background: rgba(245, 158, 11, 0.12);
    color: #fcd34d;
  }

  :global(html[data-theme='dark']) .dirty-badge,
  :global(html[data-theme='dark']) .role-badge-lg.owner {
    background: rgba(245, 158, 11, 0.16);
    color: #fcd34d;
  }
</style>
