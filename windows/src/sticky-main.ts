import { mount } from 'svelte';
import './app.css';
import StickyApp from './StickyApp.svelte';

const target = document.getElementById('sticky-root');
if (!target) {
  throw new Error('缺少 #sticky-root 挂载节点');
}

const app = mount(StickyApp, { target });

export default app;
