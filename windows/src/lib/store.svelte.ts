import { defaultSettings, seedHistory, seedLists, seedTags, seedTasks } from './demo-data';
import type {
  AiPlanItem,
  Density,
  DrawerTab,
  FloatingStickyState,
  HistoryOp,
  MainView,
  Member,
  Priority,
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
  ContextMenuState
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
  tasks = $state<Task[]>(seedTasks());
  lists = $state<TodoList[]>(seedLists);
  tags = $state<Tag[]>(seedTags);
  history = $state<HistoryOp[]>(seedHistory());
  members = $state<Member[]>([
    { id: 'u-me', name: '我', initial: '我', color: '#3B82F6', role: 'owner', email: 'me@todo.local' },
    { id: 'u-ming', name: '小明', initial: '明', color: '#10B981', role: 'editor', email: 'ming@todo.local' },
    { id: 'u-hong', name: '小红', initial: '红', color: '#8B5CF6', role: 'viewer', email: 'hong@todo.local' }
  ]);

  activeListId = $state('today');
  activeTagId = $state<string | null>(null);
  view = $state<MainView>('today');
  search = $state('');
  loading = $state(true);

  selectedTaskId = $state<string | null>(null);
  drawerOpen = $state(false);
  drawerTab = $state<DrawerTab>('detail');
  globalHistory = $state(false);

  collapsedGroups = $state<Record<string, boolean>>({ done: false });

  stickyMode = $state(false);
  floatingStickies = $state<FloatingStickyState[]>([]);

  settingsOpen = $state(false);
  quickAddOpen = $state(false);
  aiOpen = $state(false);

  sidebarWidth = $state(198);
  themePreview = $state<Theme | null>(null);
  settings = $state<Settings>({ ...defaultSettings });

  syncStatus = $state<SyncStatus>('synced');
  contextMenu = $state<ContextMenuState | null>(null);
  toast = $state<ToastMessage | null>(null);
  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    setTimeout(() => {
      this.loading = false;
    }, 420);
  }

  /* ------------------------------- 读取 ------------------------------- */

  get theme(): Theme {
    return this.themePreview ?? this.settings.theme;
  }

  get density(): Density {
    return this.settings.density;
  }

  taskById(id: string | null): Task | undefined {
    if (!id) return undefined;
    return this.tasks.find((t) => t.id === id);
  }

  get selectedTask(): Task | undefined {
    return this.taskById(this.selectedTaskId);
  }

  listById(id: string): TodoList | undefined {
    return this.lists.find((l) => l.id === id);
  }

  listName(id: string): string {
    return this.listById(id)?.name ?? '未分类';
  }

  tagById(id: string): Tag | undefined {
    return this.tags.find((t) => t.id === id);
  }

  tagName(id: string): string {
    return this.tagById(id)?.name ?? id;
  }

  tagText(id: string): string {
    return `#${this.tagName(id)}`;
  }

  countForList(id: string): number {
    if (id === 'today') return this.tasks.length;
    return this.tasks.filter((t) => t.listId === id).length;
  }

  countForTag(id: string): number {
    return this.tasks.filter((t) => t.tagIds.includes(id)).length;
  }

  get sidebarTags(): Tag[] {
    return this.tags.filter((t) => t.showInSidebar);
  }

  get scopedTasks(): Task[] {
    let base = this.tasks;
    if (this.activeListId !== 'today') {
      base = base.filter((t) => t.listId === this.activeListId);
    }
    if (this.activeTagId) {
      base = base.filter((t) => t.tagIds.includes(this.activeTagId as string));
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
    const today = base.filter((t) => t.status === 'todo' && isDueToday(t.dueAt));
    const upcoming = base.filter((t) => t.status === 'todo' && !isDueToday(t.dueAt));
    const done = base.filter((t) => t.status === 'done');

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
    const done = scope.filter((t) => t.status === 'done').length;
    const pct = total === 0 ? 0 : Math.round((done / total) * 100);
    return { done, total, pct };
  }

  get todayTasks(): Task[] {
    const today = this.tasks.filter((t) => isDueToday(t.dueAt));
    const source = today.length ? today : this.tasks;
    return [...source].sort((a, b) => {
      if (a.status !== b.status) return a.status === 'done' ? 1 : -1;
      return a.order - b.order;
    });
  }

  get todayRemaining(): Task[] {
    return this.todayTasks.filter((t) => t.status === 'todo');
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
    this.activeListId = 'today';
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

  toggleTask(id: string): void {
    const task = this.taskById(id);
    if (!task) return;
    const next = task.status === 'done' ? 'todo' : 'done';
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

  createTask(input: NewTaskInput): Task {
    const source = input.source ?? 'human';
    const title = input.title.trim() || '未命名任务';
    const task: Task = {
      id: uid('task'),
      title,
      notes: input.notes ?? '',
      dueAt: input.dueAt ?? null,
      remindAt: input.remindAt ?? null,
      priority: input.priority ?? this.settings.defaultPriority,
      status: 'todo',
      listId: input.listId ?? (this.activeListId === 'today' ? 'work' : this.activeListId),
      tagIds: input.tagIds ?? [],
      subtasks: (input.subtasks ?? []).map((text) => ({ id: uid('sub'), text, done: false })),
      source,
      createdAt: new Date().toISOString(),
      completedAt: null,
      order: this.tasks.length,
      model: input.model
    };
    this.tasks.unshift(task);
    this.tasks.forEach((t, index) => {
      t.order = index;
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

  saveDraft(draft: Task): void {
    const original = this.taskById(draft.id);
    if (!original) return;

    const changes: {
      field: TaskField;
      action: string;
      kind: 'set' | 'change';
      oldText: string;
      newText: string;
      oldValue: unknown;
      newValue: unknown;
    }[] = [];
    const fields: { field: TaskField; action: string; label: string }[] = [
      { field: 'title', action: '修改了标题', label: 'title' },
      { field: 'notes', action: '修改了备注', label: 'notes' },
      { field: 'dueAt', action: '修改了截止时间', label: 'due_at' },
      { field: 'remindAt', action: '修改了提醒时间', label: 'remind_at' },
      { field: 'priority', action: '修改了优先级', label: 'priority' },
      { field: 'status', action: '更新了完成状态', label: 'status' },
      { field: 'listId', action: '移动了清单', label: 'list_id' }
    ];

    for (const meta of fields) {
      const oldValue = fieldValue(original, meta.field);
      const newValue = fieldValue(draft, meta.field);
      if (JSON.stringify(oldValue) === JSON.stringify(newValue)) continue;
      const oldText = this.displayValue(meta.field, oldValue);
      const newText = this.displayValue(meta.field, newValue);
      changes.push({
        field: meta.field,
        action: meta.action,
        kind: oldValue === null || oldValue === '' ? 'set' : 'change',
        oldText,
        newText,
        oldValue,
        newValue
      });
    }

    const addedTags = draft.tagIds.filter((id) => !original.tagIds.includes(id));
    const removedTags = original.tagIds.filter((id) => !draft.tagIds.includes(id));

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
          text: change.kind === 'set' ? `${this.fieldLabel(change.field)} = ` : `${this.fieldLabel(change.field)}: `,
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

  deleteTask(id: string): void {
    const task = this.taskById(id);
    if (!task) return;
    this.tasks = this.tasks.filter((t) => t.id !== id);
    if (this.selectedTaskId === id) this.closeDrawer();
    this.showToast(`已删除「${truncate(task.title, 12)}」`, 'info');
  }

  postponeTask(id: string): void {
    const task = this.taskById(id);
    if (!task) return;
    const oldValue = task.dueAt;
    const d = new Date();
    d.setDate(d.getDate() + 1);
    if (task.dueAt) {
      const old = new Date(task.dueAt);
      d.setHours(old.getHours(), old.getMinutes(), 0, 0);
    } else {
      d.setHours(18, 0, 0, 0);
    }
    task.dueAt = d.toISOString();
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

  toggleSubtask(taskId: string, subtaskId: string): void {
    const task = this.taskById(taskId);
    const subtask = task?.subtasks.find((s) => s.id === subtaskId);
    if (!task || !subtask) return;
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

  addSubtask(taskId: string, text: string): Subtask | null {
    const task = this.taskById(taskId);
    const clean = text.trim();
    if (!task || !clean) return null;
    const subtask: Subtask = { id: uid('sub'), text: clean, done: false };
    task.subtasks.push(subtask);
    this.pushOp({
      task,
      action: '添加了子任务',
      diff: { kind: 'add', field: 'subtask', text: '__subtask += ', newText: `"${truncate(clean, 18)}"` }
    });
    return subtask;
  }

  removeSubtask(taskId: string, subtaskId: string): void {
    const task = this.taskById(taskId);
    if (!task) return;
    const subtask = task.subtasks.find((s) => s.id === subtaskId);
    if (!subtask) return;
    task.subtasks = task.subtasks.filter((s) => s.id !== subtaskId);
    this.pushOp({
      task,
      action: '删除了子任务',
      diff: { kind: 'remove', field: 'subtask', text: '__subtask -= ', newText: `"${truncate(subtask.text, 18)}"` }
    });
  }

  reorderTask(draggedId: string, targetId: string): void {
    if (draggedId === targetId) return;
    const from = this.tasks.findIndex((t) => t.id === draggedId);
    const to = this.tasks.findIndex((t) => t.id === targetId);
    if (from < 0 || to < 0) return;
    const [moved] = this.tasks.splice(from, 1);
    this.tasks.splice(to, 0, moved);
    this.tasks.forEach((task, index) => {
      task.order = index;
    });
    this.showToast('已调整任务顺序', 'info');
  }

  undoOp(op: HistoryOp): void {
    if (!op.field || op.undone) return;
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
      this.tasks = this.tasks.filter((t) => t.id !== task.id);
      if (this.selectedTaskId === task.id) this.closeDrawer();
    } else if (op.field !== 'subtask') {
      assignField(task, op.field, op.oldValue);
    }

    op.undone = true;
    this.showToast('已生成更高 HLC 的撤销 op');
  }

  /* ------------------------------- 便利贴 ------------------------------- */

  addFloatingSticky(focusTaskId?: string): void {
    if (this.floatingStickies.length >= 5) {
      this.showToast('同时最多 5 个便利贴，请先关闭一个', 'error');
      return;
    }
    this.floatingStickies.push({ id: uid('sticky'), focusTaskId });
    this.showToast(focusTaskId ? '已钉为便利贴' : '已打开新的便利贴', 'ok');
  }

  closeFloatingSticky(id: string): void {
    this.floatingStickies = this.floatingStickies.filter((s) => s.id !== id);
  }

  expandFromSticky(): void {
    this.stickyMode = false;
    this.floatingStickies = [];
  }

  /* ------------------------------- AI ------------------------------- */

  aiCreateTasks(plan: AiPlanItem[]): void {
    for (const item of plan) {
      this.createTask({
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

  /* ------------------------------- 设置 ------------------------------- */

  previewTheme(theme: Theme | null): void {
    this.themePreview = theme;
  }

  saveSettings(next: Settings): void {
    this.settings = { ...next };
    this.themePreview = null;
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
