import { MAU_1B_RULES } from '../samples/mau-1b';
import suiteJson from '../samples/mau-1b.golden.json';
import { lintRules } from './cel-rule-evaluator';
import { GoldenSuite, runGoldenSuite } from './golden';

/**
 * WEB-010: test vàng Mẫu 1B chạy trên cel-js. Cùng tệp `mau-1b.golden.json` được cel-java
 * chạy ở CI máy chủ (TL-01 "Biểu mẫu động"); hai bên phải cho cùng kết quả.
 */
const suite = suiteJson as GoldenSuite;

/** Số ngày làm việc (thứ Hai đến thứ Sáu) từ `from` tới `to`, không tính ngày nghỉ lễ (chỉ dùng trong test). */
function workdays(from: string, to: string): number {
  let n = 0;
  for (let d = new Date(from + 'T00:00:00Z'); d < new Date(to + 'T00:00:00Z');) {
    d.setUTCDate(d.getUTCDate() + 1);
    if (d.getUTCDay() % 6 !== 0) n++;
  }
  return n;
}

describe('test vàng Mẫu 1B', () => {
  it('bộ quy tắc mẫu hợp lệ cú pháp, hàm ngoài trình duyệt đã đánh dấu serverOnly', () => {
    expect(lintRules(MAU_1B_RULES)).toEqual([]);
  });

  for (const c of suite.cases) {
    it(`${c.id}: ${c.description}`, () => {
      const [res] = runGoldenSuite(MAU_1B_RULES, { ...suite, cases: [c] });
      expect({ missing: res.missing, unexpected: res.unexpected }).toEqual({
        missing: [],
        unexpected: [],
      });
    });
  }

  it('quy tắc serverOnly bị bỏ qua trên trình duyệt và được báo trong skipped', () => {
    const res = runGoldenSuite(MAU_1B_RULES, suite);
    expect(res.every((r) => r.ok)).toBe(true);
    expect(res.find((r) => r.caseId === 'G-1B-12')?.skipped).toEqual(['R-1B-04']);
  });

  it('có hàm workdays thì chạy được cả quy tắc serverOnly, toàn bộ ca vẫn đạt', () => {
    const res = runGoldenSuite(MAU_1B_RULES, suite, {
      functions: { workdays },
      includeServerOnly: true,
    });
    expect(res.filter((r) => !r.ok)).toEqual([]);
  });

  it('runner báo lệch khi kỳ vọng sai hoặc mã quy tắc không có trong Policy Pack', () => {
    const bad: GoldenSuite = {
      ...suite,
      cases: [{ ...suite.cases[0], id: 'X', violated: ['R-1B-01', 'R-KHONG-CO'] }],
    };
    expect(runGoldenSuite(MAU_1B_RULES, bad)[0]).toEqual(
      expect.objectContaining({ ok: false, missing: ['R-1B-01', 'R-KHONG-CO'], unexpected: [] }),
    );
  });
});
