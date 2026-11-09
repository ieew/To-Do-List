import type { Action } from 'svelte/action';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { isTauri } from './ipc';

/**
 * 命中这些元素时保留原生点击，不启动窗口拖动。
 * 注意不要包含 `[tabindex]`：便利贴头部自身带 tabindex，会把整块拖动区挡掉。
 */
const INTERACTIVE_SELECTOR = [
  'button',
  'a',
  'input',
  'select',
  'textarea',
  'label',
  'summary',
  '[role="button"]',
  '[role="link"]',
  '[role="menuitem"]',
  '[role="tab"]',
  '[role="checkbox"]',
  '[role="radio"]',
  '[role="switch"]',
  '[role="option"]',
  '[contenteditable="true"]'
].join(',');

/**
 * 独立便利贴窗口的拖动。
 *
 * 不使用 `data-tauri-drag-region`：Tauri 2.12 的裸属性只认「直接命中元素自身」，
 * 点在标题文字、三横把手等子元素上会拖不动；`"deep"` 又会让双击标题触发最大化。
 * 这里在 mousedown 时直接调用 startDragging()，覆盖整个头部并排除交互控件。
 */
export const stickyWindowDrag: Action<HTMLElement, boolean | undefined> = (
  node,
  enabled = true
) => {
  let active = enabled ?? true;

  function handleMouseDown(event: MouseEvent): void {
    if (!active || !isTauri || event.button !== 0 || event.defaultPrevented) return;
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    if (target.closest(INTERACTIVE_SELECTOR)) return;

    event.preventDefault();
    void getCurrentWindow()
      .startDragging()
      .catch(() => {});
  }

  node.addEventListener('mousedown', handleMouseDown);

  return {
    update(next: boolean | undefined) {
      active = next ?? true;
    },
    destroy() {
      node.removeEventListener('mousedown', handleMouseDown);
    }
  };
};
