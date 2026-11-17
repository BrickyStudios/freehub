// Renders the repo images from the demo-mode screenshots in docs/bilder/app. Needs Playwright:
//   PLAYWRIGHT=/path/to/node_modules/playwright/index.mjs node tools/bilder.mjs
import path from "node:path";
import { fileURLToPath } from "node:url";
const { chromium } = await import(process.env.PLAYWRIGHT ?? "playwright");
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const app = (f) => `file://${root}/docs/bilder/app/${f}`;
const images = [
  ["1-live.png", 1080, 1920, "tall", "Your gear,|*at a glance*", "Live from the derailleur, no account", app("live-en-dark.png")],
  ["2-stats.png", 1080, 1920, "tall", "Every shift,|*every kilometre*", "shifts by the derailleur, distance by GPS", app("stats-en-dark.png")],
  ["3-bike.png", 1080, 1920, "tall", "Battery and|*range*", "and only ever your own bike", app("bike-en-dark.png")],
  ["4-light.png", 1080, 1920, "tall", "Day and|*night*", "follows your phone's theme", app("live-en-light.png")],
  ["social-preview.png", 1280, 640, "wide", "Your Di2,|*no account*", "Gear, battery, range and every shift from a 12-speed Shimano Di2", app("live-en-dark.png")],
];
const browser = await chromium.launch();
for (const [name, w, h, art, t, s, img] of images) {
  const page = await browser.newPage({ viewport: { width: w, height: h } });
  const u = new URL(`file://${root}/tools/rahmen.html`);
  for (const [k, v] of Object.entries({ w, h, art, t, s, img })) u.searchParams.set(k, v);
  await page.goto(u.href, { waitUntil: "networkidle" });
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: `${root}/docs/bilder/${name}` });
  await page.close();
}
await browser.close();
