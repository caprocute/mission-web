import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Reporter, TestCase, TestResult } from '@playwright/test/reporter';

/** Một ô của bảng vai trò × tuyến, ghi bằng annotation `ca` trong kiểm thử. */
export interface CaseResult {
  vaiTro: string;
  tuyen: string;
  kyVong: string;
  thucTe: string;
}

/**
 * Gom annotation `ca` thành bảng vai trò × tuyến (đạt ✓, lệch ✗) dạng Markdown để dán vào PR, ghi
 * ở `dist/e2e-ket-qua/bao-cao-<chế độ>.md`.
 */
export default class MaTranReporter implements Reporter {
  private readonly rows: CaseResult[] = [];
  constructor(private readonly options: { mode: string }) {}

  onTestEnd(test: TestCase, _result: TestResult): void {
    for (const a of test.annotations) {
      if (a.type === 'ca' && a.description) this.rows.push(JSON.parse(a.description));
    }
  }

  onEnd(): void {
    if (this.rows.length === 0) return;
    const roles = [...new Set(this.rows.map((r) => r.vaiTro))];
    const routes = [...new Set(this.rows.map((r) => r.tuyen))];
    const cell = (role: string, route: string) => {
      const r = this.rows.find((x) => x.vaiTro === role && x.tuyen === route);
      if (!r) return '';
      return r.kyVong === r.thucTe ? `✓ ${r.kyVong}` : `✗ cần ${r.kyVong}, thấy ${r.thucTe}`;
    };
    const lech = this.rows.filter((r) => r.kyVong !== r.thucTe).length;
    const lines = [
      `# Đối chiếu ma trận phân quyền (${this.options.mode})`,
      '',
      `${this.rows.length} ca, ${this.rows.length - lech} đạt, ${lech} lệch. Ký hiệu: "vào" là mở được tuyến, "chặn" là sang trang không có quyền, "menu" là mục menu hiện, "→ /x" là được chuyển tới /x.`,
      '',
      `| Vai trò | ${routes.join(' | ')} |`,
      `|---|${routes.map(() => '---').join('|')}|`,
      ...roles.map(
        (role) => `| ${role} | ${routes.map((route) => cell(role, route)).join(' | ')} |`,
      ),
      '',
    ];
    const dir = join(__dirname, '..', 'dist', 'e2e-ket-qua');
    mkdirSync(dir, { recursive: true });
    const file = join(dir, `bao-cao-${this.options.mode}.md`);
    writeFileSync(file, lines.join('\n'));
    console.log(`\nBảng vai trò × tuyến: ${file} (${lech} lệch)`);
  }
}
