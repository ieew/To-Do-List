import { mount } from 'svelte';
import './app.css';
import QuickAddApp from './QuickAddApp.svelte';

const target = document.getElementById('quick-add-root');
if (!target) {
  throw new Error('缺少 #quick-add-root 挂载节点');
}

const app = mount(QuickAddApp, { target });

export default app;
