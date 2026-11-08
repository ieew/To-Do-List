import { mount } from 'svelte';
import './app.css';
import App from './App.svelte';

const target = document.getElementById('app');
if (!target) {
  throw new Error('缺少 #app 挂载节点');
}

const app = mount(App, { target });

// 首屏占位只覆盖 WebView 初始化到 Svelte 首次渲染之间的白屏，挂载完成后立即移除。
document.getElementById('boot')?.remove();

export default app;
