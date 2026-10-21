import {
  DossierDraftFormsRequest,
  DossierFormPreviewRequest,
  EsignCertificate,
} from '../../lib/models/appraisal';
import { Job } from '../../lib/models/job';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import { MEETING_ROUTES, meetingDraftFile, meetingJob, resetMeetings } from './meetings';
import {
  PACKAGE_ROUTES,
  packageFormFailed,
  packageFormFile,
  packageJob,
  resetPackages,
} from './packages';

/**
 * Giả lập SVC-11 docgen dưới `/api/v1/dossiers` (dossiers.yaml). Hiện chỉ có "Xem trước" biểu mẫu
 * (`POST /dossiers/forms/preview`) cho SCR-0901 "Xem trước Mẫu 1B" (WEB-035): trả một tệp PDF một trang
 * có dấu "BAN NHAP". Bản giả lập dùng phông chuẩn Helvetica nên bỏ dấu tiếng Việt; bản thật do SVC-11
 * sinh từ mẫu `docgen.template`. Tải tệp biểu mẫu đã ký (`GET /dossiers/forms/{id}/file`) cho SCR-0903
 * "Tải Mẫu 1B" (WEB-037): biểu mẫu nào có trong `SIGNED_FORMS` thì trả PDF, còn lại 404. Các màn hình QT-11 (SCR-1101, SCR-1102) bổ sung tuyến khi làm.
 *
 * SCR-0904 (WEB-038): "Xuất Mẫu 3" (`POST /dossiers/forms`, 202, job hoàn tất ngay, tệp PDF nháp) và
 * chứng thư của người ký (`GET /esign/certificates/mine`, còn hạn tới 14/08/2027; `?mock-cert=het-han`
 * trả chứng thư đã hết hạn).
 *
 * SCR-1101 (WEB-040): hội nghị kiểm điểm, góp ý, biên bản, khắc phục hạn chế và trường hợp đặc thù ở
 * `meetings.ts`; dự thảo Mẫu 1B của hội nghị và job sinh biên bản đi qua hai tuyến tệp và job dưới đây.
 *
 * SCR-1102 (WEB-041): gói hồ sơ số, danh sách kiểm tra, sinh lại, tải gói, gửi gói con và ký gói ở
 * `packages.ts`; tệp biểu mẫu của gói và job của gói cũng đi qua hai tuyến tệp và job dưới đây.
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
export function miniPdf(lines: string[], draft = true): Blob {
  const text = lines
    .map((l, i) => `BT /F1 ${i === 0 ? 16 : 11} Tf 56 ${780 - i * 22} Td (${ascii(l)}) Tj ET`)
    .join('\n');
  // ui-text-ok: lệnh vẽ dấu "BẢN NHÁP" trong tệp PDF mẫu, không phải văn bản giao diện
  const stamp = 'BT /F1 48 Tf 0.85 g 140 420 Td (BAN NHAP) Tj ET';
  const content = draft ? `${stamp}\n${text}` : text;
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

/** Mẫu 1B đã ký của hồ sơ giả lập (`signedFormId` trong `appraisals/rating-sheet.ts`). */
const SIGNED_FORMS: Record<string, string[]> = {
  // ui-text-ok: khoá tra cứu của dữ liệu giả lập, không hiển thị
  '01928f3e-7b1a-7c3e-9a51-3f2d9c1b0f45': [
    FORM_NAME['MAU_1B'],
    'Can bo: Tran Minh Hoang - Quy III/2026',
    'Ket qua xep loai: Hoan thanh tot nhiem vu',
    'Da ky so quyet dinh ngay 12/10/2026.',
  ],
};

/** Biểu mẫu nháp đã sinh qua `POST /dossiers/forms` (mã biểu mẫu → các dòng của tệp). */
const draftForms = new Map<string, string[]>();
const formJobs = new Map<string, Job>();

function createDraftForms(req: MockRequest): MockResponse {
  const body = (req.body ?? {}) as Partial<DossierDraftFormsRequest>;
  const n = body.profileIds?.length ?? 0;
  if (body.formCode !== 'MAU_3' || !body.periodId || n < 1 || n > 200)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'profileIds', message: 'Chọn từ 1 đến 200 hồ sơ để xuất Mẫu 3.' }],
    });
  const formId = crypto.randomUUID();
  draftForms.set(formId, [
    'Mau 3 - Tong hop ket qua danh gia, xep loai chat luong',
    `So ho so: ${n}`,
    `Sinh luc: ${new Date().toLocaleString('vi-VN')}`,
    'Ban nhap tu danh sach dang chon, khong co gia tri phap ly.',
  ]);
  const id = crypto.randomUUID();
  const job: Job = {
    id,
    type: 'RENDER',
    status: 'HOAN_TAT',
    progress: 100,
    message: 'Đã sinh Mẫu 3 dạng nháp',
    resultLocation: `/api/v1/dossiers/forms/${formId}`,
  };
  formJobs.set(id, job);
  return {
    status: 202,
    body: { ...job, status: 'CHO', progress: 0 },
    headers: { Location: `/api/v1/dossiers/form-jobs/${id}` },
  };
}

function certificates(): MockResponse {
  const expired =
    new URLSearchParams(globalThis.location?.search ?? '').get('mock-cert') === 'het-han';
  const cert: EsignCertificate = {
    id: '01928f3e-7b1a-7c3e-9a51-3f2d9c1b0e91',
    subjectDn: 'CN=Ngô Thanh Tâm, OU=Ban Tổ chức Đảng uỷ khối, O=Đảng uỷ khối, C=VN',
    issuerDn: 'CN=MySign CA, O=Viettel Group, C=VN',
    serialNo: '5403A1F2C9B0E7D1',
    notBefore: '2025-08-15T00:00:00+07:00',
    notAfter: expired ? '2026-08-14T23:59:59+07:00' : '2027-08-14T23:59:59+07:00',
    lastOcspStatus: 'GOOD',
    lastOcspAt: new Date().toISOString(),
  };
  return ok({ items: [cert] });
}

export const DOSSIERS_MOCK: MockModule = {
  name: 'dossiers',
  reset: () => {
    draftForms.clear();
    formJobs.clear();
    resetMeetings();
    resetPackages();
  },
  routes: [
    ...MEETING_ROUTES,
    ...PACKAGE_ROUTES,
    { method: 'POST', pattern: '/api/v1/dossiers/forms', handle: createDraftForms },
    {
      method: 'GET',
      pattern: '/api/v1/dossiers/form-jobs/:id',
      handle: (req) => {
        const id = req.pathParams['id'];
        const job = formJobs.get(id) ?? meetingJob(id) ?? packageJob(id);
        return job ? ok(job) : problem(404, 'Không tìm thấy tác vụ sinh biểu mẫu');
      },
    },
    { method: 'GET', pattern: '/api/v1/esign/certificates/mine', handle: certificates },
    {
      method: 'POST',
      pattern: '/api/v1/dossiers/forms/preview',
      handle: (req) => preview(req.body as Partial<DossierFormPreviewRequest> | null),
    },
    {
      method: 'GET',
      pattern: '/api/v1/dossiers/forms/:id/file',
      handle: (req) => {
        const draft = draftForms.get(req.pathParams['id']);
        const meetingDraft = meetingDraftFile(req.pathParams['id']);
        const packageForm = packageFormFile(req.pathParams['id']);
        if (packageFormFailed(req.pathParams['id']))
          return problem(409, 'Biểu mẫu chưa sinh xong hoặc sinh lỗi.', {
            code: 'DOSSIER-409-FORM-NOT-RENDERED',
          });
        const lines =
          draft ?? meetingDraft ?? packageForm?.lines ?? SIGNED_FORMS[req.pathParams['id']];
        if (!lines) return problem(404, 'Không tìm thấy biểu mẫu');
        return {
          status: 200,
          body: miniPdf(lines, !!draft || !!meetingDraft),
          headers: {
            'Content-Type': 'application/pdf',
            'Content-Disposition': `attachment; filename="${draft ? 'Mau-3-ban-nhap' : meetingDraft ? 'Mau-1B-du-thao' : packageForm ? 'Bieu-mau' : 'Mau-1B-Quy-III-2026'}.pdf"`,
          },
        };
      },
    },
  ],
};
