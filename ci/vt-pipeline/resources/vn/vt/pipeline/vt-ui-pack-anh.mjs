// Ảnh chụp so sánh UI Pack trên staging (INF-06, OPS-11). Mở các trang của staging hai lần: với UI Pack đang áp
// dụng và với CSS của bản ứng viên chèn đè lên, ở hai chế độ (thường, elderly); lưu ảnh trước, sau và ảnh khác
// biệt (tô đỏ điểm ảnh đổi) để người duyệt xem ở bước "Duyệt phát hành". Không chặn theo ngưỡng: đổi token tất yếu
// đổi giao diện, quyết định là của người duyệt. So điểm ảnh bằng canvas của chính trình duyệt, không cần thư viện.
//   node vt-ui-pack-anh.mjs <staging-url> <pack.css> <thư mục ra> [đường dẫn trang...]
import { createRequire } from 'node:module';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright-core');
const [goc, tepCss, ra, ...trang] = process.argv.slice(2);
const css = readFileSync(tepCss, 'utf8');
mkdirSync(ra, { recursive: true });
const tb = await chromium.launch(process.env.VT_CHROMIUM ? { executablePath: process.env.VT_CHROMIUM } : {});
const tomTat = [];
async function chup(duong, cheDo, chen) {
  const p = await tb.newPage({ viewport: { width: 1280, height: 900 } });
  await p.goto(new URL(duong, goc).href, { waitUntil: 'networkidle' });
  if (cheDo === 'elderly') await p.evaluate(() => document.documentElement.setAttribute('data-mode', 'elderly'));
  if (chen) await p.addStyleTag({ content: css });
  const anh = await p.screenshot({ fullPage: false });
  await p.close();
  return anh;
}
try {
  for (const duong of trang.length ? trang : ['/']) {
    for (const cheDo of ['thuong', 'elderly']) {
      const ten = `${duong.replace(/[^a-z0-9]+/gi, '_') || 'goc'}-${cheDo}`;
      const truoc = await chup(duong, cheDo, false);
      const sau = await chup(duong, cheDo, true);
      writeFileSync(`${ra}/${ten}-truoc.png`, truoc);
      writeFileSync(`${ra}/${ten}-sau.png`, sau);
      const p = await tb.newPage();
      const { tyLe, khac } = await p.evaluate(async ([a, b]) => {
        const nap = async (s) => { const i = new Image(); i.src = 'data:image/png;base64,' + s; await i.decode(); return i; };
        const [ia, ib] = [await nap(a), await nap(b)];
        const c = new OffscreenCanvas(ia.width, ia.height); const x = c.getContext('2d');
        x.drawImage(ia, 0, 0); const da = x.getImageData(0, 0, ia.width, ia.height);
        x.drawImage(ib, 0, 0); const db = x.getImageData(0, 0, ia.width, ia.height);
        let doi = 0;
        for (let k = 0; k < da.data.length; k += 4) {
          const d = Math.abs(da.data[k] - db.data[k]) + Math.abs(da.data[k + 1] - db.data[k + 1]) + Math.abs(da.data[k + 2] - db.data[k + 2]);
          if (d > 30) { doi++; db.data[k] = 255; db.data[k + 1] = 0; db.data[k + 2] = 0; }
        }
        x.putImageData(db, 0, 0);
        const blob = await c.convertToBlob({ type: 'image/png' });
        const buf = new Uint8Array(await blob.arrayBuffer());
        let s = ''; for (const v of buf) s += String.fromCharCode(v);
        return { tyLe: doi / (da.data.length / 4), khac: btoa(s) };
      }, [truoc.toString('base64'), sau.toString('base64')]);
      await p.close();
      writeFileSync(`${ra}/${ten}-khac.png`, Buffer.from(khac, 'base64'));
      tomTat.push({ trang: duong, cheDo, tyLeDiemAnhDoi: Number(tyLe.toFixed(4)) });
      console.log(`  ${duong} (${cheDo}): ${(tyLe * 100).toFixed(2)}% điểm ảnh đổi`);
    }
  }
} finally {
  await tb.close();
}
writeFileSync(`${ra}/tom-tat.json`, JSON.stringify(tomTat, null, 2));
