// Kiểm khả năng tiếp cận UI Pack bằng axe-core qua Playwright (INF-06, OPS-11, NFR-11: WCAG 2.1 AA).
// Mở trang mẫu do vt-ui-pack.py dựng ở hai chế độ (thường và data-mode="elderly"), chạy axe với thẻ
// wcag2a, wcag2aa, wcag21a, wcag21aa; có vi phạm thì trả mã 1. Cần playwright-core và axe-core trong
// NODE_PATH của container node, trình duyệt Chromium (biến VT_CHROMIUM hoặc bản Playwright cài sẵn).
//   node vt-ui-pack-axe.mjs <trang.html> [bao-cao.json]
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright-core');
const axeNguon = readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
const [trang, baoCao] = process.argv.slice(2);
const trinhDuyet = await chromium.launch(process.env.VT_CHROMIUM ? { executablePath: process.env.VT_CHROMIUM } : {});
const ketQua = [];
try {
  for (const cheDo of ['thuong', 'elderly']) {
    const p = await trinhDuyet.newPage({ viewport: { width: 1280, height: 800 } });
    await p.goto(pathToFileURL(trang).href);
    if (cheDo === 'elderly') await p.evaluate(() => document.documentElement.setAttribute('data-mode', 'elderly'));
    await p.addScriptTag({ content: axeNguon });
    const r = await p.evaluate(() => globalThis.axe.run(document, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] } }));
    for (const v of r.violations) {
      ketQua.push({ cheDo, id: v.id, impact: v.impact, help: v.help, nodes: v.nodes.map((n) => n.target.join(' ')).slice(0, 5) });
    }
    console.log(`  chế độ ${cheDo}: ${r.passes.length} quy tắc đạt, ${r.violations.length} vi phạm`);
    await p.close();
  }
} finally {
  await trinhDuyet.close();
}
for (const v of ketQua) console.log(`  [HỎNG] ${v.cheDo} ${v.id} (${v.impact}): ${v.help} — ${v.nodes.join(', ')}`);
if (baoCao) writeFileSync(baoCao, JSON.stringify(ketQua, null, 2));
process.exit(ketQua.length ? 1 : 0);
