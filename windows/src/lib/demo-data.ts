import type { HistoryOp, Settings, Tag, Task, TodoList } from './types';
import {
  daysAgoAt,
  daysAgoISO,
  daysAgoLabel,
  minutesAgoLabel,
  nextWeekdayAt,
  plusDaysAt,
  todayAt
} from './utils';

const BASE = new Date();

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

export function seedTasks(): Task[] {
  return [
    {
      id: 'task-write-prd',
      title: '写产品需求文档',
      notes: '对齐 MVP 范围、权限矩阵与同步流程，输出给设计与研发评审。',
      dueAt: todayAt(18, 0, BASE),
      remindAt: todayAt(17, 30, BASE),
      priority: 'high',
      status: 'todo',
      listId: 'work',
      tagIds: ['tag-work', 'tag-important'],
      subtasks: [
        { id: 'sub-1', text: '收集竞品数据', done: true },
        { id: 'sub-2', text: '整理用户反馈', done: true },
        { id: 'sub-3', text: '写第一版初稿', done: false },
        { id: 'sub-4', text: '和设计对齐交互', done: false }
      ],
      source: 'ai',
      createdAt: todayAt(9, 12, BASE),
      completedAt: null,
      order: 0,
      model: 'gpt-4o'
    },
    {
      id: 'task-reply-mail',
      title: '回复客户邮件',
      notes: '确认下周交付节点，说明离线合并的边界。',
      dueAt: todayAt(20, 0, BASE),
      remindAt: null,
      priority: 'mid',
      status: 'todo',
      listId: 'work',
      tagIds: ['tag-work'],
      subtasks: [],
      source: 'human',
      createdAt: todayAt(8, 40, BASE),
      completedAt: null,
      order: 1
    },
    {
      id: 'task-dentist',
      title: '预约牙医',
      notes: '',
      dueAt: todayAt(16, 0, BASE),
      remindAt: todayAt(15, 30, BASE),
      priority: 'low',
      status: 'todo',
      listId: 'life',
      tagIds: ['tag-life'],
      subtasks: [],
      source: 'human',
      createdAt: todayAt(8, 20, BASE),
      completedAt: null,
      order: 2
    },
    {
      id: 'task-weekly-meeting',
      title: '准备周会材料',
      notes: '同步本周进度、风险与下周计划。',
      dueAt: plusDaysAt(1, 10, 0, BASE),
      remindAt: plusDaysAt(1, 9, 30, BASE),
      priority: 'mid',
      status: 'todo',
      listId: 'work',
      tagIds: ['tag-work', 'tag-week'],
      subtasks: [
        { id: 'sub-5', text: '整理数据看板', done: false },
        { id: 'sub-6', text: '列出风险项', done: false }
      ],
      source: 'human',
      createdAt: daysAgoISO(1, 16, 20, BASE),
      completedAt: null,
      order: 3
    },
    {
      id: 'task-call-mom',
      title: '给妈妈打电话',
      notes: '',
      dueAt: nextWeekdayAt(6, 19, 0, BASE),
      remindAt: null,
      priority: 'low',
      status: 'todo',
      listId: 'life',
      tagIds: ['tag-life'],
      subtasks: [],
      source: 'human',
      createdAt: daysAgoISO(1, 18, 5, BASE),
      completedAt: null,
      order: 4
    },
    {
      id: 'task-coffee',
      title: '买咖啡豆',
      notes: '',
      dueAt: todayAt(9, 0, BASE),
      remindAt: null,
      priority: 'low',
      status: 'done',
      listId: 'life',
      tagIds: ['tag-life'],
      subtasks: [],
      source: 'human',
      createdAt: daysAgoISO(2, 20, 0, BASE),
      completedAt: todayAt(9, 15, BASE),
      order: 5
    },
    {
      id: 'task-notes',
      title: '整理会议笔记',
      notes: '把决策项同步到项目空间。',
      dueAt: daysAgoISO(1, 10, 0, BASE),
      remindAt: null,
      priority: 'mid',
      status: 'done',
      listId: 'work',
      tagIds: ['tag-work'],
      subtasks: [],
      source: 'human',
      createdAt: daysAgoISO(1, 14, 0, BASE),
      completedAt: todayAt(10, 30, BASE),
      order: 6
    },
    {
      id: 'task-reading',
      title: '看完《设计心理学》第一章',
      notes: '记三条对当前界面有帮助的原则。',
      dueAt: daysAgoISO(3, 8, 0, BASE),
      remindAt: null,
      priority: 'low',
      status: 'done',
      listId: 'study',
      tagIds: ['tag-study', 'tag-week'],
      subtasks: [],
      source: 'human',
      createdAt: daysAgoISO(3, 21, 0, BASE),
      completedAt: todayAt(8, 10, BASE),
      order: 7
    }
  ];
}

export function seedHistory(): HistoryOp[] {
  const ops: HistoryOp[] = [
    {
      id: 'op_b7e210',
      taskId: 'task-write-prd',
      taskTitle: '写产品需求文档',
      at: Date.now() - 44 * 60_000,
      timeLabel: minutesAgoLabel(44, BASE),
      actor: '系统',
      source: 'human',
      action: '生成快照',
      system: true,
      diff: { kind: 'snapshot', field: 'snapshot', text: 'snapshot · seq 1284' }
    },
    {
      id: 'op_2c9e71',
      taskId: 'task-write-prd',
      taskTitle: '写产品需求文档',
      at: Date.now() - 59 * 60_000,
      timeLabel: minutesAgoLabel(59, BASE),
      actor: '小明',
      source: 'human',
      action: '添加了标签',
      diff: { kind: 'add', field: 'tagIds', text: '__tag += ', newText: '#重要' }
    },
    {
      id: 'op_8f2a1c',
      taskId: 'task-write-prd',
      taskTitle: '写产品需求文档',
      at: Date.now() - 84 * 60_000,
      timeLabel: minutesAgoLabel(84, BASE),
      actor: 'AI 助手',
      source: 'ai',
      action: '修改了优先级',
      field: 'priority',
      oldValue: 'mid',
      newValue: 'high',
      diff: { kind: 'change', field: 'priority', text: 'priority: ', oldText: '中', newText: '高' },
      model: 'gpt-4o',
      undoable: true
    },
    {
      id: 'op_1b7d04',
      taskId: 'task-write-prd',
      taskTitle: '写产品需求文档',
      at: Date.now() - 102 * 60_000,
      timeLabel: minutesAgoLabel(102, BASE),
      actor: '我',
      source: 'human',
      action: '添加了子任务',
      diff: {
        kind: 'add',
        field: 'subtask',
        text: '__subtask += ',
        newText: '"收集竞品数据"'
      }
    },
    {
      id: 'op_4a0f92',
      taskId: 'task-write-prd',
      taskTitle: '写产品需求文档',
      at: Date.now() - 104 * 60_000,
      timeLabel: minutesAgoLabel(104, BASE),
      actor: '我',
      source: 'human',
      action: '修改了截止时间',
      field: 'dueAt',
      oldValue: null,
      newValue: todayAt(18, 0, BASE),
      diff: { kind: 'change', field: 'dueAt', text: 'due_at: ', oldText: '无', newText: '今天 18:00' }
    },
    {
      id: 'op_9e3c55',
      taskId: 'task-write-prd',
      taskTitle: '写产品需求文档',
      at: Date.now() - 142 * 60_000,
      timeLabel: minutesAgoLabel(142, BASE),
      actor: 'AI 助手',
      source: 'ai',
      action: '创建了任务',
      diff: {
        kind: 'set',
        field: 'title',
        text: 'title = ',
        newText: '"写产品需求文档"'
      },
      model: 'gpt-4o'
    },
    {
      id: 'op_5d81aa',
      taskId: 'task-notes',
      taskTitle: '整理会议笔记',
      at: Date.now() - 64 * 60_000,
      timeLabel: minutesAgoLabel(64, BASE),
      actor: '我',
      source: 'human',
      action: '完成了任务',
      field: 'status',
      oldValue: 'todo',
      newValue: 'done',
      diff: { kind: 'change', field: 'status', text: 'status: ', oldText: '未完成', newText: '已完成' }
    },
    {
      id: 'op_0a4f67',
      taskId: 'task-coffee',
      taskTitle: '买咖啡豆',
      at: Date.now() - 139 * 60_000,
      timeLabel: minutesAgoLabel(139, BASE),
      actor: '我',
      source: 'human',
      action: '完成了任务',
      field: 'status',
      oldValue: 'todo',
      newValue: 'done',
      diff: { kind: 'change', field: 'status', text: 'status: ', oldText: '未完成', newText: '已完成' }
    },
    {
      id: 'op_33c0d2',
      taskId: 'task-reading',
      taskTitle: '看完《设计心理学》第一章',
      at: Date.now() - 204 * 60_000,
      timeLabel: minutesAgoLabel(204, BASE),
      actor: '我',
      source: 'human',
      action: '完成了任务',
      field: 'status',
      oldValue: 'todo',
      newValue: 'done',
      diff: { kind: 'change', field: 'status', text: 'status: ', oldText: '未完成', newText: '已完成' }
    },
    {
      id: 'op_c41b09',
      taskId: 'task-weekly-meeting',
      taskTitle: '准备周会材料',
      at: daysAgoAt(1, 16, 20, BASE),
      timeLabel: daysAgoLabel(1, 16, 20, BASE),
      actor: '我',
      source: 'human',
      action: '创建了任务',
      diff: { kind: 'set', field: 'title', text: 'title = ', newText: '"准备周会材料"' }
    },
    {
      id: 'op_77e0fa',
      taskId: 'task-call-mom',
      taskTitle: '给妈妈打电话',
      at: daysAgoAt(1, 18, 5, BASE),
      timeLabel: daysAgoLabel(1, 18, 5, BASE),
      actor: '我',
      source: 'human',
      action: '创建了任务',
      diff: { kind: 'set', field: 'title', text: 'title = ', newText: '"给妈妈打电话"' }
    }
  ];

  return ops.sort((a, b) => b.at - a.at);
}

export const defaultSettings: Settings = {
  theme: 'light',
  density: 'comfortable',
  notifications: true,
  reminderLead: 15,
  dndEnabled: false,
  defaultPriority: 'mid',
  confirmDelete: true,
  autoArchive: false,
  syncInterval: 5,
  serverUrl: 'https://sync.todo.local',
  aiEnabled: true,
  aiEndpoint: 'http://127.0.0.1:11434/v1',
  aiModel: 'qwen2.5-7b',
  aiConfirmThreshold: 10
};
