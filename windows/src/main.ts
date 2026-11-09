import { mount } from 'svelte';
import { getCurrentWindow } from '@tauri-apps/api/window';
import './app.css';
import App from './App.svelte';
import { isTauri } from './lib/ipc';
import { store } from './lib/store.svelte';

const target = document.getElementById('app');
if (!target) {
  throw new Error('缺少 #app 挂载节点');
}

const appWindow = isTauri ? getCurrentWindow() : null;

/**
 * 主窗口在 Rust 侧是隐藏创建的。这里在启动占位 (#boot) 已进入 DOM 后立即显示，
 * 用户看到的第一帧就是 Logo 占位，而不是 WebView2 初始化期间的空白窗口。
 */
function revealWindow(): void {
  if (!appWindow) return;
  void appWindow.show().catch(() => {});
  void appWindow.setFocus().catch(() => {});
}

/**
 * 启动占位覆盖到业务数据就绪，避免「Logo → 另一个加载遮罩 → 内容」的接力闪烁。
 */
function dismissBoot(): void {
  const boot = document.getElementById('boot');
  if (!boot) return;
  const remove = (): void => boot.remove();
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    remove();
    return;
  }
  boot.classList.add('boot-leave');
  boot.addEventListener('transitionend', remove, { once: true });
  window.setTimeout(remove, 260);
}

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

// 先显示窗口再等数据：占位动画本身就是启动阶段的反馈。
revealWindow();

// 首次启动正常只需几十毫秒；设上限防止异常时窗口一直停在占位层。
await Promise.race([store.init(), wait(3000)]).catch(() => {});

const app = mount(App, { target });
dismissBoot();

// 首屏稳定后再预热非首屏视图的 chunk，切换日历/统计/全局历史时无需等待。
window.setTimeout(() => {
  void import('./components/CalendarView.svelte');
  void import('./components/StatsView.svelte');
  void import('./components/GlobalHistory.svelte');
}, 1200);

export default app;
