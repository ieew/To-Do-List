import puppeteer from 'puppeteer-core';

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const BASE = 'http://127.0.0.1:1420/';
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const browser = await puppeteer.launch({
  executablePath: EDGE,
  headless: true,
  args: ['--no-sandbox', '--disable-gpu', '--hide-scrollbars']
});
const page = await browser.newPage();
await page.setViewport({ width: 1440, height: 900 });
const results = {};

async function text(selector) {
  return page.$eval(selector, (el) => el.textContent.trim()).catch(() => null);
}

async function clickByText(selector, needle) {
  return page.evaluate(
    ({ selector, needle }) => {
      const el = Array.from(document.querySelectorAll(selector)).find((node) =>
        node.textContent.includes(needle)
      );
      if (!el) return false;
      el.click();
      return true;
    },
    { selector, needle }
  );
}

// 1. 工作区切换与成员列表
await page.goto(BASE, { waitUntil: 'load' });
await sleep(1100);
results.initialWorkspace = await text('.ws-name');
results.sidebarMembers = await page.$$eval('.member-row', (els) => els.length);
results.memberNames = await page.$$eval('.member-name', (els) =>
  els.map((el) => el.textContent.replace(/\s+/g, '').trim())
);

await page.click('.ws-btn');
await sleep(250);
results.workspaceOptions = await page.$$eval('.ws-item-name', (els) =>
  els.map((el) => el.textContent.trim())
);
await clickByText('.ws-item', '产品团队');
await sleep(700);
results.workspaceAfterSwitch = await text('.ws-name');
results.teamMembers = await page.$$eval('.member-name', (els) =>
  els.map((el) => el.textContent.replace(/\s+/g, '').trim())
);
results.teamTaskRows = await page.$$eval('.task-row', (els) => els.length);

// 切回我的空间
await page.click('.ws-btn');
await sleep(200);
await clickByText('.ws-item', '我的空间');
await sleep(700);
results.workspaceBack = await text('.ws-name');

// 2. 设置中的 AI 密钥可编辑
await page.click('.tb-actions .icon-btn');
await sleep(400);
await clickByText('.settings-nav-item', 'AI 助手');
await sleep(300);
results.keyInputDisabled = await page.$eval(
  'input[aria-label="AI API 密钥"]',
  (el) => el.disabled
);

// 先清除已配置状态，再重新保存
const clearButton = await page.$('.settings-panel .danger-outline');
if (clearButton) {
  await clearButton.click();
  await sleep(200);
  await page.click('.settings-panel .danger-outline');
  await sleep(400);
}
results.keyStatusAfterClear = await text('.key-status');

await page.type('input[aria-label="AI API 密钥"]', 'sk-local-test');
await sleep(150);
await clickByText('.settings-panel .btn-primary', '保存密钥');
await sleep(500);
results.keyStatusAfterSave = await text('.key-status');
results.keyInputCleared = await page.$eval(
  'input[aria-label="AI API 密钥"]',
  (el) => el.value === ''
);

// 3. 成员管理页
await clickByText('.settings-nav-item', '成员');
await sleep(300);
results.settingsMemberRows = await page.$$eval('.member-item', (els) => els.length);
results.roleSelects = await page.$$eval('.role-select', (els) => els.length);
results.ownerSelectDisabled = await page.$eval('.role-select', (el) => el.disabled);
results.inviteFormVisible = !!(await page.$('.invite-grid'));

// 4. 独立便利贴入口（浏览器降级渲染）
await page.goto(`${BASE}sticky.html?mode=main`, { waitUntil: 'load' });
await sleep(900);
results.stickyMainRendered = !!(await page.$('.sticky-note'));
results.stickyStandalone = !!(await page.$('.sticky-stage.standalone'));

await page.goto(`${BASE}sticky.html?mode=floating&focus=task-write-prd`, { waitUntil: 'load' });
await sleep(900);
results.stickyFloatingRendered = !!(await page.$('.floating-sticky'));
results.stickyFloatingStandalone = !!(await page.$('.floating-sticky.standalone'));

console.log(results);
await browser.close();
