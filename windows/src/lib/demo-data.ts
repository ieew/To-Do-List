import type { HistoryOp, Member, Settings, Tag, Task, TodoList, Workspace } from './types';

/*
 * 浏览器预览使用的初始投影。
 * 与 Tauri 首次启动保持一致：默认工作区、默认清单与常用标签，
 * 不包含任何演示任务、协作成员或历史。
 */

export const seedLists: TodoList[] = [
  { id: 'today', name: '今天', color: '#3B82F6', smart: true },
  { id: 'work', name: '工作', color: '#F59E0B' },
  { id: 'life', name: '生活', color: '#10B981' },
  { id: 'study', name: '学习', color: '#8B5CF6' }
];

export const seedTags: Tag[] = [
  { id: 'tag-work', name: '工作', bg: '#EFF6FF', fg: '#2563EB' },
  { id: 'tag-life', name: '生活', bg: '#ECFDF5', fg: '#059669' },
  { id: 'tag-study', name: '学习', bg: '#F5F3FF', fg: '#7C3AED' },
  { id: 'tag-important', name: '重要', bg: '#FEF2F2', fg: '#DC2626', showInSidebar: true },
  { id: 'tag-week', name: '本周', bg: '#FEF3C7', fg: '#B45309', showInSidebar: true }
];

export const seedWorkspaces: Workspace[] = [{ id: 'ws-me', name: '我的空间' }];

export function seedMembers(): Member[] {
  return [
    {
      id: 'u-me',
      name: '我',
      initial: '我',
      color: '#3B82F6',
      role: 'owner',
      email: 'me@todo.local',
      workspaceId: 'ws-me'
    }
  ];
}

export function seedTasks(): Task[] {
  return [];
}

export function seedHistory(): HistoryOp[] {
  return [];
}

export const defaultSettings: Settings = {
  theme: 'light',
  density: 'comfortable',
  notifications: true,
  reminderLead: 10,
  dndEnabled: false,
  defaultPriority: 'mid',
  confirmDelete: true,
  autoArchive: false,
  syncInterval: 5,
  serverUrl: 'http://127.0.0.1:8000',
  aiEnabled: true,
  aiEndpoint: 'http://127.0.0.1:11434/v1',
  aiModel: 'qwen2.5-7b',
  aiConfirmThreshold: 10
};
