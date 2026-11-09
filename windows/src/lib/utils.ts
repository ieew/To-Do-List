import type { Priority, Task, TaskStatus } from './types';

const WEEKDAY_CN = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
const PRIORITY_CN: Record<Priority, string> = { high: '高', mid: '中', low: '低' };
const STATUS_CN: Record<TaskStatus, string> = { todo: '未完成', done: '已完成' };

export function uid(prefix = 'id'): string {
  return `${prefix}_${Math.random().toString(36).slice(2, 10)}`;
}

export function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

export function hhmm(date: Date): string {
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}`;
}

export function sameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}

export function startOfDay(date: Date): Date {
  const d = new Date(date);
  d.setHours(0, 0, 0, 0);
  return d;
}

export function dayDiff(iso: string, base = new Date()): number {
  const target = startOfDay(new Date(iso));
  const today = startOfDay(base);
  return Math.round((target.getTime() - today.getTime()) / 86_400_000);
}

/** 任务列表中的简洁截止文案 */
export function formatDueShort(iso: string | null, base = new Date()): string {
  if (!iso) return '未设置';
  const d = new Date(iso);
  const diff = dayDiff(iso, base);
  if (diff === 0) return `${hhmm(d)} 截止`;
  if (diff === 1) return `明天 ${hhmm(d)}`;
  if (diff === -1) return `昨天 ${hhmm(d)}`;
  if (diff > 1 && diff < 7) return `${WEEKDAY_CN[d.getDay()]} ${hhmm(d)}`;
  return `${d.getMonth() + 1}月${d.getDate()}日`;
}

/** 详情与历史中的完整截止文案 */
export function formatDueLong(iso: string | null, base = new Date()): string {
  if (!iso) return '无';
  const d = new Date(iso);
  const diff = dayDiff(iso, base);
  if (diff === 0) return `今天 ${hhmm(d)}`;
  if (diff === 1) return `明天 ${hhmm(d)}`;
  if (diff === -1) return `昨天 ${hhmm(d)}`;
  if (diff > 1 && diff < 7) return `${WEEKDAY_CN[d.getDay()]} ${hhmm(d)}`;
  return `${d.getMonth() + 1}月${d.getDate()}日 ${hhmm(d)}`;
}

export function isDueToday(iso: string | null, base = new Date()): boolean {
  return iso ? sameDay(new Date(iso), base) : false;
}

/**
 * 任务是否已到开始时间。
 * `startAt` 为空或非法时视为已开始，兼容旧数据与早期未设置开始时间的任务。
 */
export function hasStarted(task: Task, base = new Date()): boolean {
  if (!task.startAt) return true;
  const startedAt = new Date(task.startAt).getTime();
  if (Number.isNaN(startedAt)) return true;
  return startedAt <= base.getTime();
}

/** 任务列表中的简洁开始文案 */
export function formatStartShort(iso: string | null, base = new Date()): string {
  return formatDueShort(iso, base).replace(' 截止', '');
}

export function isOverdue(task: Task, base = new Date()): boolean {
  if (!task.dueAt || task.status === 'done') return false;
  return new Date(task.dueAt).getTime() < base.getTime();
}

export function isUrgent(task: Task, base = new Date()): boolean {
  if (!task.dueAt || task.status === 'done') return false;
  const due = new Date(task.dueAt).getTime();
  const delta = due - base.getTime();
  return delta >= 0 && delta <= 4 * 60 * 60 * 1000;
}

export function priorityLabel(priority: Priority): string {
  return PRIORITY_CN[priority];
}

export function statusLabel(status: TaskStatus): string {
  return STATUS_CN[status];
}

export function toLocalInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}T${hhmm(d)}`;
}

export function fromLocalInput(value: string): string | null {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

export function todayAt(hour: number, minute = 0, base = new Date()): string {
  const d = new Date(base);
  d.setHours(hour, minute, 0, 0);
  return d.toISOString();
}

/** 新任务默认开始时间：创建任务的当天时间（即当前时刻）。 */
export function defaultStartAt(base = new Date()): string {
  return base.toISOString();
}

/** 新任务默认截止时间：当前时间 + 7 天 */
export function defaultDueAt(base = new Date()): string {
  const d = new Date(base);
  d.setDate(d.getDate() + 7);
  return d.toISOString();
}

export function plusDaysAt(days: number, hour = 9, minute = 0, base = new Date()): string {
  const d = new Date(base);
  d.setDate(d.getDate() + days);
  d.setHours(hour, minute, 0, 0);
  return d.toISOString();
}

/** 找到下一个指定星期几（1=周一 … 6=周六，0=周日） */
export function nextWeekdayAt(
  weekday: number,
  hour = 9,
  minute = 0,
  base = new Date()
): string {
  const d = new Date(base);
  const delta = (weekday - d.getDay() + 7) % 7 || 7;
  d.setDate(d.getDate() + delta);
  d.setHours(hour, minute, 0, 0);
  return d.toISOString();
}

export function timeLabelNow(base = new Date()): string {
  return `今天 ${hhmm(base)}`;
}

export function minutesAgoLabel(minutes: number, base = new Date()): string {
  const d = new Date(base.getTime() - minutes * 60_000);
  return `${sameDay(d, base) ? '今天' : '昨天'} ${hhmm(d)}`;
}

export function daysAgoLabel(days: number, hour: number, minute = 0, base = new Date()): string {
  const d = new Date(base);
  d.setDate(d.getDate() - days);
  d.setHours(hour, minute, 0, 0);
  return `${days === 1 ? '昨天' : `${d.getMonth() + 1}月${d.getDate()}日`} ${hhmm(d)}`;
}

export function daysAgoAt(days: number, hour: number, minute = 0, base = new Date()): number {
  const d = new Date(base);
  d.setDate(d.getDate() - days);
  d.setHours(hour, minute, 0, 0);
  return d.getTime();
}

export function daysAgoISO(days: number, hour: number, minute = 0, base = new Date()): string {
  return new Date(daysAgoAt(days, hour, minute, base)).toISOString();
}

export function truncate(text: string, max = 32): string {
  const clean = text.replace(/\s+/g, ' ').trim();
  return clean.length > max ? `${clean.slice(0, max)}…` : clean;
}

export function cloneTask(task: Task): Task {
  return {
    ...task,
    tagIds: [...task.tagIds],
    subtasks: task.subtasks.map((s) => ({ ...s }))
  };
}

export function pluralTasks(n: number): string {
  return `${n} 项待办`;
}
