import { getCurrentWindow } from '@tauri-apps/api/window';
import { isTauri } from './ipc';

/**
 * 主窗口是否处于最大化状态。
 *
 * 透明无边框窗口在最大化时如果仍保留「留边 + 圆角 + 投影」，
 * 屏幕四周会露出桌面（表现为主窗口边上多出一圈）。所以需要把最大化状态
 * 暴露给根节点，由 CSS 把留边/圆角/投影清零。
 */
let maximized = $state(false);
let initialized = false;

/**
 * 幂等初始化：读取初始状态并跟随窗口尺寸变化刷新。
 * 浏览器预览（非 Tauri）下直接跳过，`maximized` 恒为 false。
 */
export function initWindowFrame(): void {
  if (!isTauri || initialized) return;
  initialized = true;

  const win = getCurrentWindow();
  const refresh = (): void => {
    void win
      .isMaximized()
      .then((value) => {
        maximized = value;
      })
      .catch(() => {
        /* 窗口正在销毁时读取失败，保持上一次状态即可 */
      });
  };

  refresh();
  void win.onResized(refresh);
}

/**
 * 跨模块共享窗口状态。用对象 + getter 而不是直接导出 `$state` 变量，
 * 这样在 Svelte 5 里各组件读到的都是同一个响应式源。
 */
export const windowFrame = {
  get maximized(): boolean {
    return maximized;
  }
};
