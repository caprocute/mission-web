// Chụp đường từ cảnh báo tới trace trên Grafana của cụm kind (INF-15, tiêu chí B5): dashboard_url của cảnh báo
// → bấm dòng log có trace_id → bấm liên kết Tempo. Chạy sau kiem-thu-quan-sat.sh, cần Playwright:
//   kubectl -n observability port-forward svc/om-grafana 13000:80 &
//   node chup-duong-canh-bao.js http://127.0.0.1:13000 "$(kubectl -n observability get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d)" <thư mục ảnh>
const { chromium } = require('playwright');
(async () => {
  const [base, pass, out] = process.argv.slice(2);
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 1600, height: 2200 }, locale: 'vi-VN', timezoneId: 'Asia/Ho_Chi_Minh', httpCredentials: { username: 'admin', password: pass } });
  const p = await ctx.newPage();
  const loi = [];
  p.on('console', m => { if (m.type() === 'error') loi.push(m.text()); });
  await p.goto(base + '/login');
  await p.fill('input[name="user"]', 'admin');
  await p.fill('input[name="password"]', pass);
  await p.click('button[type="submit"]');
  await p.waitForURL(u => !u.toString().includes('/login'));
  // Thao tác 1: mở dashboard_url của cảnh báo (dashboard dịch vụ, khoảng 30 phút gần nhất).
  await p.goto(`${base}/d/vt-service?var-service=mission-core&from=now-30m&to=now`, { waitUntil: 'networkidle' });
  await p.waitForTimeout(4000);
  await p.screenshot({ path: `${out}/1-dashboard.png`, fullPage: true });
  // Thao tác 2: mở một dòng log có trace_id.
  const dong = p.getByText(/"span":\{"id"/).first();
  await dong.scrollIntoViewIfNeeded();
  await dong.click();
  await p.waitForTimeout(1500);
  await p.screenshot({ path: `${out}/2-dong-log.png`, fullPage: true });
  // Thao tác 3: bấm liên kết Tempo của trường trace_id.
  const lienKet = p.locator('a, button').filter({ hasText: /Tempo|View trace|Query with|trace_id/ }).first();
  const [moi] = await Promise.all([ctx.waitForEvent('page', { timeout: 5000 }).catch(() => null), lienKet.click()]);
  const t = moi || p;
  await t.waitForLoadState('networkidle');
  await t.waitForTimeout(5000);
  await t.screenshot({ path: `${out}/3-trace.png`, fullPage: true });
  console.log('URL trace:', t.url());
  console.log('Lỗi console:', loi.length ? loi.join('\n') : 'không có');
  await b.close();
})();
