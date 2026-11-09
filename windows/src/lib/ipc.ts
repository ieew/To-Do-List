import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import type { AiPlanResponse, BootstrapResponse, Settings } from './types';

export const isTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

export interface CreateTaskPayload {
  title: string;
  notes?: string;
  dueAt?: string | null;
  remindAt?: string | null;
  priority?: string;
  listId?: string;
  tagIds?: string[];
  source?: string;
  subtasks?: string[];
  model?: string;
}

export interface StickyContext {
  mode: 'main' | 'floating';
  focusTaskId: string | null;
}

/**
 * Tauri 命令薄封装。组件只通过这里访问 Rust，便于集中处理错误与后续替换为
 * tauri-specta 生成文件。
 */
export const ipc = {
  bootstrap: (workspaceId?: string) =>
    invoke<BootstrapResponse>('bootstrap', { workspaceId: workspaceId ?? null }),

  setActiveWorkspace: (workspaceId: string) =>
    invoke<void>('set_active_workspace', { workspaceId }),

  createWorkspace: (name: string) =>
    invoke<{ id: string; name: string }>('create_workspace', { name }),

  renameWorkspace: (workspaceId: string, name: string) =>
    invoke<void>('rename_workspace', { workspaceId, name }),

  deleteWorkspace: (workspaceId: string) =>
    invoke<string>('delete_workspace', { workspaceId }),

  createTask: (input: CreateTaskPayload) => invoke<string>('create_task', { input }),

  updateTaskField: (taskId: string, field: string, value: unknown) =>
    invoke<void>('update_task_field', { taskId, field, value }),

  deleteTask: (taskId: string) => invoke<void>('delete_task', { taskId }),

  reorderTask: (taskId: string, targetId: string) =>
    invoke<void>('reorder_task', { taskId, targetId }),

  addSubtask: (taskId: string, text: string) =>
    invoke<string>('add_subtask', { taskId, text }),

  toggleSubtask: (taskId: string, subtaskId: string) =>
    invoke<void>('toggle_subtask', { taskId, subtaskId }),

  removeSubtask: (taskId: string, subtaskId: string) =>
    invoke<void>('remove_subtask', { taskId, subtaskId }),

  getHistory: (workspaceId?: string | null, taskId?: string | null) =>
    invoke<BootstrapResponse['projection']['history']>('get_history', {
      workspaceId: workspaceId ?? null,
      taskId: taskId ?? null
    }),

  undoOp: (opId: string) => invoke<void>('undo_op', { opId }),

  updateMemberRole: (workspaceId: string, userId: string, role: string) =>
    invoke<void>('update_member_role', { workspaceId, userId, role }),

  removeMember: (workspaceId: string, userId: string) =>
    invoke<void>('remove_member', { workspaceId, userId }),

  inviteMember: (workspaceId: string, name: string, email: string, role: string) =>
    invoke<string>('invite_member', { workspaceId, name, email, role }),

  saveSettings: (settings: Settings) => invoke<void>('save_settings', { settings }),

  aiKeyStatus: () => invoke<boolean>('ai_key_status'),

  setAiKey: (apiKey: string) => invoke<boolean>('set_ai_key', { apiKey }),

  clearAiKey: () => invoke<boolean>('clear_ai_key'),

  buildAiPrompt: (prompt: string, workspaceId?: string | null) =>
    invoke<string>('ai_build_prompt', {
      prompt,
      workspaceId: workspaceId ?? null
    }),

  generateAiTasks: (prompt: string, workspaceId?: string | null) =>
    invoke<AiPlanResponse>('ai_generate_tasks', {
      prompt,
      workspaceId: workspaceId ?? null
    }),

  parseAiPlan: (text: string, workspaceId?: string | null) =>
    invoke<AiPlanResponse>('ai_parse_plan', {
      text,
      workspaceId: workspaceId ?? null
    }),

  openStickyWindow: (mode: 'main' | 'floating', taskId?: string | null) =>
    invoke<string>('open_sticky_window', { mode, taskId: taskId ?? null }),

  closeStickyWindow: (label: string) => invoke<void>('close_sticky_window', { label }),

  showMainWindow: () => invoke<void>('show_main_window'),

  getStickyContext: (label: string) =>
    invoke<StickyContext | null>('get_sticky_context', { label }),

  listStickyWindows: () => invoke<string[]>('list_sticky_windows'),

  onProjectionUpdated: (handler: (workspaceId: string | null) => void): Promise<UnlistenFn> =>
    listen<{ workspaceId: string | null }>('projection-updated', (event) => {
      handler(event.payload.workspaceId);
    })
};

export function errorMessage(error: unknown): string {
  if (typeof error === 'string') return error;
  if (error instanceof Error) return error.message;
  return '操作失败，请重试';
}
