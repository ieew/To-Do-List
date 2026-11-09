import {
  defaultSettings,
  presetLists,
  presetTags,
  seedHistory,
  seedLists,
  seedMembers,
  seedTags,
  seedTasks,
  seedWorkspaces
} from './demo-data';
import { errorMessage, ipc, isTauri } from './ipc';
import type {
  AiPlanItem,
  BootstrapResponse,
  ContextMenuState,
  Density,
  DrawerTab,
  FloatingStickyState,
  HistoryOp,
  MainView,
  Member,
  Priority,
  Role,
  Settings,
  Source,
  Subtask,
  SyncStatus,
  Tag,
  Task,
  TaskField,
  TaskGroup,
  Theme,
  TodoList,
  ToastMessage,
  Workspace
} from './types';
import {
  cloneTask,
  formatDueLong,
  isDueToday,
  priorityLabel,
  statusLabel,
  timeLabelNow,
  truncate,
  uid
} from './utils';

export interface NewTaskInput {
  title: string;
  notes?: string;
  dueAt?: string | null;
  remindAt?: string | null;
  priority?: Priority;
  listId?: string;
  tagIds?: string[];
  source?: Source;
  subtasks?: string[];
  model?: string;
}

function fieldValue(task: Task, field: TaskField): unknown {
  switch (field) {
    case 'title':
      return task.title;
    case 'notes':
      return task.notes;
    case 'dueAt':
      return task.dueAt;
    case 'remindAt':
      return task.remindAt;
    case 'priority':
      return task.priority;
    case 'status':
      return task.status;
    case 'listId':
      return task.listId;
    case 'tagIds':
      return [...task.tagIds];
  }
}

function assignField(task: Task, field: TaskField, value: unknown): void {
  switch (field) {
    case 'title':
      task.title = String(value ?? '未命名任务');
      break;
    case 'notes':
      task.notes = String(value ?? '');
      break;
    case 'dueAt':
      task.dueAt = (value as string | null) ?? null;
      break;
    case 'remindAt':
      task.remindAt = (value as string | null) ?? null;
      break;
    case 'priority':
      task.priority = value as Priority;
      break;
    case 'status': {
      task.status = value as Task['status'];
      task.completedAt = task.status === 'done' ? new Date().toISOString() : null;
      break;
    }
    case 'listId':
      task.listId = String(value);
      break;
    case 'tagIds':
      task.tagIds = [...((value as string[]) ?? [])];
      break;
  }
}

class TodoStore {
  /* 浏览器预览使用内存投影；Tauri 模式启动后由 bootstrap 覆盖。 */
  tasks = $state<Task[]>(seedTasks());
  lists = $state<TodoList[]>(seedLists);
  tags = $state<Tag[]>(seedTags);
  allMembers = $state<Member[]>(seedMembers());
  history = $state<HistoryOp[]>(seedHistory());

  workspaces = $state<Workspace[]>(seedWorkspaces);
  activeWorkspaceId = $state('ws-me');
  currentUserId = $state('u-me');

  activeListId = $state(seedLists[0]?.id ?? 'today');
  activeTagId = $state<string | null>(null);
  view = $state<MainView>('today');
  search = $state('');
  loading = $state(false);
  loadError = $state<string | null>(null);

  selectedTaskId = $state<string | null>(null);
  drawerOpen = $state(false);
  drawerTab = $state<DrawerTab>('detail');
  globalHistory = $state(false);

  collapsedGroups = $state<Record<string, boolean>>({ done: false });

  stickyMode = $state(false);
  floatingStickies = $state<FloatingStickyState[]>([]);

  settingsOpen = $state(false);
  settingsSection = $state('appearance');
  quickAddOpen = $state(false);
  aiOpen = $state(false);
  aiKeyConfigured = $state(!isTauri);

  sidebarWidth = $state(180);
  themePreview = $state<Theme | null>(null);
  settings = $state<Settings>({ ...defaultSettings });

  syncStatus = $state<SyncStatus>('synced');
  contextMenu = $state<ContextMenuState | null>(null);
  toast = $state<ToastMessage | null>(null);
  private toastTimer: ReturnType<typeof setTimeout> | null = null;
  private projectionUnlisten: (() => void) | null = null;

  constructor() {
    // 原型数据同步生成，默认直接出内容；仅 ?skeleton=1 时演示骨架屏。
    if (
      !isTauri &&
      typeof location !== 'undefined' &&
      new URLSearchParams(location.search).has('skeleton')
    ) {
      this.loading = true;
      setTimeout(() => {
        this.loading = false;
      }, 1200);
    }
  }

  /* ------------------------------- 初始化 ------------------------------- */

  async init(): Promise<void> {
    if (!isTauri) return;
    this.loading = true;
    this.loadError = null;
    try {
      const response = await ipc.bootstrap();
      this.applyProjection(response, true);
      if (!this.projectionUnlisten) {
        this.projectionUnlisten = await ipc.onProjectionUpdated((workspaceId) => {
          if (!workspaceId || workspaceId === this.activeWorkspaceId) {
            void this.refresh();
          }
        });
      }
    } catch (error) {
      this.loadError = errorMessage(error);
    } finally {
      this.loading = false;
    }
  }

  private applyProjection(response: BootstrapResponse, resetSelection: boolean): void {
    const projection = response.projection;
    const previousWorkspace = this.activeWorkspaceId;

    this.workspaces = projection.workspaces;
    this.activeWorkspaceId = projection.activeWorkspaceId;
    this.currentUserId = projection.currentUserId;
    this.lists = projection.lists;
    this.tags = projection.tags;
    this.tasks = projection.tasks;
    this.history = projection.history;
    this.allMembers = projection.members;
    this.settings = { ...response.settings };
    this.aiKeyConfigured = response.aiKeyConfigured;

    if (!this.lists.some((list) => list.id === this.activeListId)) {
      this.activeListId = this.todayListId;
    }
    if (resetSelection || previousWorkspace !== this.activeWorkspaceId) {
      this.selectedTaskId = null;
      this.drawerOpen = false;
      this.activeTagId = null;
      this.search = '';
      this.globalHistory = false;
    }
  }

  async refresh(): Promise<void> {
    if (!isTauri) return;
    try {
      const response = await ipc.bootstrap(this.activeWorkspaceId);
      this.applyProjection(response, false);
    } catch (error) {
      this.showToast(errorMessage(error), 'error');
    }
  }

  /* ------------------------------- 读取 ------------------------------- */

  get theme(): Theme {
    return this.themePreview ?? this.settings.theme;
  }

  get density(): Density {
    return this.settings.density;
  }

  get members(): Member[] {
    return this.allMembers.filter(
      (member) => !member.workspaceId || member.workspaceId === this.activeWorkspaceId
    );
  }

  get activeWorkspace(): Workspace | undefined {
    return this.workspaces.find((workspace) => workspace.id === this.activeWorkspaceId);
  }

  get todayListId(): string {
    return this.lists.find((list) => list.smart)?.id ?? this.lists[0]?.id ?? 'today';
  }

  taskById(id: string | null): Task | undefined {
    if (!id) return undefined;
    return this.tasks.find((task) => task.id === id);
  }

  get selectedTask(): Task | undefined {
    return this.taskById(this.selectedTaskId);
  }

  listById(id: string): TodoList | undefined {
    return this.lists.find((list) => list.id === id);
  }

  listName(id: string): string {
    return this.listById(id)?.name ?? '未分类';
  }

  tagById(id: string): Tag | undefined {
    return this.tags.find((tag) => tag.id === id);
  }

  tagName(id: string): string {
    return this.tagById(id)?.name ?? id;
  }

  tagText(id: string): string {
    return `#${this.tagName(id)}`;
  }

  countForList(id: string): number {
    if (id === this.todayListId) return this.tasks.length;
    return this.tasks.filter((task) => task.listId === id).length;
  }

  countForTag(id: string): number {
    return this.tasks.filter((task) => task.tagIds.includes(id)).length;
  }

  get sidebarTags(): Tag[] {
    return this.tags.filter((tag) => tag.showInSidebar);
  }

  get scopedTasks(): Task[] {
    let base = this.tasks;
    if (this.activeListId !== this.todayListId) {
      base = base.filter((task) => task.listId === this.activeListId);
    }
    if (this.activeTagId) {
      base = base.filter((task) => task.tagIds.includes(this.activeTagId as string));
    }
    return [...base].sort((a, b) => a.order - b.order);
  }

  get searchResults(): Task[] | null {
    const query = this.search.trim().toLowerCase();
    if (!query) return null;
    return this.tasks
      .filter((task) => {
        const tagHit = task.tagIds.some((id) => this.tagName(id).toLowerCase().includes(query));
        return (
          task.title.toLowerCase().includes(query) ||
          task.notes.toLowerCase().includes(query) ||
          tagHit
        );
      })
      .sort((a, b) => a.order - b.order);
  }

  get groups(): TaskGroup[] {
    const searchResults = this.searchResults;
    if (searchResults) {
      return [{ key: 'search', title: '搜索结果', tasks: searchResults }];
    }

    const base = this.scopedTasks;
    const today = base.filter((task) => task.status === 'todo' && isDueToday(task.dueAt));
    const upcoming = base.filter((task) => task.status === 'todo' && !isDueToday(task.dueAt));
    const done = base.filter((task) => task.status === 'done');

    const groups: TaskGroup[] = [];
    if (today.length) groups.push({ key: 'today', title: '今天', tasks: today });
    if (upcoming.length) groups.push({ key: 'upcoming', title: '即将到来', tasks: upcoming });
    if (done.length) groups.push({ key: 'done', title: '已完成', tasks: done });
    return groups;
  }

  get hasScopedTasks(): boolean {
    return this.scopedTasks.length > 0;
  }

  get progress(): { done: number; total: number; pct: number } {
    const scope = this.scopedTasks;
    const total = scope.length;
    const done = scope.filter((task) => task.status === 'done').length;
    const pct = total === 0 ? 0 : Math.round((done / total) * 100);
    return { done, total, pct };
  }

  get todayTasks(): Task[] {
    const today = this.tasks.filter((task) => isDueToday(task.dueAt));
    const source = today.length ? today : this.tasks;
    return [...source].sort((a, b) => {
      if (a.status !== b.status) return a.status === 'done' ? 1 : -1;
      return a.order - b.order;
    });
  }

  get todayRemaining(): Task[] {
    return this.todayTasks.filter((task) => task.status === 'todo');
  }

  get activeListName(): string {
    return this.listName(this.activeListId);
  }

  get headerTitle(): string {
    if (this.search.trim()) return '搜索结果';
    return this.activeTagId ? `#${this.tagName(this.activeTagId)}` : this.listName(this.activeListId);
  }

  /* ------------------------------- 视图状态 ------------------------------- */

  setActiveList(id: string): void {
    this.activeListId = id;
    this.activeTagId = null;
    this.globalHistory = false;
    this.view = 'today';
    this.search = '';
  }

  setTagFilter(id: string): void {
    this.activeTagId = this.activeTagId === id ? null : id;
    this.activeListId = this.todayListId;
    this.globalHistory = false;
    this.view = 'today';
  }

  openTask(id: string, tab: DrawerTab = 'detail'): void {
    this.selectedTaskId = id;
    this.drawerOpen = true;
    this.drawerTab = tab;
    this.globalHistory = false;
  }

  closeDrawer(): void {
    this.drawerOpen = false;
    this.selectedTaskId = null;
  }

  toggleGroup(key: string): void {
    this.collapsedGroups[key] = !this.collapsedGroups[key];
  }

  toggleStickyMode(): void {
    this.stickyMode = !this.stickyMode;
    if (this.stickyMode) {
      this.contextMenu = null;
      this.drawerOpen = false;
    }
  }

  showGlobalHistory(): void {
    this.globalHistory = true;
    this.drawerOpen = false;
    this.activeTagId = null;
    this.search = '';
  }

  /* ----------------------------- 工作区 ----------------------------- */

  /** 切换或删除工作区后统一重置作用域内的筛选与浮层状态。 */
  private resetWorkspaceScopedState(): void {
    this.activeTagId = null;
    this.search = '';
    this.globalHistory = false;
    this.drawerOpen = false;
    this.selectedTaskId = null;
    this.view = 'today';
  }

  async setActiveWorkspace(id: string): Promise<void> {
    if (id === this.activeWorkspaceId) return;
    this.activeWorkspaceId = id;
    this.resetWorkspaceScopedState();

    if (isTauri) {
      try {
        await ipc.setActiveWorkspace(id);
        await this.refresh();
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
      }
      return;
    }

    this.applyBrowserWorkspace(id);
  }

  private applyBrowserWorkspace(id: string): void {
    // 浏览器预览与 Tauri 保持一致：预设清单与标签，但不注入任何演示任务。
    this.tasks = [];
    this.history = [];
    const prefix = id === 'ws-me' ? 'me' : id;
    this.lists = presetLists(prefix);
    this.tags = presetTags(prefix);
    this.activeListId = this.todayListId;
  }

  get canDeleteWorkspace(): boolean {
    return this.workspaces.length > 1;
  }

  async createWorkspace(name: string): Promise<Workspace | null> {
    const clean = name.trim();
    if (!clean) {
      this.showToast('工作区名称不能为空', 'error');
      return null;
    }
    if (Array.from(clean).length > 24) {
      this.showToast('工作区名称需为 1–24 个字符', 'error');
      return null;
    }
    if (isTauri) {
      try {
        const created = await ipc.createWorkspace(clean);
        this.activeWorkspaceId = created.id;
        this.resetWorkspaceScopedState();
        await this.refresh();
        this.showToast(`已创建并切换到「${created.name}」`);
        return created;
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
        return null;
      }
    }
    const created: Workspace = { id: uid('ws'), name: clean };
    this.workspaces = [...this.workspaces, created];
    await this.setActiveWorkspace(created.id);
    this.showToast(`已创建并切换到「${clean}」`);
    return created;
  }

  async renameWorkspace(id: string, name: string): Promise<void> {
    const clean = name.trim();
    if (!clean) {
      this.showToast('工作区名称不能为空', 'error');
      return;
    }
    if (Array.from(clean).length > 24) {
      this.showToast('工作区名称需为 1–24 个字符', 'error');
      return;
    }
    if (isTauri) {
      try {
        await ipc.renameWorkspace(id, clean);
        await this.refresh();
        this.showToast('已重命名工作区');
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
      }
      return;
    }
    this.workspaces = this.workspaces.map((workspace) =>
      workspace.id === id ? { ...workspace, name: clean } : workspace
    );
    this.showToast('已重命名工作区');
  }

  async deleteWorkspace(id: string): Promise<boolean> {
    if (this.workspaces.length <= 1) {
      this.showToast('至少需要保留一个工作区', 'error');
      return false;
    }
    const target = this.workspaces.find((workspace) => workspace.id === id);
    if (!target) return false;

    if (isTauri) {
      try {
        const nextActive = await ipc.deleteWorkspace(id);
        if (nextActive !== this.activeWorkspaceId) {
          this.activeWorkspaceId = nextActive;
          this.resetWorkspaceScopedState();
        }
        await this.refresh();
        this.showToast(`已删除工作区「${target.name}」`);
        return true;
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
        return false;
      }
    }

    const remaining = this.workspaces.filter((workspace) => workspace.id !== id);
    this.workspaces = remaining;
    if (this.activeWorkspaceId === id) {
      const next = remaining[0];
      this.activeWorkspaceId = next?.id ?? '';
      this.resetWorkspaceScopedState();
      if (next) this.applyBrowserWorkspace(next.id);
    }
    this.showToast(`已删除工作区「${target.name}」`);
    return true;
  }

  /* ------------------------------- 写入 ------------------------------- */

  private pushOp(input: {
    task: Task;
    action: string;
    source?: Source;
    actor?: string;
    field?: TaskField | 'create' | 'subtask';
    oldValue?: unknown;
    newValue?: unknown;
    diff?: HistoryOp['diff'];
    undoable?: boolean;
    model?: string;
    timeLabel?: string;
  }): void {
    const source = input.source ?? 'human';
    this.history.unshift({
      id: uid('op'),
      taskId: input.task.id,
      taskTitle: input.task.title,
      at: Date.now(),
      timeLabel: input.timeLabel ?? timeLabelNow(),
      actor: input.actor ?? (source === 'ai' ? 'AI 助手' : '我'),
      source,
      action: input.action,
      field: input.field,
      oldValue: input.oldValue,
      newValue: input.newValue,
      diff: input.diff,
      undoable: input.undoable ?? false,
      model: input.model ?? (source === 'ai' ? this.settings.aiModel : undefined)
    });
  }

  async toggleTask(id: string): Promise<void> {
    const task = this.taskById(id);
    if (!task) return;
    const next = task.status === 'done' ? 'todo' : 'done';

    if (isTauri) {
      try {
        await ipc.updateTaskField(id, 'status', next);
        await this.refresh();
        this.showToast(next === 'done' ? '已完成，已移入「已完成」' : '已恢复为待办');
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
      }
      return;
    }

    assignField(task, 'status', next);
    this.pushOp({
      task,
      action: next === 'done' ? '完成了任务' : '恢复为待办',
      field: 'status',
      oldValue: next === 'done' ? 'todo' : 'done',
      newValue: next,
      diff: {
        kind: 'change',
        field: 'status',
        text: 'status: ',
        oldText: statusLabel(next === 'done' ? 'todo' : 'done'),
        newText: statusLabel(next)
      }
    });
    this.showToast(next === 'done' ? '已完成，已移入「已完成」' : '已恢复为待办');
  }

  async createTask(input: NewTaskInput): Promise<Task | null> {
    const source = input.source ?? 'human';
    const title = input.title.trim() || '未命名任务';

    if (isTauri) {
      try {
        const createdId = await ipc.createTask({
          title,
          notes: input.notes ?? '',
          dueAt: input.dueAt ?? null,
          remindAt: input.remindAt ?? null,
          priority: input.priority ?? this.settings.defaultPriority,
          listId: input.listId,
          tagIds: input.tagIds ?? [],
          source,
          subtasks: input.subtasks ?? [],
          model: input.model
        });
        await this.refresh();
        return this.taskById(createdId) ?? null;
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
        return null;
      }
    }

    const task: Task = {
      id: uid('task'),
      title,
      notes: input.notes ?? '',
      dueAt: input.dueAt ?? null,
      remindAt: input.remindAt ?? null,
      priority: input.priority ?? this.settings.defaultPriority,
      status: 'todo',
      listId:
        input.listId ??
        (this.activeListId === this.todayListId
          ? this.lists.find((list) => !list.smart)?.id ?? this.todayListId
          : this.activeListId),
      tagIds: input.tagIds ?? [],
      subtasks: (input.subtasks ?? []).map((text) => ({ id: uid('sub'), text, done: false })),
      source,
      createdAt: new Date().toISOString(),
      completedAt: null,
      order: this.tasks.length,
      model: input.model
    };
    this.tasks.unshift(task);
    this.tasks.forEach((item, index) => {
      item.order = index;
    });
    this.pushOp({
      task,
      action: source === 'ai' ? 'AI 创建了任务' : '创建了任务',
      source,
      field: 'create',
      newValue: task.id,
      undoable: true,
      diff: { kind: 'set', field: 'title', text: 'title = ', newText: `"${truncate(title, 26)}"` }
    });
    return task;
  }

  async saveDraft(draft: Task): Promise<void> {
    const original = this.taskById(draft.id);
    if (!original) return;

    const fields: { field: TaskField; action: string; label: string }[] = [
      { field: 'title', action: '修改了标题', label: 'title' },
      { field: 'notes', action: '修改了备注', label: 'notes' },
      { field: 'dueAt', action: '修改了截止时间', label: 'due_at' },
      { field: 'remindAt', action: '修改了提醒时间', label: 'remind_at' },
      { field: 'priority', action: '修改了优先级', label: 'priority' },
      { field: 'status', action: '更新了完成状态', label: 'status' },
      { field: 'listId', action: '移动了清单', label: 'list_id' }
    ];

    const changes: {
      field: TaskField;
      action: string;
      kind: 'set' | 'change';
      oldText: string;
      newText: string;
      oldValue: unknown;
      newValue: unknown;
    }[] = [];

    for (const meta of fields) {
      const oldValue = fieldValue(original, meta.field);
      const newValue = fieldValue(draft, meta.field);
      if (JSON.stringify(oldValue) === JSON.stringify(newValue)) continue;
      changes.push({
        field: meta.field,
        action: meta.action,
        kind: oldValue === null || oldValue === '' ? 'set' : 'change',
        oldText: this.displayValue(meta.field, oldValue),
        newText: this.displayValue(meta.field, newValue),
        oldValue,
        newValue
      });
    }

    const addedTags = draft.tagIds.filter((id) => !original.tagIds.includes(id));
    const removedTags = original.tagIds.filter((id) => !draft.tagIds.includes(id));

    if (isTauri) {
      try {
        for (const change of changes) {
          await ipc.updateTaskField(original.id, change.field, change.newValue ?? null);
        }
        if (addedTags.length || removedTags.length) {
          await ipc.updateTaskField(original.id, 'tagIds', draft.tagIds);
        }
        const count = changes.length + addedTags.length + removedTags.length;
        await this.refresh();
        this.showToast(count ? `已保存 ${count} 项修改` : '没有需要保存的修改', count ? 'ok' : 'info');
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
      }
      return;
    }

    for (const change of changes) {
      assignField(original, change.field, fieldValue(draft, change.field));
      this.pushOp({
        task: original,
        action: change.action,
        field: change.field,
        oldValue: change.oldValue,
        newValue: change.newValue,
        diff: {
          kind: change.kind,
          field: change.field,
          text:
            change.kind === 'set'
              ? `${this.fieldLabel(change.field)} = `
              : `${this.fieldLabel(change.field)}: `,
          oldText: change.kind === 'change' ? change.oldText : undefined,
          newText: change.newText
        }
      });
    }

    for (const tagId of addedTags) {
      this.pushOp({
        task: original,
        action: '添加了标签',
        diff: { kind: 'add', field: 'tagIds', text: '__tag += ', newText: `#${this.tagName(tagId)}` }
      });
    }
    for (const tagId of removedTags) {
      this.pushOp({
        task: original,
        action: '移除了标签',
        diff: { kind: 'remove', field: 'tagIds', text: '__tag -= ', newText: `#${this.tagName(tagId)}` }
      });
    }
    if (addedTags.length || removedTags.length) {
      original.tagIds = [...draft.tagIds];
    }

    const count = changes.length + addedTags.length + removedTags.length;
    this.showToast(count ? `已保存 ${count} 项修改` : '没有需要保存的修改', count ? 'ok' : 'info');
  }

  private fieldLabel(field: TaskField): string {
    const map: Record<TaskField, string> = {
      title: 'title',
      notes: 'notes',
      dueAt: 'due_at',
      remindAt: 'remind_at',
      priority: 'priority',
      status: 'status',
      listId: 'list_id',
      tagIds: '__tag'
    };
    return map[field];
  }

  private displayValue(field: TaskField, value: unknown): string {
    switch (field) {
      case 'priority':
        return priorityLabel(value as Priority);
      case 'status':
        return statusLabel(value as Task['status']);
      case 'dueAt':
      case 'remindAt':
        return formatDueLong((value as string | null) ?? null);
      case 'listId':
        return this.listName(String(value));
      case 'tagIds':
        return (value as string[]).map((id) => `#${this.tagName(id)}`).join(' ');
      case 'notes':
        return truncate(String(value ?? ''), 22) || '空';
      default:
        return String(value ?? '');
    }
  }

  async deleteTask(id: string): Promise<void> {
    const task = this.taskById(id);
    if (!task) return;
    if (isTauri) {
      try {
        await ipc.deleteTask(id);
        await this.refresh();
        if (this.selectedTaskId === id) this.closeDrawer();
        this.showToast(`已删除「${truncate(task.title, 12)}」`, 'info');
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
      }
      return;
    }
    this.tasks = this.tasks.filter((item) => item.id !== id);
    if (this.selectedTaskId === id) this.closeDrawer();
    this.showToast(`已删除「${truncate(task.title, 12)}」`, 'info');
  }

  async postponeTask(id: string): Promise<void> {
    const task = this.taskById(id);
    if (!task) return;
    const oldValue = task.dueAt;
    const next = new Date();
    next.setDate(next.getDate() + 1);
    if (task.dueAt) {
      const old = new Date(task.dueAt);
      next.setHours(old.getHours(), old.getMinutes(), 0, 0);
    } else {
      next.setHours(18, 0, 0, 0);
    }
    const nextIso = next.toISOString();

    if (isTauri) {
      try {
        await ipc.updateTaskField(id, 'dueAt', nextIso);
        await this.refresh();
        this.showToast('已延期到明天');
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
      }
      return;
    }

    task.dueAt = nextIso;
    this.pushOp({
      task,
      action: '延期到明天',
      field: 'dueAt',
      oldValue,
      newValue: task.dueAt,
      diff: {
        kind: 'change',
        field: 'dueAt',
        text: 'due_at: ',
        oldText: formatDueLong(oldValue),
        newText: formatDueLong(task.dueAt)
      }
    });
    this.showToast('已延期到明天');
  }

  async toggleSubtask(taskId: string, subtaskId: string): Promise<void> {
    const task = this.taskById(taskId);
    const subtask = task?.subtasks.find((item) => item.id === subtaskId);
    if (!task || !subtask) return;

    if (isTauri) {
      try {
        await ipc.toggleSubtask(taskId, subtaskId);
        await this.refresh();
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
      }
      return;
    }

    subtask.done = !subtask.done;
    this.pushOp({
      task,
      action: subtask.done ? '完成了子任务' : '恢复子任务',
      field: 'subtask',
      diff: {
        kind: 'change',
        field: 'subtask',
        text: '__subtask: ',
        oldText: subtask.done ? '未完成' : '已完成',
        newText: subtask.done ? '已完成' : '未完成'
      }
    });
  }

  async addSubtask(taskId: string, text: string): Promise<Subtask | null> {
    const task = this.taskById(taskId);
    const clean = text.trim();
    if (!task || !clean) return null;

    if (isTauri) {
      try {
        const createdId = await ipc.addSubtask(taskId, clean);
        await this.refresh();
        return this.taskById(taskId)?.subtasks.find((item) => item.id === createdId) ?? null;
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
        return null;
      }
    }

    const subtask: Subtask = { id: uid('sub'), text: clean, done: false };
    task.subtasks.push(subtask);
    this.pushOp({
      task,
      action: '添加了子任务',
      diff: {
        kind: 'add',
        field: 'subtask',
        text: '__subtask += ',
        newText: `"${truncate(clean, 18)}"`
      }
    });
    return subtask;
  }

  async removeSubtask(taskId: string, subtaskId: string): Promise<void> {
    const task = this.taskById(taskId);
    if (!task) return;
    const subtask = task.subtasks.find((item) => item.id === subtaskId);
    if (!subtask) return;

    if (isTauri) {
      try {
        await ipc.removeSubtask(taskId, subtaskId);
        await this.refresh();
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
      }
      return;
    }

    task.subtasks = task.subtasks.filter((item) => item.id !== subtaskId);
    this.pushOp({
      task,
      action: '删除了子任务',
      diff: {
        kind: 'remove',
        field: 'subtask',
        text: '__subtask -= ',
        newText: `"${truncate(subtask.text, 18)}"`
      }
    });
  }

  async reorderTask(draggedId: string, targetId: string): Promise<void> {
    if (draggedId === targetId) return;

    if (isTauri) {
      try {
        await ipc.reorderTask(draggedId, targetId);
        await this.refresh();
        this.showToast('已调整任务顺序', 'info');
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
      }
      return;
    }

    const from = this.tasks.findIndex((task) => task.id === draggedId);
    const to = this.tasks.findIndex((task) => task.id === targetId);
    if (from < 0 || to < 0) return;
    const [moved] = this.tasks.splice(from, 1);
    this.tasks.splice(to, 0, moved);
    this.tasks.forEach((task, index) => {
      task.order = index;
    });
    this.showToast('已调整任务顺序', 'info');
  }

  async undoOp(op: HistoryOp): Promise<void> {
    if (!op.field || op.undone) return;

    if (isTauri) {
      try {
        await ipc.undoOp(op.id);
        await this.refresh();
        this.showToast('已生成更高 HLC 的撤销 op');
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
      }
      return;
    }

    const task = this.taskById(op.taskId);
    if (!task) {
      this.showToast('任务已不存在，无法撤销', 'error');
      return;
    }

    this.history.unshift({
      id: uid('op'),
      taskId: task.id,
      taskTitle: task.title,
      at: Date.now(),
      timeLabel: timeLabelNow(),
      actor: '我',
      source: 'human',
      action: '生成了撤销 op',
      diff: { kind: 'set', field: 'undo', text: '反向 op · ', newText: `撤销 ${op.id}` }
    });

    if (op.field === 'create') {
      this.tasks = this.tasks.filter((item) => item.id !== task.id);
      if (this.selectedTaskId === task.id) this.closeDrawer();
    } else if (op.field !== 'subtask') {
      assignField(task, op.field, op.oldValue);
    }

    op.undone = true;
    this.showToast('已生成更高 HLC 的撤销 op');
  }

  /* ------------------------------ 成员 ------------------------------ */

  async updateMemberRole(userId: string, role: Role): Promise<void> {
    if (isTauri) {
      try {
        await ipc.updateMemberRole(this.activeWorkspaceId, userId, role);
        await this.refresh();
        this.showToast('成员角色已更新');
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
      }
      return;
    }
    this.allMembers = this.allMembers.map((member) =>
      member.id === userId && member.workspaceId === this.activeWorkspaceId
        ? { ...member, role }
        : member
    );
    this.showToast('成员角色已更新（预览内存）', 'info');
  }

  async removeMember(userId: string): Promise<void> {
    if (isTauri) {
      try {
        await ipc.removeMember(this.activeWorkspaceId, userId);
        await this.refresh();
        this.showToast('已移除成员');
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
      }
      return;
    }
    this.allMembers = this.allMembers.filter(
      (member) => !(member.id === userId && member.workspaceId === this.activeWorkspaceId)
    );
    this.showToast('已移除成员（预览内存）', 'info');
  }

  async inviteMember(name: string, email: string, role: Role): Promise<void> {
    if (isTauri) {
      try {
        await ipc.inviteMember(this.activeWorkspaceId, name, email, role);
        await this.refresh();
        this.showToast(`已添加成员「${name}」`);
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
      }
      return;
    }
    this.showToast('邀请链接已复制（浏览器预览不持久化）', 'info');
  }

  /* ------------------------------- 便利贴 ------------------------------- */

  async openStickyMainWindow(): Promise<void> {
    if (!isTauri) {
      this.toggleStickyMode();
      return;
    }
    try {
      await ipc.openStickyWindow('main');
    } catch (error) {
      this.showToast(errorMessage(error), 'error');
    }
  }

  async openFloatingStickyWindow(focusTaskId?: string): Promise<void> {
    if (!isTauri) {
      this.addFloatingSticky(focusTaskId);
      return;
    }
    try {
      await ipc.openStickyWindow('floating', focusTaskId);
    } catch (error) {
      this.showToast(errorMessage(error), 'error');
    }
  }

  async closeStickyWindow(label: string): Promise<void> {
    if (!isTauri) return;
    try {
      await ipc.closeStickyWindow(label);
    } catch (error) {
      this.showToast(errorMessage(error), 'error');
    }
  }

  async showMainWindow(): Promise<void> {
    if (!isTauri) {
      this.expandFromSticky();
      return;
    }
    try {
      await ipc.showMainWindow();
    } catch (error) {
      this.showToast(errorMessage(error), 'error');
    }
  }

  addFloatingSticky(focusTaskId?: string): void {
    if (this.floatingStickies.length >= 5) {
      this.showToast('同时最多 5 个便利贴，请先关闭一个', 'error');
      return;
    }
    this.floatingStickies.push({ id: uid('sticky'), focusTaskId });
    this.showToast(focusTaskId ? '已钉为便利贴' : '已打开新的便利贴', 'ok');
  }

  closeFloatingSticky(id: string): void {
    this.floatingStickies = this.floatingStickies.filter((sticky) => sticky.id !== id);
  }

  expandFromSticky(): void {
    this.stickyMode = false;
    this.floatingStickies = [];
  }

  /* ------------------------------- AI ------------------------------- */

  async aiCreateTasks(plan: AiPlanItem[]): Promise<void> {
    for (const item of plan) {
      await this.createTask({
        title: item.title,
        priority: item.priority,
        listId: item.listId,
        dueAt: item.dueAt,
        source: 'ai',
        model: this.settings.aiModel
      });
    }
    this.showToast(`AI 已写入 ${plan.length} 条任务，可撤销`, 'ok');
  }

  async setAiKey(apiKey: string): Promise<boolean> {
    if (!isTauri) {
      this.aiKeyConfigured = apiKey.trim().length > 0;
      this.showToast('预览模式：密钥仅保存在当前会话', 'info');
      return this.aiKeyConfigured;
    }
    try {
      await ipc.setAiKey(apiKey);
      this.aiKeyConfigured = true;
      this.showToast('密钥已保存到本机安全存储');
      return true;
    } catch (error) {
      this.showToast(errorMessage(error), 'error');
      return false;
    }
  }

  async clearAiKey(): Promise<void> {
    if (!isTauri) {
      this.aiKeyConfigured = false;
      this.showToast('已清除当前会话密钥', 'info');
      return;
    }
    try {
      await ipc.clearAiKey();
      this.aiKeyConfigured = false;
      this.showToast('已清除本机密钥');
    } catch (error) {
      this.showToast(errorMessage(error), 'error');
    }
  }

  /* ------------------------------- 设置 ------------------------------- */

  previewTheme(theme: Theme | null): void {
    this.themePreview = theme;
  }

  async saveSettings(next: Settings): Promise<void> {
    this.settings = { ...next };
    this.themePreview = null;
    if (isTauri) {
      try {
        await ipc.saveSettings(next);
      } catch (error) {
        this.showToast(errorMessage(error), 'error');
        return;
      }
    }
    this.showToast('设置已保存');
  }

  /* ------------------------------- 同步与提示 ------------------------------- */

  syncNow(): void {
    if (this.syncStatus === 'syncing') return;
    this.syncStatus = 'syncing';
    setTimeout(() => {
      this.syncStatus = 'synced';
      this.showToast('同步完成，已拉到最新 op');
    }, 900);
  }

  showToast(message: string, tone: ToastMessage['tone'] = 'ok'): void {
    if (this.toastTimer) clearTimeout(this.toastTimer);
    this.toast = { id: Date.now(), message, tone };
    this.toastTimer = setTimeout(() => {
      this.toast = null;
    }, 2600);
  }

  openContextMenu(x: number, y: number, taskId: string): void {
    this.contextMenu = { x, y, taskId };
  }

  closeContextMenu(): void {
    this.contextMenu = null;
  }

  copyDraft(task: Task): Task {
    return cloneTask(task);
  }
}

export const store = new TodoStore();
