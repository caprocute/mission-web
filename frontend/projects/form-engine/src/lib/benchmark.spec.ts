import { TestBed } from '@angular/core/testing';
import { evaluateRules } from './cel-rule-evaluator';
import { BIG_FORM_FIELDS as N, bigForm } from '../samples/bieu-mau-50-truong';
import { provideFormEngine } from './provide-form-engine';
import { VtFormComponent } from './vt-form.component';

/**
 * WEB-010, ngân sách TL-01 mục 7 (baseline mục 14): dựng biểu mẫu 50 trường ≤ 50 ms, đánh giá toàn
 * bộ quy tắc CEL của một biểu mẫu ≤ 5 ms. Lấy trung vị của nhiều lần sau một lần khởi động.
 *
 * Quy tắc CEL đo được ở đây đúng ngân sách. Thời gian dựng thì jsdom chậm hơn trình duyệt nhiều lần
 * nên test chỉ chặn hồi quy (≤ JSDOM_RENDER_GUARD_MS, tính tới khi đủ mọi nhóm); số đo trên Chromium
 * (dựng dần theo nhóm, `e2e/do-ngan-sach-bieu-mau.mjs`) ghi ở wiki/09 mục "Biểu mẫu động".
 */
const JSDOM_RENDER_GUARD_MS = 600;
const median = (xs: number[]) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)];

describe('benchmark form engine (TL-01 ngân sách)', () => {
  const f = bigForm();

  it(`đánh giá ${N} quy tắc CEL ≤ 5 ms`, () => {
    evaluateRules(f.rules, f.model); // phân tích một lần, các lần sau dùng cây đã lưu
    const times = Array.from({ length: 20 }, () => {
      const t0 = performance.now();
      evaluateRules(f.rules, f.model);
      return performance.now() - t0;
    });
    const m = median(times);
    console.info(`[benchmark] ${N} quy tắc CEL: trung vị ${m.toFixed(2)} ms`);
    expect(m).toBeLessThanOrEqual(5);
  });

  it(`dựng biểu mẫu ${N} trường: chặn hồi quy trong jsdom (≤ ${JSDOM_RENDER_GUARD_MS} ms)`, async () => {
    TestBed.configureTestingModule({ providers: [provideFormEngine()] });
    const render = async () => {
      const t0 = performance.now();
      const fixture = TestBed.createComponent(VtFormComponent);
      fixture.componentRef.setInput('schema', f.schema);
      fixture.componentRef.setInput('ui', f.ui);
      fixture.componentRef.setInput('rules', f.rules);
      fixture.componentRef.setInput('value', f.model);
      fixture.detectChanges();
      await fixture.whenStable();
      const ms = performance.now() - t0;
      const count = (fixture.nativeElement as HTMLElement).querySelectorAll(
        'input, textarea, select',
      ).length;
      fixture.destroy();
      return { ms, count };
    };
    await render();
    const runs = [];
    for (let i = 0; i < 7; i++) runs.push(await render());
    expect(runs[0].count).toBe(N);
    const m = median(runs.map((r) => r.ms));
    console.info(`[benchmark] dựng ${N} trường (jsdom): trung vị ${m.toFixed(1)} ms`);
    expect(m).toBeLessThanOrEqual(JSDOM_RENDER_GUARD_MS);
  });
});
