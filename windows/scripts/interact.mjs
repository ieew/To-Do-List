import puppeteer from 'puppeteer-core';

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const BASE = 'http://127.0.0.1:1420/';

const browser = await puppeteer.launch({
  executablePath: EDGE,
  headless: true,
  args: ['--no-sandbox', '--disable-gpu', '--hide-scrollbars']
});
const page = await browser.newPage();
await page.setViewport({ width: 1440, height: 900 });

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function open() {
  await page.goto(BASE, { waitUntil: 'load' });
  await sleep(1100);
}

const results = {};

await open();
results.initialTasks = await page.$$eval('.task-row', (els) => els.length);

// 快速添加
await page.click('.main-toolbar .btn-primary');
await sleep(350);
results.quickAddOpen = !!(await page.$('.modal'));
await page.type('.modal input[placeholder="要做什么？"]', '端到端验证任务');
await page.click('.modal-actions .btn-primary');
await sleep(500);
results.tasksAfterCreate = await page.$$eval('.task-row', (els) => els.length);
results.createdVisible = await page.$$eval('.task-title', (els) =>
  els.some((el) => el.textContent.includes('端到端验证任务'))
);

// 设置：切换暗色并保存
await page.click('.tb-actions .icon-btn');
await sleep(400);
results.settingsOpen = !!(await page.$('.settings-overlay.open'));
await page.evaluate(() => {
  const buttons = Array.from(document.querySelectorAll('.settings-panel .segmented button'));
  const dark = buttons.find((button) => button.textContent.trim() === '暗色');
  dark?.click();
});
await sleep(300);
results.dirtyVisible = !!(await page.$('.dirty-badge'));
await page.click('.settings-actions .btn-primary');
await sleep(500);
results.themeAfterSave = await page.$eval('html', (el) => el.dataset.theme);
await page.click('.settings-back');
await sleep(400);
results.settingsClosed = !(await page.$('.settings-overlay.open'));

// AI 助手：生成草案并写入
await page.click('.ai-entry');
await sleep(350);
await page.type('.ai-textarea', '帮我规划今晚的学习');
await page.click('.ai-actions .btn-primary');
await sleep(1100);
results.aiPlanCount = await page.$$eval('.plan-list li', (els) => els.length);
await page.click('.ai-actions .btn-primary');
await sleep(600);
results.tasksAfterAi = await page.$$eval('.task-row', (els) => els.length);
results.aiBadgeCount = await page.$$eval('.task-row .ai-badge', (els) => els.length);

// 打开任务历史并撤销
await page.click('.task-row');
await sleep(400);
await page.evaluate(() => {
  const tabs = Array.from(document.querySelectorAll('.detail-tab'));
  const history = tabs.find((tab) => tab.textContent.includes('历史'));
  history?.click();
});
await sleep(400);
results.historyUndoButtons = await page.$$eval('.history-undo', (els) => els.length);
const undoHandle = await page.$('.history-undo:not([disabled])');
if (undoHandle) {
  await undoHandle.click();
  await sleep(500);
}
results.toastAfterUndo = await page.$eval('.toast', (el) => el.textContent.trim()).catch(() => null);
await page.keyboard.press('Escape');
await sleep(300);

// 便利贴形态
await page.click('.tb-btn');
await sleep(500);
results.stickyVisible = !!(await page.$('.sticky-note'));
await page.keyboard.press('Escape');
await sleep(500);
results.mainRestored = !(await page.$('.app-window.is-collapsed'));

// 右键菜单 → 延期
await page.click('.task-row', { button: 'right' });
await sleep(300);
results.contextMenuOpen = !!(await page.$('.context-menu'));
const postpone = await page.$$eval('.context-menu .menu-item', (els) => els.map((el) => el.textContent.trim()));
results.contextItems = postpone;
await page.evaluate(() => {
  const items = Array.from(document.querySelectorAll('.context-menu .menu-item'));
  const item = items.find((el) => el.textContent.includes('延期到明天'));
  item?.click();
});
await sleep(500);
results.toastAfterPostpone = await page.$eval('.toast', (el) => el.textContent.trim()).catch(() => null);

console.log(results);
await browser.close();
