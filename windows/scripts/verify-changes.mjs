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

// 1. 工作区预设、重命名与删除
await page.goto(BASE, { waitUntil: 'load' });
await sleep(1100);
results.initialWorkspace = await text('.ws-name');
results.initialLists = await page.$$eval('.sidebar .side-item .item-name', (els) =>
  els.map((el) => el.textContent.trim())
);
results.sidebarMembers = await page.$$eval('.member-row', (els) => els.length);

async function openWorkspacePopover() {
  await page.click('.ws-btn');
  await sleep(250);
}

// 新建工作区：必须带 4 个预设清单与 5 个预设标签
await openWorkspacePopover();
await clickByText('.ws-action', '新建工作区');
await sleep(200);
await page.type('.ws-pop .ws-create .ws-input', '项目空间');
await clickByText('.ws-create .ws-mini.primary', '创建');
await sleep(700);
results.createdWorkspace = await text('.ws-name');
results.createdLists = await page.$$eval('.sidebar .side-item .item-name', (els) =>
  els.map((el) => el.textContent.trim())
);

// 更多按钮 → 重命名
await openWorkspacePopover();
await page.click('.ws-row.is-active .ws-more');
await sleep(200);
results.workspaceMenuItems = await page.$$eval('.ws-menu-item', (els) =>
  els.map((el) => el.textContent.trim())
);
await clickByText('.ws-menu-item', '重命名');
await sleep(200);
await page.click('.ws-rename .ws-input');
await page.keyboard.down('Control');
await page.keyboard.press('A');
await page.keyboard.up('Control');
await page.type('.ws-rename .ws-input', '重命名空间');
await clickByText('.ws-rename .ws-mini.primary', '保存');
await sleep(700);
results.renamedWorkspace = await text('.ws-name');

// 右键工作区 → 同一操作菜单
await openWorkspacePopover();
await page.click('.ws-row.is-active .ws-item', { button: 'right' });
await sleep(200);
results.contextMenuItems = await page.$$eval('.ws-menu-item', (els) =>
  els.map((el) => el.textContent.trim())
);
await page.keyboard.press('Escape');
await sleep(150);

// 删除当前工作区 → 自动切回剩余工作区
await page.click('.ws-row.is-active .ws-more');
await sleep(200);
await page.click('.ws-menu-item.danger');
await sleep(200);
results.deleteConfirmVisible = !!(await page.$('.ws-confirm'));
await page.click('.ws-confirm .ws-mini.danger');
await sleep(800);
results.afterDeleteWorkspace = await text('.ws-name');

// 最后一个工作区：删除项必须禁用
await openWorkspacePopover();
results.workspaceCountAfterDelete = await page.$$eval('.ws-item-name', (els) => els.length);
await page.click('.ws-row.is-active .ws-more');
await sleep(200);
results.lastDeleteDisabled = await page.$eval('.ws-menu-item.danger', (el) => el.disabled);
results.lastDeleteHint = await text('.ws-menu-hint');
await page.keyboard.press('Escape');
await sleep(150);
await page.click('.ws-btn');
await sleep(200);

// 2. 设置中的 AI 密钥可编辑
await page.click('.tb-actions .icon-btn');
await sleep(400);
results.stickyOpacitySlider = !!(await page.$('input[aria-label="便利贴底色透明度"]'));
results.stickyOpacityValue = await page
  .$eval('input[aria-label="便利贴底色透明度"]', (el) => el.value)
  .catch(() => null);
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

// 3.5 外观：底色透明度滑块需联动到便利贴底色（浏览器预览）
await clickByText('.settings-nav-item', '外观');
await sleep(250);
await page.$eval('input[aria-label="便利贴底色透明度"]', (el) => {
  el.value = '50';
  el.dispatchEvent(new Event('input', { bubbles: true }));
});
await sleep(150);
results.stickyOpacityLabel = await page.$eval(
  'input[aria-label="便利贴底色透明度"]',
  (el) => el.closest('.setting-control')?.querySelector('.range-value')?.textContent ?? null
);
await page.click('.settings-actions .btn-primary');
await sleep(300);
await page.click('.settings-back');
await sleep(250);
await clickByText('.tb-actions .tb-btn', '新便利贴');
await sleep(400);
results.stickyAlphaAfterSave = await page
  .$eval('.floating-sticky', (el) => getComputedStyle(el).backgroundColor)
  .catch(() => null);
await page.evaluate(() => {
  document.querySelector('.floating-sticky .floating-btn:last-child')?.click();
});
await sleep(200);

// 4. AI 入口：网页版桥接默认隐藏，仅保留 API 直连
await page.goto(`${BASE}?demo=ai`, { waitUntil: 'load' });
await sleep(900);
results.aiModalVisible = !!(await page.$('.ai-modal'));
results.aiTabs = await page.$$eval('.ai-tab', (els) => els.map((el) => el.textContent.trim()));
results.webBridgeHidden = results.aiTabs.length === 0;
await page.type('.ai-textarea', '规划今晚的学习');
await sleep(150);
results.apiGenerateEnabled = await page
  .$eval('.ai-actions .btn-primary', (el) => !el.disabled)
  .catch(() => false);

// 5. 独立便利贴入口（浏览器降级渲染）
await page.goto(`${BASE}sticky.html?mode=main`, { waitUntil: 'load' });
await sleep(900);
results.stickyMainRendered = !!(await page.$('.sticky-note'));
results.stickyStandalone = !!(await page.$('.sticky-stage.standalone'));
results.stickyMainRadius = await page
  .$eval('.sticky-note', (el) => getComputedStyle(el).borderTopLeftRadius)
  .catch(() => null);
results.stickyMainBackground = await page
  .$eval('.sticky-note', (el) => getComputedStyle(el).backgroundColor)
  .catch(() => null);
await page.select('.sticky-list-switch select', 'me-work');
await sleep(250);
results.stickyMainListTitle = await text('.sticky-title');
results.stickyMainScopeLabel = await text('.sticky-progress-row span');
results.stickyMainEmptyText = await text('.sticky-empty span');

await page.goto(`${BASE}sticky.html?mode=floating&focus=task-write-prd`, { waitUntil: 'load' });
await sleep(900);
results.stickyFloatingRendered = !!(await page.$('.floating-sticky'));
results.stickyFloatingStandalone = !!(await page.$('.floating-sticky.standalone'));
results.stickyFloatingRadius = await page
  .$eval('.floating-sticky', (el) => getComputedStyle(el).borderTopLeftRadius)
  .catch(() => null);
results.stickyFloatingBackground = await page
  .$eval('.floating-sticky', (el) => getComputedStyle(el).backgroundColor)
  .catch(() => null);
await page.select('.floating-list select', 'me-work');
await sleep(250);
results.stickyFloatingListTitle = await text('.floating-title');
results.stickyFloatingEmptyText = await text('.floating-empty');
await page.click('.footer-icon');
await sleep(200);
results.floatingAddVisible = !!(await page.$('.floating-add-input'));
await page.type('.floating-add-input', '便签内添加测试');
await page.keyboard.press('Enter');
await sleep(300);
results.floatingAddCreated = await page.$$eval('.floating-task-title', (els) =>
  els.map((el) => el.textContent.trim())
);

console.log(results);
await browser.close();
