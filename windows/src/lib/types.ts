export type Priority = 'high' | 'mid' | 'low';
export type TaskStatus = 'todo' | 'done';
export type Source = 'human' | 'ai' | 'import';
export type DrawerTab = 'detail' | 'history';
export type MainView = 'today' | 'calendar' | 'stats';
export type SyncStatus =
  | 'signed_out'
  | 'syncing'
  | 'synced'
  | 'offline'
  | 'retrying'
  | 'auth_required';
export type Theme = 'light' | 'dark';
export type Density = 'comfortable' | 'compact';
/** 统计视图的时间范围：本周 / 本月 / 近 30 天 / 全部。 */
export type StatsRange = 'week' | 'month' | '30d' | 'all';

export type TaskField =
  | 'title'
  | 'notes'
  | 'startAt'
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
  /** 开始时间；创建任务时默认为创建当天的时间。 */
  startAt: string | null;
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

export interface Workspace {
  id: string;
  name: string;
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
  workspaceId?: string;
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
  actionLabel?: string;
  onAction?: () => void;
}

export interface ContextMenuState {
  x: number;
  y: number;
  taskId: string;
}

export interface Settings {
  theme: Theme;
  density: Density;
  /** 便利贴底色透明度，整数百分比 0–100。 */
  stickyOpacity: number;
  notifications: boolean;
  reminderLead: number;
  dndEnabled: boolean;
  /** 免打扰开始时间，`HH:mm`。 */
  dndStart: string;
  /** 免打扰结束时间，`HH:mm`。 */
  dndEnd: string;
  /** 关闭主窗口时隐藏到托盘。 */
  closeToTray: boolean;
  /** 开机自启。 */
  autoStart: boolean;
  defaultPriority: Priority;
  confirmDelete: boolean;
  autoArchive: boolean;
  syncInterval: number;
  serverUrl: string;
  aiEnabled: boolean;
  aiEndpoint: string;
  aiModel: string;
  aiConfirmThreshold: number;
  /** 允许 AI 修改完成状态与删除任务。 */
  aiAllowDestructive: boolean;
  /** 允许在 AI 助手中附加图片。 */
  aiImageInput: boolean;
  /** 接收 dsh 通道的服务端通知与提醒（需登录）。 */
  pushEnabled: boolean;
  /** 剪贴板双向同步，默认关闭。 */
  clipEnabled: boolean;
}

export interface AiPlanItem {
  title: string;
  notes: string;
  priority: Priority;
  listId: string;
  dueAt: string | null;
  subtasks: string[];
  tagIds: string[];
}

export interface AiPlanResponse {
  tasks: AiPlanItem[];
  warnings: string[];
}

/** AI 意图操作：创建、修改字段、子任务、标签、状态、删除。 */
export type AiOperation =
  | {
      op: 'create_task';
      tempId: string;
      title: string;
      notes: string;
      priority: Priority;
      listId: string;
      dueAt: string | null;
      tagIds: string[];
      subtasks: string[];
    }
  | {
      op: 'update_fields';
      taskId: string;
      patch: Record<string, unknown>;
      clearFields: string[];
    }
  | { op: 'add_subtasks'; taskId: string; subtasks: string[] }
  | { op: 'set_subtask_done'; taskId: string; subtaskId: string; done: boolean }
  | { op: 'add_tags' | 'remove_tags'; taskId: string; tagIds: string[] }
  | { op: 'set_status'; taskId: string; status: TaskStatus }
  | { op: 'delete_task'; taskId: string };

export interface AiIntentResponse {
  intentVersion: number;
  reply: string;
  operations: AiOperation[];
  warnings: string[];
}

export interface AiApplyResult {
  opIds: string[];
  affectedTaskIds: string[];
}

export interface Projection {
  activeWorkspaceId: string;
  currentUserId: string;
  workspaces: Workspace[];
  members: Member[];
  lists: TodoList[];
  tags: Tag[];
  tasks: Task[];
  history: HistoryOp[];
}

export interface BootstrapResponse {
  projection: Projection;
  settings: Settings;
  aiKeyConfigured: boolean;
  deviceId: string;
  sync: SyncStateDto;
}

export interface SyncAccount {
  serverUrl: string;
  userId: string;
  username: string;
  displayName: string;
}

export interface SyncStateDto {
  status: SyncStatus;
  account: SyncAccount | null;
  pendingCount: number;
  rejectedCount: number;
  lastSyncedAt: number | null;
  lastError: string | null;
  cursor: number;
}

export interface SyncNoticeDto {
  kind: 'clock_skew' | 'permission' | 'auth' | 'error';
  code: string;
  message: string;
  retryable: boolean;
  at: number;
}

/** dsh 推送/剪贴板通道状态。 */
export interface DshStatus {
  enabled: boolean;
  signedIn: boolean;
  mqttEnabled: boolean;
  mqttConnected: boolean;
  /** MQTT 不可用，已降级为定时 HTTP 轮询。 */
  degraded: boolean;
  deviceId: string | null;
  lastPullAt: number | null;
  lastClipSentAt: number | null;
  lastClipReceivedAt: number | null;
  lastError: string | null;
}

/** 点击通知后要求前端定位任务。 */
export interface OpenTaskPayload {
  taskId: string | null;
  workspaceId: string | null;
}

export interface AuthResult {
  account: SyncAccount;
  backupPath: string | null;
  sync: SyncStateDto;
}

export interface SyncPreflight {
  localOpCount: number;
  backupDir: string;
}

export interface AuthRegisterInput {
  serverUrl: string;
  username: string;
  password: string;
  email?: string;
  displayName?: string;
  workspaceName?: string;
}
