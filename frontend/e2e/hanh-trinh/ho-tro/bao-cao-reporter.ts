import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { FullResult, Reporter, TestCase, TestResult } from '@playwright/test/reporter';

/**
 * Báo cáo của chế độ `he-thong` (QA-03) ở `dist/e2e-ket-qua/bao-cao-he-thong.md`: mỗi hành trình một
 * dòng với kết quả, thời gian, các bước đang chờ việc khác và `X-Request-Id` của phản hồi lỗi để tra
 * nhật ký máy chủ. Không ghi địa chỉ, tài khoản, token hay thân yêu cầu.
 */
interface Dong {
  hanhTrinh: string;
  ketQua: string;
  giay: number;
  cho: string[];
  requestId: string[];
  conLai: string[];
  loi: string | null;
}

const KET_QUA: Record<string, string> = {
  passed: 'Đạt',
  failed: 'Không đạt',
  timedOut: 'Quá thời gian',
  interrupted: 'Bị ngắt',
};

export default class BaoCaoHeThong implements Reporter {
  private readonly dong: Dong[] = [];

  onTestEnd(test: TestCase, result: TestResult): void {
    const ann = (t: string) =>
      test.annotations.filter((a) => a.type === t && a.description).map((a) => a.description!);
    const cho = ann('cho');
    this.dong.push({
      hanhTrinh: test.titlePath().slice(2).join(' › '),
      ketQua:
        result.status === 'skipped'
          ? cho.length
            ? 'Chờ'
            : 'Bỏ qua'
          : (KET_QUA[result.status] ?? result.status),
      giay: Math.round(result.duration / 100) / 10,
      cho,
      requestId: ann('x-request-id'),
      conLai: ann('du-lieu-con-lai'),
      loi: result.error?.message?.split('\n')[0]?.replace(/\u001b\[[0-9;]*m/g, '') ?? null,
    });
  }

  onEnd(result: FullResult): void {
    if (this.dong.length === 0) return;
    const dem = (k: string) => this.dong.filter((d) => d.ketQua === k).length;
    const o = (s: string) => s.replaceAll('|', '\\|');
    const lines = [
      '# Kiểm thử đầu cuối trên hệ thống thật',
      '',
      `Thời điểm: ${new Date().toISOString()}. Tổng kết: ${result.status}; ${this.dong.length} hành trình, ` +
        `${dem('Đạt')} đạt, ${dem('Không đạt') + dem('Quá thời gian')} không đạt, ${dem('Chờ')} chờ việc khác.`,
      '',
      '| Hành trình | Kết quả | Thời gian (giây) | Ghi chú |',
      '|---|---|---|---|',
      ...this.dong.map((d) => {
        const ghiChu = [
          ...d.cho.map((c) => `Chờ: ${c}`),
          ...(d.loi && d.ketQua !== 'Đạt' && d.ketQua !== 'Chờ' ? [`Lỗi: ${d.loi}`] : []),
          ...d.requestId.map((r) => `Phản hồi lỗi: ${r}`),
          ...d.conLai,
        ];
        return `| ${o(d.hanhTrinh)} | ${d.ketQua} | ${d.giay} | ${o(ghiChu.join('<br>'))} |`;
      }),
      '',
    ];
    const dir = join(__dirname, '..', '..', '..', 'dist', 'e2e-ket-qua');
    mkdirSync(dir, { recursive: true });
    const file = join(dir, 'bao-cao-he-thong.md');
    writeFileSync(file, lines.join('\n'));
    console.log(`\nBáo cáo hệ thống thật: ${file}`);
  }
}
