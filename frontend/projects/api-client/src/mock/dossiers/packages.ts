import {
  DossierChecklistItem,
  DossierForm,
  DossierFormCode,
  DossierPackage,
  DossierPackageKind,
  DossierTransferRequest,
} from '../../lib/models/dossier';
import { Job } from '../../lib/models/job';
import { mockSession } from '../mock-session';
import { MockRequest, MockResponse, MockRoute, ok, problem } from '../mock-router';

/**
 * Giả lập gói hồ sơ số của MOD-06 (QT-11 bước 12–14, SCR-1102, WEB-041) theo `dossiers.yaml`.
 *
 * Bốn gói của "Chi bộ Ban Tổ chức": G1 gói năm 2026 chờ ký nhưng danh sách kiểm tra còn hai mục cảnh báo
 * (theo mockup: Mẫu 1C còn 2 người chưa ký, Mẫu 3 chờ Trưởng ban ký) nên ký trả 409 MSG-11-04, có 2 hồ sơ diện
 * Trung ương; G2 gói quý III/2026 đủ điều kiện ký, ký xong chuyển `DA_NOP` và mở "Gửi gói con"; G3 gói quý
 * II/2026 đang lập, có 2 tệp sinh lỗi (MSG-11-07) để thử "Sinh lại mẫu lỗi"; G4 gói quý I/2026 đã nộp, bị khoá
 * (BR-11-10), lần gửi gói con trước chưa thành công nên gửi lại được. Ký gói cần phiên AAL2 (401 step-up) và hoàn tất ở lần hỏi job thứ hai (yêu cầu ký chờ xác nhận
 * trên ứng dụng ký từ xa). Gửi gói con tới Văn phòng Trung ương trả 422 (chưa cấu hình kết nối); URL trang có
 * `?mock-integ=loi` thì job gửi thất bại để hiện MSG-11-06.
 */

const ID = '0192a3f4-1102-7000-8000-';
const FID = '0192a3f4-1103-7000-8000-';
const packageId = (n: number) => `${ID}${String(n).padStart(12, '0')}`;
const UNIT = { id: '0192a3f4-0001-7000-8000-000000000010', name: 'Chi bộ Ban Tổ chức' };
const PID = '0192a3f4-0003-7000-8000-';
const PEOPLE = [
  'Ngô Thanh Tâm',
  'Trần Minh Hoàng',
  'Lê Thu Hà',
  'Đỗ Quang Vinh',
  'Phạm Đức Long',
  'Hoàng Thị Mai',
].map((name, i) => ({ id: `${PID}${String(101 + i).padStart(12, '0')}`, name }));

const PERIOD = {
  'p-2026': { id: 'p-2026', label: 'Năm 2026', startDate: '2026-01-01', endDate: '2026-12-31' },
  'p-2026q3': {
    id: 'p-2026q3',
    label: 'Quý III/2026',
    startDate: '2026-07-01',
    endDate: '2026-09-30',
  },
  'p-2026q2': {
    id: 'p-2026q2',
    label: 'Quý II/2026',
    startDate: '2026-04-01',
    endDate: '2026-06-30',
  },
  'p-2026q1': {
    id: 'p-2026q1',
    label: 'Quý I/2026',
    startDate: '2026-01-01',
    endDate: '2026-03-31',
  },
} as const;

interface Stored {
  p: DossierPackage;
  forms: DossierForm[];
  checklist: DossierChecklistItem[];
}

let packages: Stored[] = [];
let seq = 0;
const jobs = new Map<string, { job: Job; polls: number; onDone?: () => Job }>();
const exports = new Map<string, Blob>();

/** Mã băm giả lập 64 ký tự hex, ổn định theo hạt giống. */
function hex(seed: number): string {
  let x = seed * 2654435761;
  let out = '';
  while (out.length < 64) {
    x = (x * 1103515245 + 12345) >>> 0;
    out += x.toString(16).padStart(8, '0');
  }
  return out.slice(0, 64);
}

/** Biểu mẫu của gói: mỗi người Mẫu 1B, 2 và bảng chấm điểm; cả đơn vị Mẫu 1A, 1C, 3. */
function formsOf(n: number, signed: boolean, failed = 0): DossierForm[] {
  const out: DossierForm[] = [];
  const add = (formCode: DossierFormCode, person?: { id: string; name: string }) => {
    const i = out.length + 1;
    const id = `${FID}${String(n * 100 + i).padStart(12, '0')}`;
    out.push({
      id,
      formCode,
      packageId: packageId(n),
      profileId: `0192a3f4-0901-7000-8000-${String(n * 100 + i).padStart(12, '0')}`,
      ...(person ? { subjectPerson: person } : {}),
      templateVersion: 'v2.0',
      status: signed ? 'DA_KY' : 'DA_SINH',
      renderStatus: 'XONG',
      format: 'PDF/A-2b',
      dataSnapshotHash: hex(n * 1000 + i),
      pdfSha256: hex(n * 2000 + i),
      signedAt: signed ? '2026-10-12T09:30:00+07:00' : null,
    });
  };
  add('MAU_1A');
  for (const p of PEOPLE) {
    add('MAU_1B', p);
    add('MAU_2', p);
    add('BANG_CHAM_DIEM', p);
  }
  add('MAU_1C');
  add('MAU_3');
  for (const f of out.slice(-failed - 2, -2)) {
    f.status = 'LOI';
    f.renderStatus = 'LOI';
    f.pdfSha256 = null;
    f.renderError = 'Thiếu dữ liệu kết quả thực hiện nhiệm vụ của quý.';
  }
  return out;
}

function counts(forms: DossierForm[]): DossierPackage['formCounts'] {
  return {
    total: forms.length,
    rendered: forms.filter((f) => f.status === 'DA_SINH' || f.status === 'DA_KY').length,
    failed: forms.filter((f) => f.status === 'LOI').length,
    signed: forms.filter((f) => f.status === 'DA_KY').length,
  };
}

const item = (code: string, label: string, okay = true, count?: number): DossierChecklistItem => ({
  code,
  label,
  status: okay ? 'DAT' : 'CANH_BAO',
  ...(count ? { count } : {}),
});

function passedChecklist(n: number): DossierChecklistItem[] {
  return [
    item('HO_SO_DA_KY_SO', `${n}/${n} hồ sơ đã ký số`),
    item('MA_BAM_KHOP', 'Mã băm từng tệp khớp manifest'),
    item('CA_NHAN_DA_KY', 'Các cá nhân đã ký bản kiểm điểm'),
    item('MAU_3_DA_KY', 'Mẫu 3 đã được ký'),
    item('KHONG_KHIEU_NAI_MO', 'Không còn khiếu nại đang mở'),
  ];
}

function pkg(
  n: number,
  o: {
    code: string;
    kind: DossierPackageKind;
    period: keyof typeof PERIOD;
    status: DossierPackage['status'];
    deadline: string;
    forms: DossierForm[];
    central?: DossierPackage['central'];
    submittedAt?: string;
  },
): DossierPackage {
  const locked = o.status === 'DA_KY' || o.status === 'DA_NOP' || o.status === 'DA_LUU_TRU';
  return {
    id: packageId(n),
    code: o.code,
    packageKind: o.kind,
    period: { ...PERIOD[o.period] },
    orgUnit: { ...UNIT },
    person: null,
    status: o.status,
    deadline: o.deadline,
    submittedAt: o.submittedAt ?? null,
    versionNo: 1,
    formCounts: counts(o.forms),
    profileCount: PEOPLE.length,
    manifestHash: o.status === 'DANG_LAP' ? null : hex(n * 77),
    packageSha256: locked ? hex(n * 91) : null,
    ...(o.central ? { central: o.central } : {}),
    transferredTo: o.central?.transferStatus === 'DA_GUI' ? (o.central.destination ?? null) : null,
    signRequestId: locked ? `0192a3f4-1810-7000-8000-${String(n).padStart(12, '0')}` : null,
    rowVersion: 1,
  };
}

function seed(): Stored[] {
  const f1 = formsOf(1, false);
  f1.filter((f) => f.formCode !== 'MAU_1C' && f.formCode !== 'MAU_3').forEach((f) => {
    f.status = 'DA_KY';
    f.signedAt = '2027-01-20T10:00:00+07:00';
  });
  const g1 = pkg(1, {
    code: 'DP-2026-N-0142',
    kind: 'NAM',
    period: 'p-2026',
    status: 'CHO_KY',
    deadline: '2027-02-01',
    forms: f1,
    central: { profileCount: 2, destination: 'BTC_TW', transferStatus: 'CHUA_GUI' },
  });
  const f2 = formsOf(2, true);
  const g2 = pkg(2, {
    code: 'DP-2026-Q-0131',
    kind: 'QUY',
    period: 'p-2026q3',
    status: 'CHO_KY',
    deadline: '2026-10-31',
    forms: f2,
    central: { profileCount: 1, destination: 'BTC_TW', transferStatus: 'CHUA_GUI' },
  });
  const f3 = formsOf(3, false, 2);
  const g3 = pkg(3, {
    code: 'DP-2026-Q-0098',
    kind: 'QUY',
    period: 'p-2026q2',
    status: 'DANG_LAP',
    deadline: '2026-07-31',
    forms: f3,
  });
  const f4 = formsOf(4, true);
  const g4 = pkg(4, {
    code: 'DP-2026-Q-0075',
    kind: 'QUY',
    period: 'p-2026q1',
    status: 'DA_NOP',
    deadline: '2026-04-30',
    forms: f4,
    submittedAt: '2026-04-24T15:12:00+07:00',
    central: { profileCount: 1, destination: 'BTC_TW', transferStatus: 'LOI' },
  });
  return [
    {
      p: g1,
      forms: f1,
      checklist: [
        item('HO_SO_DA_KY_SO', `${PEOPLE.length}/${PEOPLE.length} hồ sơ đã ký số`),
        item('MA_BAM_KHOP', 'Mã băm từng tệp khớp manifest'),
        item('CA_NHAN_DA_KY', 'Mẫu 1C còn 2 người chưa ký', false, 2),
        item('MAU_3_DA_KY', 'Mẫu 3 chờ Trưởng ban ký', false, 1),
        item('KHONG_KHIEU_NAI_MO', 'Không còn khiếu nại đang mở'),
      ],
    },
    { p: g2, forms: f2, checklist: passedChecklist(PEOPLE.length) },
    {
      p: g3,
      forms: f3,
      checklist: [
        item('HO_SO_DA_KY_SO', `${PEOPLE.length}/${PEOPLE.length} hồ sơ đã ký số`),
        item('MA_BAM_KHOP', 'Còn 2 tệp chưa sinh nên chưa đối chiếu được manifest', false, 2),
        item('CA_NHAN_DA_KY', 'Các cá nhân đã ký bản kiểm điểm'),
        item('MAU_3_DA_KY', 'Mẫu 3 chưa ký', false, 1),
        item('KHONG_KHIEU_NAI_MO', 'Không còn khiếu nại đang mở'),
      ],
    },
    { p: g4, forms: f4, checklist: passedChecklist(PEOPLE.length) },
  ];
}

/** Trả bản sao như máy chủ thật, để màn hình không giữ chung tham chiếu với dữ liệu giả lập. */
const copy = <T>(x: T): T => structuredClone(x);
const etag = (p: DossierPackage) => ({ ETag: `"${p.rowVersion}"` });
const locked = (p: DossierPackage) =>
  p.status === 'DA_KY' || p.status === 'DA_NOP' || p.status === 'DA_LUU_TRU';
const pageQuery = () => new URLSearchParams(globalThis.location?.search ?? '');

function find(req: MockRequest): Stored | MockResponse {
  const s = packages.find((x) => x.p.id === req.pathParams['id']);
  return s ?? problem(404, 'Không tìm thấy gói hồ sơ');
}
const isRes = (x: unknown): x is MockResponse =>
  !!x && typeof x === 'object' && 'status' in x && !('p' in x);

function versionGuard(req: MockRequest, p: DossierPackage): MockResponse | null {
  const match = req.headers.get('If-Match');
  if (!match)
    return problem(
      428,
      'Dữ liệu trên màn hình đã cũ. Vui lòng tải lại rồi thực hiện lại thao tác.',
    );
  if (match.replace(/"/g, '') !== String(p.rowVersion))
    return problem(409, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      code: 'DOSSIER-409-VERSION-CONFLICT',
    });
  return null;
}

const lockedProblem = () =>
  problem(409, 'Gói đã nộp bị khoá; mọi thay đổi phải qua mở lại kỳ.', {
    code: 'DOSSIER-409-PACKAGE-LOCKED',
  });

/** Ghi một job; `onDone` chạy ở lần hỏi thứ `after` và trả job kết thúc. */
function accept(type: string, message: string, onDone: () => Job, after = 1): MockResponse {
  const id = `job-package-${++seq}`;
  const job: Job = { id, type, status: 'DANG_CHAY', progress: 10, message };
  jobs.set(id, { job, polls: after, onDone });
  return {
    status: 202,
    body: job,
    headers: { Location: `/api/v1/dossiers/form-jobs/${id}` },
  };
}

function list(req: MockRequest): MockResponse {
  const status = req.params.get('status');
  const period = req.params.get('period');
  const items = packages
    .map((s) => s.p)
    .filter((p) => (!status || p.status === status) && (!period || p.period.id === period));
  return ok({ items: copy(items), nextCursor: null });
}

function forms(req: MockRequest): MockResponse {
  const s = find(req);
  if (isRes(s)) return s;
  const status = req.params.get('status');
  const code = req.params.get('formCode');
  const items = s.forms.filter(
    (f) => (!status || f.status === status) && (!code || f.formCode === code),
  );
  return ok({ items: copy(items), nextCursor: null });
}

function checklist(req: MockRequest): MockResponse {
  const s = find(req);
  if (isRes(s)) return s;
  return ok({
    passed: s.checklist.every((i) => i.status === 'DAT'),
    evaluatedAt: new Date().toISOString(),
    items: copy(s.checklist),
  });
}

function regenerate(req: MockRequest): MockResponse {
  const s = find(req);
  if (isRes(s)) return s;
  if (locked(s.p)) return lockedProblem();
  const guard = versionGuard(req, s.p);
  if (guard) return guard;
  const ids = (req.body as { formIds?: string[] } | null)?.formIds;
  const failed = s.forms.filter((f) => f.status === 'LOI' && (!ids?.length || ids.includes(f.id)));
  if (!failed.length)
    return problem(409, 'Gói không có tệp sinh lỗi.', { code: 'DOSSIER-409-NO-FAILED-FORMS' });
  s.p.status = 'DANG_SINH';
  s.p.rowVersion++;
  return accept('RENDER', 'Đang sinh lại biểu mẫu lỗi', () => {
    for (const f of failed) {
      f.status = 'DA_SINH';
      f.renderStatus = 'XONG';
      f.renderError = null;
      f.pdfSha256 = hex(f.id.length * 31 + seq);
    }
    s.p.formCounts = counts(s.forms);
    s.p.status = s.p.formCounts.failed ? 'DANG_LAP' : 'CHO_KY';
    s.p.manifestHash = hex(seq * 13);
    s.checklist = s.checklist.map((i) =>
      i.code === 'MA_BAM_KHOP' ? item('MA_BAM_KHOP', 'Mã băm từng tệp khớp manifest') : i,
    );
    s.p.rowVersion++;
    return { id: '', type: 'RENDER', status: 'HOAN_TAT', progress: 100 };
  });
}

/** CRC-32 (IEEE) cho tệp ZIP. */
function crc32(bytes: Uint8Array): number {
  let c = ~0;
  for (const b of bytes) {
    c ^= b;
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return ~c >>> 0;
}

/** ZIP tối giản (lưu không nén) gồm các tệp văn bản. */
function zip(entries: { name: string; text: string }[]): Blob {
  const enc = new TextEncoder();
  const parts: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;
  const u16 = (v: DataView, at: number, n: number) => v.setUint16(at, n, true);
  const u32 = (v: DataView, at: number, n: number) => v.setUint32(at, n, true);
  for (const e of entries) {
    const name = enc.encode(e.name);
    const data = enc.encode(e.text);
    const crc = crc32(data);
    const local = new Uint8Array(30 + name.length);
    const lv = new DataView(local.buffer);
    u32(lv, 0, 0x04034b50);
    u16(lv, 4, 20);
    u16(lv, 6, 0x0800);
    u32(lv, 14, crc);
    u32(lv, 18, data.length);
    u32(lv, 22, data.length);
    u16(lv, 26, name.length);
    local.set(name, 30);
    const head = new Uint8Array(46 + name.length);
    const hv = new DataView(head.buffer);
    u32(hv, 0, 0x02014b50);
    u16(hv, 4, 20);
    u16(hv, 6, 20);
    u16(hv, 8, 0x0800);
    u32(hv, 16, crc);
    u32(hv, 20, data.length);
    u32(hv, 24, data.length);
    u16(hv, 28, name.length);
    u32(hv, 42, offset);
    head.set(name, 46);
    parts.push(local, data);
    central.push(head);
    offset += local.length + data.length;
  }
  const size = central.reduce((a, c) => a + c.length, 0);
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  u32(ev, 0, 0x06054b50);
  u16(ev, 8, entries.length);
  u16(ev, 10, entries.length);
  u32(ev, 12, size);
  u32(ev, 16, offset);
  return new Blob([...parts, ...central, end] as BlobPart[], { type: 'application/zip' });
}

function exportPackage(req: MockRequest): MockResponse {
  const s = find(req);
  if (isRes(s)) return s;
  if (s.p.status === 'DANG_LAP' || s.p.status === 'DANG_SINH')
    return problem(409, 'Gói chưa sẵn sàng để tải.', { code: 'DOSSIER-409-PACKAGE-NOT-READY' });
  const p = s.p;
  return accept('EXPORT', 'Đang đóng gói tệp ZIP', () => {
    const manifest = {
      code: p.code,
      period: p.period.label,
      orgUnit: p.orgUnit?.name,
      manifestHash: p.manifestHash,
      packageSha256: p.packageSha256 ?? null,
      files: s.forms
        .filter((f) => f.pdfSha256)
        .map((f) => ({ formId: f.id, formCode: f.formCode, sha256: f.pdfSha256 })),
    };
    const id = `export-${++seq}`;
    exports.set(
      id,
      zip([
        { name: 'manifest.json', text: JSON.stringify(manifest, null, 2) },
        {
          name: 'DOC-TOI.txt',
          text: `Goi ${p.code}: ban gia lap chi gom manifest, khong kem tep PDF/A.\n`,
        },
      ]),
    );
    return {
      id: '',
      type: 'EXPORT',
      status: 'HOAN_TAT',
      progress: 100,
      resultLocation: `/api/v1/dossiers/package-exports/${id}`,
    };
  });
}

function transfer(req: MockRequest): MockResponse {
  const s = find(req);
  if (isRes(s)) return s;
  const body = (req.body ?? {}) as Partial<DossierTransferRequest>;
  if (!locked(s.p))
    return problem(409, 'Gói chưa được ký nên chưa gửi được gói con.', {
      code: 'DOSSIER-409-PACKAGE-NOT-SIGNED',
    });
  if (!s.p.central?.profileCount)
    return problem(409, 'Gói không có hồ sơ diện Trung ương quản lý.', {
      code: 'DOSSIER-409-NO-CENTRAL-PROFILES',
    });
  if (body.destination !== 'BTC_TW')
    return problem(422, 'Nơi nhận chưa được cấu hình kết nối.', {
      code: 'DOSSIER-422-DESTINATION-NOT-CONFIGURED',
      errors: [{ field: 'destination', message: 'Nơi nhận này chưa được cấu hình kết nối.' }],
    });
  const central = s.p.central;
  central.destination = body.destination;
  central.transferStatus = 'DANG_GUI';
  const fail = pageQuery().get('mock-integ') === 'loi';
  return accept('EXPORT', 'Đang gửi gói con qua cổng tích hợp', () => {
    if (fail) {
      central.transferStatus = 'LOI';
      const retry = new Date(Date.now() + 30 * 60_000);
      return {
        id: '',
        type: 'EXPORT',
        status: 'THAT_BAI',
        problem: {
          type: 'about:blank',
          title: 'Gửi gói con chưa thành công',
          status: 502,
          detail: 'Bên nhận chưa phản hồi',
          retryAt: retry.toISOString(),
        } as Job['problem'],
      };
    }
    central.transferStatus = 'DA_GUI';
    s.p.transferredTo = central.destination ?? null;
    return { id: '', type: 'EXPORT', status: 'HOAN_TAT', progress: 100 };
  });
}

function sign(req: MockRequest): MockResponse {
  const s = find(req);
  if (isRes(s)) return s;
  if (locked(s.p)) return lockedProblem();
  if (!mockSession.recentMfa()) {
    const r = problem(401, 'Cần xác thực nhiều lớp trước khi ký số gói', {
      type: 'https://datatracker.ietf.org/doc/html/rfc9470',
      code: 'insufficient_user_authentication',
    });
    r.headers = {
      ...r.headers,
      // ui-text-ok: tiêu đề HTTP theo RFC 9470, không hiện lên màn hình
      'WWW-Authenticate':
        'Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=900',
    };
    return r;
  }
  const guard = versionGuard(req, s.p);
  if (guard) return guard;
  if (s.p.status !== 'CHO_KY')
    return problem(409, 'Gói chưa ở trạng thái chờ ký.', { code: 'DOSSIER-409-INVALID-STATE' });
  const failing = s.checklist.filter((i) => i.status !== 'DAT').length;
  if (failing)
    return problem(409, 'Chưa thể ký gói', {
      code: 'DOSSIER-409-CHECKLIST-NOT-PASSED',
      msg: 'MSG-11-04',
      detail: `Chưa thể ký gói: ${failing} mục kiểm tra chưa đạt. Xem chi tiết ở danh sách kiểm tra.`,
    });
  if (mockSession.esignDown() || pageQuery().get('mock-esign') === 'loi')
    return problem(422, 'Chứng thư của người ký không hợp lệ.', {
      code: 'ESIGN-422-CERTIFICATE-INVALID',
    });
  const signRequestId = `0192a3f4-1810-7000-8000-${String(900 + ++seq).padStart(12, '0')}`;
  s.p.signRequestId = signRequestId;
  s.p.rowVersion++;
  const res = accept(
    'ESIGN_BATCH',
    'Chờ người ký xác nhận trên ứng dụng ký số',
    () => {
      s.p.status = 'DA_NOP';
      s.p.submittedAt = new Date().toISOString();
      s.p.packageSha256 = hex(seq * 17);
      s.p.rowVersion++;
      return { id: '', type: 'ESIGN_BATCH', status: 'HOAN_TAT', progress: 100 };
    },
    2,
  );
  res.body = { ...(res.body as Job), signRequestId };
  return res;
}

/** Job của gói (`Location` của sinh lại, tải gói, gửi gói con, ký gói); hỏi đủ số lần thì kết thúc. */
export function packageJob(id: string): Job | undefined {
  const j = jobs.get(id);
  if (!j) return undefined;
  if (j.job.status === 'DANG_CHAY' && --j.polls <= 0 && j.onDone) {
    j.job = { ...j.onDone(), id };
    j.onDone = undefined;
  }
  return copy(j.job);
}

/** Tệp PDF/A của một biểu mẫu trong gói (`GET /dossiers/forms/{id}/file`): các dòng in vào tệp mẫu. */
export function packageFormFile(id: string): { lines: string[]; draft: boolean } | undefined {
  const s = packages.find((x) => x.forms.some((f) => f.id === id));
  const f = s?.forms.find((x) => x.id === id);
  if (!s || !f || f.status === 'LOI' || f.status === 'CHO_SINH') return undefined;
  return {
    draft: false,
    lines: [
      `${f.formCode.replace('MAU_', 'Mau ').replace('BANG_CHAM_DIEM', 'Bang cham diem')}`,
      `Goi ${s.p.code} - ${s.p.period.label}`,
      f.subjectPerson ? `Can bo: ${f.subjectPerson.name}` : `Don vi: ${s.p.orgUnit?.name ?? ''}`,
      `${f.format ?? 'PDF/A-2b'} - tem ${f.templateVersion}`,
    ],
  };
}

/** Biểu mẫu sinh lỗi thì tải tệp trả 409. */
export function packageFormFailed(id: string): boolean {
  return packages.some((s) => s.forms.some((f) => f.id === id && f.status === 'LOI'));
}

export function resetPackages(): void {
  seq = 0;
  jobs.clear();
  exports.clear();
  packages = seed();
}
resetPackages();

export const PACKAGE_ROUTES: MockRoute[] = [
  { method: 'GET', pattern: '/api/v1/dossiers/packages', handle: list },
  {
    method: 'GET',
    pattern: '/api/v1/dossiers/packages/:id',
    handle: (req) => {
      const s = find(req);
      return isRes(s) ? s : ok(copy(s.p), etag(s.p));
    },
  },
  { method: 'GET', pattern: '/api/v1/dossiers/packages/:id/forms', handle: forms },
  { method: 'GET', pattern: '/api/v1/dossiers/packages/:id/checklist', handle: checklist },
  { method: 'POST', pattern: '/api/v1/dossiers/packages/:id/regenerate', handle: regenerate },
  { method: 'POST', pattern: '/api/v1/dossiers/packages/:id/export', handle: exportPackage },
  { method: 'POST', pattern: '/api/v1/dossiers/packages/:id/transfers', handle: transfer },
  { method: 'POST', pattern: '/api/v1/dossiers/packages/:id/sign', handle: sign },
  {
    method: 'GET',
    pattern: '/api/v1/dossiers/package-exports/:id',
    handle: (req) => {
      const blob = exports.get(req.pathParams['id']);
      if (!blob) return problem(404, 'Không tìm thấy tệp gói');
      return {
        status: 200,
        body: blob,
        headers: {
          'Content-Type': 'application/zip',
          'Content-Disposition': 'attachment; filename="goi-ho-so.zip"',
        },
      };
    },
  },
];
