export type Priority = 'high' | 'mid' | 'low';
export type TaskStatus = 'todo' | 'done';
export type Source = 'human' | 'ai' | 'import';
export type DrawerTab = 'detail' | 'history';
export type MainView = 'today' | 'calendar' | 'stats';
export type SyncStatus = 'synced' | 'syncing' | 'offline' | 'retrying';
export type Theme = 'light' | 'dark';
export type Density = 'comfortable' | 'compact';

export type TaskField =
  | 'title'
  | 'notes'
  | 'dueAt'
  | 'remindAt'
  | 'priority'
  | 'status'
  | 'listId'
  | 'tagIds';

export interface Subtask {
  id: string;
  text: string;
  done: boolean;
}

export interface Task {
  id: string;
  title: string;
  notes: string;
  dueAt: string | null;
  remindAt: string | null;
  priority: Priority;
  status: TaskStatus;
  listId: string;
  tagIds: string[];
  subtasks: Subtask[];
  source: Source;
  createdAt: string;
  completedAt: string | null;
  order: number;
  model?: string;
}

export interface TodoList {
  id: string;
  name: string;
  color: string;
  smart?: boolean;
}

export interface Tag {
  id: string;
  name: string;
  bg: string;
  fg: string;
  showInSidebar?: boolean;
}

export type Role = 'owner' | 'editor' | 'viewer';

export interface Member {
  id: string;
  name: string;
  initial: string;
  color: string;
  role: Role;
  email: string;
}

export type DiffKind = 'change' | 'add' | 'remove' | 'set' | 'snapshot';

export interface HistoryDiff {
  kind: DiffKind;
  field: string;
  oldText?: string;
  newText?: string;
  /** 前缀文本，例如 `__tag += `、`priority: ` */
  text?: string;
}

export interface HistoryOp {
  id: string;
  taskId: string;
  taskTitle: string;
  at: number;
  timeLabel: string;
  actor: string;
  source: Source;
  action: string;
  field?: TaskField | 'create' | 'subtask';
  oldValue?: unknown;
  newValue?: unknown;
  diff?: HistoryDiff;
  model?: string;
  undoable?: boolean;
  undone?: boolean;
  system?: boolean;
}

export interface TaskGroup {
  key: 'today' | 'upcoming' | 'done' | 'search';
  title: string;
  tasks: Task[];
}

export interface FloatingStickyState {
  id: string;
  focusTaskId?: string;
}

export interface ToastMessage {
  id: number;
  message: string;
  tone: 'ok' | 'error' | 'info';
}

export interface ContextMenuState {
  x: number;
  y: number;
  taskId: string;
}

export interface Settings {
  theme: Theme;
  density: Density;
  notifications: boolean;
  reminderLead: number;
  dndEnabled: boolean;
  defaultPriority: Priority;
  confirmDelete: boolean;
  autoArchive: boolean;
  syncInterval: number;
  serverUrl: string;
  aiEnabled: boolean;
  aiEndpoint: string;
  aiModel: string;
  aiConfirmThreshold: number;
}

export interface AiPlanItem {
  title: string;
  priority: Priority;
  listId: string;
  dueAt: string | null;
}
