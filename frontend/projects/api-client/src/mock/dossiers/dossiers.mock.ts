import { DossierFormPreviewRequest } from '../../lib/models/appraisal';
import { MockModule, MockResponse, problem } from '../mock-router';

/**
 * Giả lập SVC-11 docgen dưới `/api/v1/dossiers` (dossiers.yaml). Hiện chỉ có "Xem trước" biểu mẫu
 * (`POST /dossiers/forms/preview`) cho SCR-0901 "Xem trước Mẫu 1B" (WEB-035): trả một tệp PDF một trang
 * có dấu "BAN NHAP". Bản giả lập dùng phông chuẩn Helvetica nên bỏ dấu tiếng Việt; bản thật do SVC-11
 * sinh từ mẫu `docgen.template`. Các màn hình QT-11 (SCR-1101, SCR-1102) bổ sung tuyến khi làm.
 */

const FORM_NAME: Record<string, string> = {
  MAU_1B: 'Mau 1B - Phieu tu danh gia, xep loai chat luong',
  MAU_2: 'Mau 2 - Phieu danh gia, xep loai chat luong',
};

/** Bỏ dấu và ký tự ngoài ASCII in được (phông chuẩn của PDF chỉ có Latin-1). */
const ascii = (s: string) =>
  s
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/đ/g, 'd')
    .replace(/Đ/g, 'D')
    .replace(/[^\x20-\x7e]/g, '?')
    .replace(/([()\\])/g, '\\$1');

/** Dựng PDF 1.4 tối giản: một trang A4, các dòng chữ; tính đúng bảng `xref`. */
export function miniPdf(lines: string[]): Blob {
  const text = lines
    .map((l, i) => `BT /F1 ${i === 0 ? 16 : 11} Tf 56 ${780 - i * 22} Td (${ascii(l)}) Tj ET`)
    .join('\n');
  const stamp = 'BT /F1 48 Tf 0.85 g 140 420 Td (BAN NHAP) Tj ET';
  const content = `${stamp}\n${text}`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let out = '%PDF-1.4\n';
  const offsets: number[] = [];
  objects.forEach((o, i) => {
    offsets.push(out.length);
    out += `${i + 1} 0 obj\n${o}\nendobj\n`;
  });
  const xref = out.length;
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  out += offsets.map((n) => `${String(n).padStart(10, '0')} 00000 n \n`).join('');
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return new Blob([out], { type: 'application/pdf' });
}

function preview(body: Partial<DossierFormPreviewRequest> | null): MockResponse {
  if (!body?.formCode || !body.sourceModule || !body.sourceRefId)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'formCode', message: 'Thiếu biểu mẫu hoặc bản ghi nguồn' }],
    });
  const name = FORM_NAME[body.formCode];
  if (!name) return problem(404, `Không có mẫu ${body.formCode}`);
  return {
    status: 200,
    body: miniPdf([
      name,
      `Nguon: ${body.sourceModule} / ${body.sourceRefId}`,
      `Sinh luc: ${new Date().toLocaleString('vi-VN')}`,
      'Ban xem truoc tu du lieu da luu, khong co gia tri phap ly.',
    ]),
    headers: { 'Content-Type': 'application/pdf' },
  };
}

export const DOSSIERS_MOCK: MockModule = {
  name: 'dossiers',
  reset: () => undefined,
  routes: [
    {
      method: 'POST',
      pattern: '/api/v1/dossiers/forms/preview',
      handle: (req) => preview(req.body as Partial<DossierFormPreviewRequest> | null),
    },
  ],
};
