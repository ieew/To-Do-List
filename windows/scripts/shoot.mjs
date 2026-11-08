import fs from 'node:fs';
import puppeteer from 'puppeteer-core';

const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const BASE = 'http://127.0.0.1:1420/';
const OUT = 'shots';

fs.mkdirSync(OUT, { recursive: true });

const browser = await puppeteer.launch({
  executablePath: EDGE,
  headless: true,
  args: ['--no-sandbox', '--disable-gpu', '--hide-scrollbars']
});

const page = await browser.newPage();
await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 });

async function shoot(name, demo, after) {
  await page.goto(BASE + (demo ? `?demo=${demo}` : ''), { waitUntil: 'load' });
  await new Promise((resolve) => setTimeout(resolve, 1100));
  if (after) await after();
  await page.screenshot({ path: `${OUT}/${name}.png` });
  console.log('shot', name);
}

await shoot('main');
const deco = await page.$eval('.task-row:not(.is-done) .title-text', (el) => ({
  self: getComputedStyle(el).textDecorationLine,
  parent: getComputedStyle(el.parentElement).textDecorationLine,
  row: getComputedStyle(el.closest('.task-row')).textDecorationLine,
  color: getComputedStyle(el).color,
  font: getComputedStyle(el).fontFamily
}));
console.log('title-style', deco);

await shoot('detail', 'detail');
await shoot('history', 'history');
await shoot('settings', 'settings');
await shoot('sticky', 'sticky');
await shoot('floating', 'floating');
await shoot('calendar', 'calendar');
await shoot('stats', 'stats');
await shoot('global', 'global');
await shoot('dark', 'dark');
await shoot('ai', 'ai');

await page.goto(BASE, { waitUntil: 'load' });
await new Promise((resolve) => setTimeout(resolve, 1100));
await page.click('.task-row .checkbox');
await new Promise((resolve) => setTimeout(resolve, 300));
const drawerOpenAfterCheckbox = await page.$eval('.detail-pane', (el) => el.classList.contains('open'));
const doneCount = await page.$$eval('.task-row.is-done', (els) => els.length);
await page.click('.task-row .title-text');
await new Promise((resolve) => setTimeout(resolve, 400));
const drawerOpenAfterRow = await page.$eval('.detail-pane', (el) => el.classList.contains('open'));
console.log({ drawerOpenAfterCheckbox, doneCount, drawerOpenAfterRow });
await page.screenshot({ path: `${OUT}/interaction-detail.png` });

await browser.close();
