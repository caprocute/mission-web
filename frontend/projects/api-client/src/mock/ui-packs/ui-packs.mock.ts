import { UiFormVersion } from '../../lib/models/ui-form';
import {
  UiComponent,
  UiFormTestRunRequest,
  UiFormTestRunResult,
  UiFormVersionCreate,
  UiFormVersionUpdate,
  UiPackCheck,
  UiPackFormAttach,
  UiPackSummary,
  UiPackVersion,
  UiPackVersionCreate,
  UiPackVersionUpdate,
  UiSduiNode,
} from '../../lib/models/ui-pack';
import { startMockJob } from '../mock-jobs';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import { MOCK_ME, mockSession } from '../mock-session';
import formsFixture from '../ui-forms/ui-forms.json';

/**
 * Giả lập cấu hình giao diện tenant (MOD-08, QT-18): UI Pack có phiên bản với vòng đời NHAP →
 * DANG_KIEM_TRA → CHO_DUYET → DA_PHAT_HANH → THAY_THE, danh mục component có phiên bản và biểu mẫu
 * động có bộ test hai engine. Ví dụ theo TL-02b: UI Pack Sở Y tế tỉnh X, biểu mẫu `phieu-vc-yte`.
 */

/** Bản ghi ghi được trong máy chủ giả lập (bỏ `readOnly` của lược đồ). */
type Ver = { -readonly [K in keyof UiPackVersion]: UiPackVersion[K] };

const uuid = (n: number) => `0192fa0b-8192-7da4-9fb6-${String(n).padStart(12, '0')}`;
const PACK_ID = uuid(8001);
const REGISTRY = '2026.3';
const MAX_HOME_WIDGETS = 12;
const out = (body: unknown, headers?: Record<string, string>) => ok(structuredClone(body), headers);
const etag = (rowVersion: number) => ({ ETag: `"${rowVersion}"` });
const notFound = () => problem(404, 'Không tìm thấy cấu hình giao diện');

const POLICY_13 = {
  packVersionId: uuid(7013),
  packCode: 'nn-335/so-y-te',
  version: '1.3',
};
const POLICY_11_STOPPED = {
  packVersionId: uuid(7011),
  packCode: 'nn-335/so-y-te',
  version: '1.1',
};

const COMPONENTS: UiComponent[] = [
  ['vt.layout.grid', true, null],
  ['vt.widget.myTasksToday', false, null],
  ['vt.widget.kpiTile', false, null],
  ['vt.widget.krCheckinDue', false, null],
  ['vt.widget.periodMilestones', false, null],
  ['vt.widget.weeklySummaryAi', false, null],
  ['vt.widget.okrProgress', false, null],
  ['vt.widget.pendingApprovals', false, null],
  ['vt.widget.announcement', false, null],
  ['yte.widget.chuyenMonKpi', false, 'fe-plugin-yte'],
].map(([componentKey, container, plugin]) => ({
  componentKey: componentKey as string,
  registryVersion: REGISTRY,
  propsSchema: { type: 'object', additionalProperties: true },
  allowedClassification: 'C1',
  status: 'HIEU_LUC',
  container: container as boolean,
  mobile: true,
  plugin: plugin as string | null,
}));

function tokens(primary: string, radius: number, size = 13) {
  return {
    color: {
      primary: { $type: 'color', $value: primary },
      onPrimary: { $type: 'color', $value: '#FFFFFF' },
      danger: { $type: 'color', $value: '#D93025' },
      text: { $type: 'color', $value: '#1F2329' },
      background: { $type: 'color', $value: '#F5F6F8' },
      surface: { $type: 'color', $value: '#FFFFFF' },
    },
    font: {
      family: { $type: 'fontFamily', $value: ['Be Vietnam Pro', 'Roboto', 'sans-serif'] },
      size: { body: { $type: 'dimension', $value: `${size}px` } },
    },
    radius: { card: { $type: 'dimension', $value: `${radius}px` } },
    space: { unit: { $type: 'dimension', $value: '4px' } },
  };
}

const home = (...keys: string[]): Record<string, UiSduiNode> => ({
  home: {
    component: 'vt.layout.grid',
    props: { columns: { xs: 1, md: 2, xl: 3 } },
    children: keys.map((component) => ({ component })),
  },
});

const LOGO = {
  key: 'logo',
  path: 'assets/logo-so-y-te-x.svg',
  mediaType: 'image/svg+xml' as const,
  sha256: '9f2c7a1e4b5d6c8f0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6071',
  sizeBytes: 8421,
};

function version(
  n: number,
  v: string,
  status: UiPackVersion['status'],
  extra: Partial<UiPackVersion>,
): UiPackVersion {
  return {
    id: uuid(8100 + n),
    uiPackId: PACK_ID,
    version: v,
    status,
    tokens: tokens('#0B6E7A', 12),
    layouts: home('vt.widget.myTasksToday', 'vt.widget.kpiTile'),
    assets: [LOGO],
    forms: [{ formKey: 'phieu-vc-yte', version: '11' }],
    componentRegistryVersion: REGISTRY,
    policyPack: POLICY_13,
    elderMode: false,
    fewSteps: false,
    bundleSha256: null,
    bundleSizeGzipBytes: 10912,
    publishedAt: null,
    reviewComment: null,
    createdBy: { id: uuid(8901), name: 'Đỗ Thanh Tâm' },
    updatedAt: '2026-10-19T09:40:00+07:00',
    rowVersion: 1,
    ...extra,
  } as UiPackVersion;
}

function seedVersions(): UiPackVersion[] {
  return [
    version(5, '1.5.0', 'THAY_THE', {
      tokens: tokens('#127C8A', 8),
      forms: [{ formKey: 'phieu-vc-yte', version: '9' }],
      policyPack: POLICY_11_STOPPED,
      publishedAt: '2026-03-02T15:00:00+07:00',
      updatedAt: '2026-03-02T15:00:00+07:00',
    }),
    version(6, '1.6.0', 'THAY_THE', {
      tokens: tokens('#0B6E7A', 10),
      forms: [{ formKey: 'phieu-vc-yte', version: '10' }],
      publishedAt: '2026-06-15T16:30:00+07:00',
      updatedAt: '2026-06-15T16:30:00+07:00',
    }),
    version(7, '1.7.0', 'DA_PHAT_HANH', {
      publishedAt: '2026-09-01T10:00:00+07:00',
      updatedAt: '2026-09-01T10:00:00+07:00',
      bundleSha256: LOGO.sha256,
    }),
    version(8, '1.8.0', 'NHAP', {
      tokens: tokens('#0A7E8C', 14),
      layouts: home(
        'vt.widget.myTasksToday',
        'vt.widget.kpiTile',
        'vt.widget.kpiTile',
        'yte.widget.chuyenMonKpi',
      ),
      forms: [{ formKey: 'phieu-vc-yte', version: '12' }],
      elderMode: true,
      bundleSizeGzipBytes: 11469,
      rowVersion: 6,
    }),
  ];
}

// ---------------------------------------------------------------- biểu mẫu
const KHOA = [
  ['HSTC', 'Khoa Hồi sức tích cực'],
  ['NOI', 'Khoa Nội tổng hợp'],
  ['NGOAI', 'Khoa Ngoại'],
  ['CC', 'Khoa Cấp cứu'],
].map(([c, t]) => ({ const: c, title: t }));

function vcYte(v: string, status: UiFormVersion['status'], rowVersion: number): UiFormVersion {
  return {
    formKey: 'phieu-vc-yte',
    version: v,
    status,
    schemaJson: {
      $schema: 'https://json-schema.org/draft/2020-12/schema',
      type: 'object',
      required: ['khoa_phong', 'so_ca_truc', 'ty_le_hsba_dat'],
      properties: {
        khoa_phong: {
          type: 'string',
          title: 'Khoa, phòng',
          oneOf: KHOA,
          'x-kind': 'select',
          'x-classification': 'C1',
        },
        so_ca_truc: {
          type: 'number',
          title: 'Số ca trực trong quý',
          description: 'Tối thiểu 36 ca theo danh mục vị trí việc làm.',
          'x-kind': 'number',
          'x-cel': 'self >= 0.0',
          'x-classification': 'C1',
        },
        ty_le_hsba_dat: {
          type: 'number',
          title: 'Tỷ lệ hồ sơ bệnh án đạt chuẩn (%)',
          'x-kind': 'number',
          'x-cel': 'self >= 0.0 && self <= 100.0',
          'x-classification': 'C1',
          'x-scored': true,
        },
        su_co_y_khoa: {
          type: 'string',
          title: 'Sự cố y khoa phải báo cáo',
          oneOf: [
            { const: 'KHONG', title: 'Không' },
            { const: 'CO', title: 'Có' },
          ],
          'x-kind': 'select',
          'x-classification': 'C1',
        },
        mo_ta_su_co: {
          type: 'string',
          title: 'Mô tả sự cố và biện pháp khắc phục',
          maxLength: 2000,
          'x-kind': 'textarea',
          'x-cel': 'size(self) >= 20',
          'x-classification': 'C2',
        },
        minh_chung: {
          type: 'string',
          title: 'Minh chứng chuyên môn',
          'x-kind': 'file',
          'x-classification': 'C1',
        },
      },
    },
    uiJson: {
      // ui-text-ok: biểu thức hiển thị CEL lưu trong cấu hình, không hiện như văn bản giao diện
      mo_ta_su_co: {
        hideExpression: '!(form.su_co_y_khoa == "CO")',
        message: 'Mô tả ít nhất 20 ký tự',
      },
      so_ca_truc: { message: 'Số ca trực không được âm' },
      ty_le_hsba_dat: { message: 'Giá trị phải từ 0 đến 100' },
      'x-test-cases': [
        { caseId: 'TC-01', input: { khoa_phong: 'HSTC', so_ca_truc: 42, ty_le_hsba_dat: 104 } },
        { caseId: 'TC-02', input: { khoa_phong: 'HSTC', so_ca_truc: 42, ty_le_hsba_dat: 96.5 } },
        { caseId: 'TC-03', input: { khoa_phong: 'NOI', so_ca_truc: -1, ty_le_hsba_dat: 80 } },
        {
          caseId: 'TC-04',
          input: { khoa_phong: 'CC', so_ca_truc: 40, ty_le_hsba_dat: 90, su_co_y_khoa: 'CO' },
        },
        {
          caseId: 'TC-05',
          input: {
            khoa_phong: 'CC',
            so_ca_truc: 40,
            ty_le_hsba_dat: 90,
            su_co_y_khoa: 'CO',
            mo_ta_su_co: 'Nhầm thuốc ở ca đêm, đã bổ sung quy trình đối chiếu hai người.',
          },
        },
        { caseId: 'TC-06', input: { khoa_phong: 'NGOAI', ty_le_hsba_dat: 0 } },
      ],
    },
    celRules: [
      { field: 'so_ca_truc', expr: 'self >= 0.0', message: 'Số ca trực không được âm' },
      {
        field: 'ty_le_hsba_dat',
        expr: 'self >= 0.0 && self <= 100.0',
        message: 'Giá trị phải từ 0 đến 100',
      },
    ],
    policyPack: POLICY_13,
    lastTestRun: null,
    updatedAt: '2026-10-19T09:30:00+07:00',
    rowVersion,
  } as unknown as UiFormVersion;
}

interface State {
  versions: Ver[];
  /** Số lần đọc còn lại trước khi bước nền (kiểm tra, phát hành) xong, theo phiên bản. */
  pending: Map<string, { left: number; to: 'CHECK' | 'PUBLISH' }>;
  forms: Map<string, UiFormVersion[]>;
  /** Ca test mà máy chủ giả lập cố ý cho kết quả khác trình duyệt (kiểm chứng MSG-18-08). */
  parityBreak: Set<string>;
}

const fixtureForms = formsFixture.forms as unknown as Record<string, UiFormVersion>;

function seed(): State {
  return {
    versions: seedVersions(),
    pending: new Map(),
    forms: new Map<string, UiFormVersion[]>([
      ['phieu-vc-yte', [vcYte('11', 'DA_PHAT_HANH', 3), vcYte('12', 'NHAP', 9)]],
      ...Object.values(fixtureForms).map((f): [string, UiFormVersion[]] => [
        f.formKey,
        [structuredClone(f)],
      ]),
    ]),
    parityBreak: new Set(),
  };
}

let state = seed();

/** Cho máy chủ giả lập trả kết quả khác trình duyệt ở một ca test (dùng trong kiểm thử MSG-18-08). */
export function breakMockFormParity(caseId: string): void {
  state.parityBreak.add(caseId);
}

// ---------------------------------------------------------------- tiện ích kiểm tra
function rgb(hex: string): number[] | null {
  let h = String(hex).replace('#', '');
  if (h.length === 3) h = [...h].map((x) => x + x).join('');
  if (!/^[0-9a-f]{6}$/i.test(h)) return null;
  return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
}

function contrast(a: string, b: string): number {
  const lum = (hex: string) => {
    const c = rgb(hex);
    if (!c) return 0;
    const [r, g, bl] = c
      .map((v) => v / 255)
      .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [x, y] = [lum(a), lum(b)];
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

const fmt = (n: number, d = 1) => n.toLocaleString('vi-VN', { maximumFractionDigits: d });

function tokenValue(t: unknown, path: string): string {
  let node = t as Record<string, unknown> | undefined;
  for (const p of path.split('.')) node = node?.[p] as Record<string, unknown> | undefined;
  return String((node as { $value?: unknown } | undefined)?.$value ?? '');
}

function walk(node: UiSduiNode | undefined, fn: (n: UiSduiNode) => void) {
  if (!node) return;
  fn(node);
  node.children?.forEach((c) => walk(c, fn));
}

function sizeOf(v: Pick<UiPackVersion, 'tokens' | 'layouts'>): number {
  return 10240 + JSON.stringify({ t: v.tokens, l: v.layouts }).length;
}

/** Bộ kiểm tra tự động khi xem trước và khi gửi duyệt (BR-18-01, 02, 08, 09). */
function checks(v: UiPackVersion, mode: 'default' | 'elderly' = 'default'): UiPackCheck[] {
  const out: UiPackCheck[] = [];
  const pairs: [string, string, string][] = [
    ['color.onPrimary', 'color.primary', 'chữ trên nền màu chủ đạo'],
    ['color.text', 'color.background', 'chữ trên nền trang'],
    ['color.surface', 'color.danger', 'chữ trên nền màu cảnh báo'],
  ];
  const need = mode === 'elderly' ? 7 : 4.5;
  const bad = pairs
    .map(([fg, bg, label]) => ({
      fg: tokenValue(v.tokens, fg),
      bg: tokenValue(v.tokens, bg),
      label,
    }))
    .map((p) => ({ ...p, ratio: contrast(p.fg, p.bg) }))
    .filter((p) => (p.label === 'chữ trên nền trang' ? p.ratio < need : p.ratio < 4.5));
  out.push(
    bad.length
      ? {
          code: 'CONTRAST',
          passed: false,
          msg: 'MSG-18-01',
          message: `Màu chữ ${bad[0].fg} trên nền ${bad[0].bg} có tương phản ${fmt(bad[0].ratio)} : 1, chưa đạt mức AA (4,5 : 1).`,
        }
      : {
          code: 'CONTRAST',
          passed: true,
          message: `Tương phản ${fmt(contrast(tokenValue(v.tokens, 'color.onPrimary'), tokenValue(v.tokens, 'color.primary')))} : 1, đạt mức AA.`,
        },
  );
  const known = new Set(COMPONENTS.map((c) => c.componentKey));
  const unknown: string[] = [];
  let widgets = 0;
  walk(v.layouts['home'], (n) => {
    if (!known.has(n.component)) unknown.push(n.component);
    if (n.component !== 'vt.layout.grid') widgets++;
  });
  out.push(
    unknown.length
      ? {
          code: 'REGISTRY',
          passed: false,
          msg: 'MSG-18-02',
          message: `Thành phần ${unknown[0]} không có trong danh mục cho phép phiên bản ${REGISTRY}.`,
        }
      : widgets > MAX_HOME_WIDGETS
        ? {
            code: 'REGISTRY',
            passed: false,
            message: `Trang chủ có ${widgets} khối, vượt giới hạn ${MAX_HOME_WIDGETS} khối.`,
          }
        : {
            code: 'REGISTRY',
            passed: true,
            message: 'Mọi khối trang chủ đều có trong danh mục cho phép.',
          },
  );
  const kb = sizeOf(v) / 1024;
  out.push(
    kb > 30
      ? {
          code: 'SIZE',
          passed: false,
          msg: 'MSG-18-05',
          message: `Kích thước gói giao diện là ${fmt(kb)} KB, vượt ngân sách 30 KB.`,
        }
      : {
          code: 'SIZE',
          passed: true,
          message: `Gói ${fmt(kb)} KB, trong ngân sách 30 KB.`,
        },
  );
  if (v.elderMode) {
    const r = contrast(
      tokenValue(v.tokens, 'color.text'),
      tokenValue(v.tokens, 'color.background'),
    );
    out.push({
      code: 'ELDER_MODE',
      passed: r >= 7 || mode === 'default',
      message:
        r >= 7
          ? `Chế độ người cao tuổi: chữ 18 px, tương phản ${fmt(r)} : 1.`
          : `Chế độ người cao tuổi dùng màu chữ đậm hơn để đạt tương phản 7 : 1.`,
    });
  }
  return out;
}

function flatten(t: unknown, prefix = '--vt'): Record<string, string> {
  const res: Record<string, string> = {};
  for (const [k, node] of Object.entries((t ?? {}) as Record<string, unknown>)) {
    const name = `${prefix}-${k.replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase()}`;
    const n = node as { $value?: unknown; $type?: string };
    if (n && typeof n === 'object' && '$value' in n) {
      if (n.$type === 'asset') continue;
      res[name] = Array.isArray(n.$value) ? n.$value.join(', ') : String(n.$value);
    } else Object.assign(res, flatten(node, name));
  }
  return res;
}

// ---------------------------------------------------------------- UI Pack
function find(r: MockRequest): Ver | undefined {
  if (r.pathParams['id'] !== PACK_ID) return undefined;
  return state.versions.find((x) => x.version === r.pathParams['v']);
}

function ifMatch(r: MockRequest, rowVersion: number): MockResponse | null {
  const h = r.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu điều kiện phiên bản khi ghi');
  if (h.replace(/"/g, '') !== String(rowVersion)) {
    return problem(409, 'Nội dung đã được người khác cập nhật. Vui lòng tải lại.', {
      code: 'UI-409-VERSION-CONFLICT',
    } as never);
  }
  return null;
}

const notDraft = () =>
  problem(409, 'Phiên bản này không còn là bản nháp nên không sửa được.', {
    code: 'UI-409-NOT-DRAFT',
  } as never);

/** Bước nền giả lập: kiểm tra tự động, phát hành; xong sau hai lần đọc phiên bản. */
function settle(v: Ver) {
  const p = state.pending.get(v.version);
  if (!p || --p.left > 0) return;
  state.pending.delete(v.version);
  if (p.to === 'CHECK') {
    v.status = checks(v).every((c) => c.passed) ? 'CHO_DUYET' : 'NHAP';
  } else {
    for (const o of state.versions) if (o.status === 'DA_PHAT_HANH') o.status = 'THAY_THE';
    v.status = 'DA_PHAT_HANH';
    v.publishedAt = new Date().toISOString();
    v.bundleSha256 = LOGO.sha256;
  }
  v.rowVersion++;
}

function summary(): UiPackSummary {
  const cur = state.versions.find((v) => v.status === 'DA_PHAT_HANH');
  const draft = [...state.versions]
    .reverse()
    .find((v) => v.status === 'NHAP' || v.status === 'DANG_KIEM_TRA' || v.status === 'CHO_DUYET');
  return {
    id: PACK_ID,
    code: 'so-y-te-x',
    name: 'Giao diện Sở Y tế tỉnh X',
    currentVersionId: cur?.id ?? null,
    currentVersion: cur?.version ?? null,
    draftVersion: draft?.version ?? null,
  };
}

function saveVersion(r: MockRequest): MockResponse {
  const v = find(r);
  if (!v) return notFound();
  const blocked = ifMatch(r, v.rowVersion);
  if (blocked) return blocked;
  if (v.status !== 'NHAP') return notDraft();
  const body = r.body as UiPackVersionUpdate;
  const next = { ...v, tokens: body.tokens, layouts: body.layouts } as UiPackVersion;
  for (const c of checks(next)) {
    if (c.passed || c.code === 'SIZE' || c.code === 'ELDER_MODE') continue;
    return problem(422, 'Cấu hình giao diện chưa hợp lệ', {
      code: c.code === 'CONTRAST' ? 'UI-422-CONTRAST' : 'UI-422-UNKNOWN-COMPONENT',
      detail: c.message,
    } as never);
  }
  Object.assign(v, {
    tokens: body.tokens,
    layouts: body.layouts,
    assets: body.assets ?? v.assets,
    componentRegistryVersion: body.componentRegistryVersion,
    elderMode: body.elderMode,
    fewSteps: body.fewSteps,
    bundleSizeGzipBytes: sizeOf(next),
    reviewComment: null,
    updatedAt: new Date().toISOString(),
  });
  v.rowVersion++;
  return out(v, etag(v.rowVersion));
}

function createVersion(r: MockRequest): MockResponse {
  if (r.pathParams['id'] !== PACK_ID) return notFound();
  const body = r.body as UiPackVersionCreate;
  if (summary().draftVersion) {
    return problem(409, 'Đã có một phiên bản đang soạn hoặc chờ duyệt.', {
      code: 'UI-409-DRAFT-EXISTS',
    } as never);
  }
  if (state.versions.some((v) => v.version === body.version)) {
    return problem(409, 'Số phiên bản đã tồn tại.');
  }
  const from = state.versions.find((v) => v.version === body.cloneFromVersion);
  const base = from ?? state.versions[state.versions.length - 1];
  const v: UiPackVersion = {
    ...structuredClone(base),
    id: uuid(8100 + state.versions.length + 10),
    version: body.version,
    status: 'NHAP',
    bundleSha256: null,
    publishedAt: null,
    reviewComment: null,
    createdBy: { id: MOCK_ME.id, name: MOCK_ME.name },
    updatedAt: new Date().toISOString(),
    rowVersion: 1,
  };
  state.versions.push(v);
  return { status: 201, body: structuredClone(v), headers: etag(1) };
}

/** 202 + `Location` của job dùng chung; job xong thì bước nền của phiên bản cũng xong. */
function job(type: string, v: Ver): MockResponse {
  return startMockJob(type, () => {
    const p = state.pending.get(v.version);
    if (!p) return;
    p.left = 1;
    settle(v);
  });
}

function submit(r: MockRequest): MockResponse {
  const v = find(r);
  if (!v) return notFound();
  const blocked = ifMatch(r, v.rowVersion);
  if (blocked) return blocked;
  if (v.status !== 'NHAP') return notDraft();
  v.status = 'DANG_KIEM_TRA';
  v.rowVersion++;
  state.pending.set(v.version, { left: 2, to: 'CHECK' });
  return job('TEST_RUN', v);
}

function publish(r: MockRequest): MockResponse {
  const v = find(r);
  if (!v) return notFound();
  const blocked = ifMatch(r, v.rowVersion);
  if (blocked) return blocked;
  if (v.status !== 'CHO_DUYET') {
    return problem(409, 'Phiên bản không ở trạng thái chờ duyệt.', {
      code: 'UI-409-NOT-PENDING',
    } as never);
  }
  if (v.createdBy?.id === MOCK_ME.id) {
    return problem(403, 'Người duyệt phải khác người soạn phiên bản giao diện.', {
      code: 'UI-403-SAME-PERSON',
    } as never);
  }
  if (!mockSession.recentMfa()) {
    const res = problem(401, 'Cần xác thực nhiều lớp trước khi phát hành giao diện.', {
      type: 'https://datatracker.ietf.org/doc/html/rfc9470',
      code: 'insufficient_user_authentication',
    });
    res.headers = {
      ...res.headers,
      // ui-text-ok: tiêu đề HTTP theo RFC 9470, không hiện lên màn hình
      'WWW-Authenticate': 'Bearer error="insufficient_user_authentication", acr_values="aal2"',
    };
    return res;
  }
  state.pending.set(v.version, { left: 2, to: 'PUBLISH' });
  return job('RENDER', v);
}

function reject(r: MockRequest): MockResponse {
  const v = find(r);
  if (!v) return notFound();
  const blocked = ifMatch(r, v.rowVersion);
  if (blocked) return blocked;
  if (v.status !== 'CHO_DUYET') {
    return problem(409, 'Phiên bản không ở trạng thái chờ duyệt.', {
      code: 'UI-409-NOT-PENDING',
    } as never);
  }
  const comment = String((r.body as { comment?: string })?.comment ?? '').trim();
  if (!comment) return problem(422, 'Cần nhập ý kiến khi từ chối.');
  v.status = 'NHAP';
  v.reviewComment = comment;
  v.rowVersion++;
  return out(v, etag(v.rowVersion));
}

function activate(r: MockRequest): MockResponse {
  const v = find(r);
  if (!v) return notFound();
  const blocked = ifMatch(r, v.rowVersion);
  if (blocked) return blocked;
  if (v.status !== 'THAY_THE') {
    return problem(409, 'Chỉ quay lui được về phiên bản đã thay thế.');
  }
  if (v.policyPack?.packVersionId === POLICY_11_STOPPED.packVersionId) {
    return problem(409, 'Không quay lui được', {
      code: 'UI-409-ROLLBACK-INCOMPATIBLE',
      detail: `Không thể quay lui về bản ${v.version.replace(/\.0$/, '')} vì biểu mẫu của phiên bản này dùng bộ chính sách đánh giá đã ngừng áp dụng.`,
    } as never);
  }
  for (const o of state.versions) {
    if (o.status === 'DA_PHAT_HANH') {
      o.status = 'THAY_THE';
      o.rowVersion++;
    }
  }
  v.status = 'DA_PHAT_HANH';
  v.publishedAt = new Date().toISOString();
  v.rowVersion++;
  return out(v, etag(v.rowVersion));
}

function attachForm(r: MockRequest): MockResponse {
  const v = find(r);
  if (!v) return notFound();
  const blocked = ifMatch(r, v.rowVersion);
  if (blocked) return blocked;
  if (v.status !== 'NHAP') return notDraft();
  const body = r.body as UiPackFormAttach;
  const f = state.forms.get(body.formKey)?.find((x) => x.version === body.formVersion);
  if (!f) return notFound();
  const run = f.lastTestRun;
  if (!run || !run.parity || run.passed !== run.total) {
    return problem(422, 'Biểu mẫu chưa đạt bộ test nên chưa đưa vào giao diện được.', {
      code: 'UI-422-FORM-TEST-FAILED',
    } as never);
  }
  v.forms = [
    ...(v.forms ?? []).filter((x) => x.formKey !== body.formKey),
    { formKey: body.formKey, version: body.formVersion },
  ];
  v.rowVersion++;
  return out(v, etag(v.rowVersion));
}

// ---------------------------------------------------------------- biểu mẫu
function findForm(r: MockRequest): UiFormVersion | undefined {
  return state.forms.get(r.pathParams['key'])?.find((x) => x.version === r.pathParams['v']);
}

function celProblem(expr: string): string | null {
  let depth = 0;
  const code = expr.replace(/"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/g, 'S');
  if (/["']/.test(code)) return 'chuỗi ký tự chưa đóng';
  for (const ch of code) {
    if (ch === '(') depth++;
    if (ch === ')' && --depth < 0) return 'thừa dấu đóng ngoặc';
  }
  if (depth) return 'thiếu dấu đóng ngoặc';
  if (/(&&|\|\||==|!=|>=|<=|[<>+\-*/])\s*$/.test(code.trim())) return 'biểu thức chưa hoàn chỉnh';
  return null;
}

type Props = Record<string, Record<string, unknown>>;

function saveForm(r: MockRequest): MockResponse {
  const f = findForm(r);
  if (!f) return notFound();
  const blocked = ifMatch(r, f.rowVersion);
  if (blocked) return blocked;
  if (f.status !== 'NHAP') return notDraft();
  const body = r.body as UiFormVersionUpdate;
  const before = ((f.schemaJson as { properties?: Props }).properties ?? {}) as Props;
  const after = ((body.schemaJson as { properties?: Props }).properties ?? {}) as Props;
  for (const [k, p] of Object.entries(before)) {
    if (!p['x-scored']) continue;
    if (!after[k] || after[k]['type'] !== p['type']) {
      return problem(409, 'Không sửa được trường tính điểm', {
        code: 'UI-409-SCORED-FIELD',
        detail: `Trường ${String(p['title'] ?? k)} được bộ chính sách đánh giá ${f.policyPack?.packCode ?? ''} dùng để tính điểm nên không thể đổi kiểu hoặc xoá.`,
      } as never);
    }
  }
  const exprs: string[] = [
    ...Object.values(after).map((p) => String(p['x-cel'] ?? '')),
    ...Object.values((body.uiJson ?? {}) as Props).map((u) =>
      String((u as Record<string, unknown> | undefined)?.['hideExpression'] ?? ''),
    ),
  ].filter(Boolean);
  for (const e of exprs) {
    const err = celProblem(e);
    if (err) {
      return problem(422, 'Biểu thức kiểm tra chưa hợp lệ', {
        code: 'UI-422-CEL-INVALID',
        detail: `Biểu thức kiểm tra không hợp lệ: ${err}.`,
      } as never);
    }
  }
  Object.assign(f, {
    schemaJson: body.schemaJson,
    uiJson: body.uiJson,
    celRules: body.celRules ?? [],
    lastTestRun: null,
    updatedAt: new Date().toISOString(),
  });
  f.rowVersion++;
  return out(f, etag(f.rowVersion));
}

function createFormVersion(r: MockRequest): MockResponse {
  const key = r.pathParams['key'];
  const list = state.forms.get(key) ?? [];
  const body = r.body as UiFormVersionCreate;
  if (list.some((x) => x.status === 'NHAP')) {
    return problem(409, 'Biểu mẫu đã có một bản nháp.', { code: 'UI-409-DRAFT-EXISTS' } as never);
  }
  if (list.some((x) => x.version === body.version)) return problem(409, 'Số phiên bản đã tồn tại.');
  const from = list.find((x) => x.version === body.cloneFromVersion) ?? list[list.length - 1];
  const f: UiFormVersion = from
    ? {
        ...structuredClone(from),
        version: body.version,
        status: 'NHAP',
        lastTestRun: null,
        rowVersion: 1,
      }
    : {
        formKey: key,
        version: body.version,
        status: 'NHAP',
        schemaJson: { type: 'object', properties: {} },
        uiJson: {},
        rowVersion: 1,
      };
  list.push(f);
  state.forms.set(key, list);
  return { status: 201, body: structuredClone(f), headers: etag(1) };
}

function runFormTests(r: MockRequest): MockResponse {
  const f = findForm(r);
  if (!f) return notFound();
  const body = r.body as UiFormTestRunRequest;
  if (!body?.cases?.length) return problem(422, 'Bộ test chưa có ca nào.');
  const cases = body.cases.map((c) => {
    const serverValid = state.parityBreak.has(c.caseId) ? !c.browserValid : c.browserValid;
    return {
      caseId: c.caseId,
      serverValid,
      browserValid: c.browserValid,
      match: serverValid === c.browserValid,
      serverErrors: serverValid ? [] : (c.browserErrors ?? []),
    };
  });
  const passed = cases.filter((c) => c.match).length;
  const res: UiFormTestRunResult = {
    total: cases.length,
    passed,
    parity: passed === cases.length,
    renderMs50Fields: body.renderMs50Fields ?? null,
    cases,
    ranAt: new Date().toISOString(),
  };
  f.lastTestRun = res;
  return out(res);
}

export const UI_PACKS_MOCK: MockModule = {
  name: 'ui-packs',
  reset: () => {
    state = seed();
  },
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/ui/packs',
      handle: () => out({ items: [summary()], nextCursor: null }),
    },
    {
      method: 'GET',
      pattern: '/api/v1/ui/packs/:id/versions',
      handle: (r) => {
        if (r.pathParams['id'] !== PACK_ID) return notFound();
        for (const v of state.versions) settle(v);
        return out({ items: [...state.versions].reverse(), nextCursor: null });
      },
    },
    { method: 'POST', pattern: '/api/v1/ui/packs/:id/versions', handle: createVersion },
    {
      method: 'GET',
      pattern: '/api/v1/ui/packs/:id/versions/:v',
      handle: (r) => {
        const v = find(r);
        if (!v) return notFound();
        settle(v);
        return out(v, etag(v.rowVersion));
      },
    },
    { method: 'PUT', pattern: '/api/v1/ui/packs/:id/versions/:v', handle: saveVersion },
    {
      method: 'GET',
      pattern: '/api/v1/ui/packs/:id/versions/:v/preview',
      handle: (r) => {
        const v = find(r);
        if (!v) return notFound();
        const mode = r.params.get('mode') === 'elderly' ? 'elderly' : 'default';
        return out({
          version: v.version,
          mode,
          cssVariables: flatten(v.tokens),
          layouts: v.layouts,
          checks: checks(v, mode),
          bundleSizeGzipBytes: sizeOf(v),
        });
      },
    },
    { method: 'POST', pattern: '/api/v1/ui/packs/:id/versions/:v/submit', handle: submit },
    { method: 'POST', pattern: '/api/v1/ui/packs/:id/versions/:v/publish', handle: publish },
    { method: 'POST', pattern: '/api/v1/ui/packs/:id/versions/:v/reject', handle: reject },
    { method: 'POST', pattern: '/api/v1/ui/packs/:id/versions/:v/activate', handle: activate },
    { method: 'POST', pattern: '/api/v1/ui/packs/:id/versions/:v/forms', handle: attachForm },
    {
      method: 'GET',
      pattern: '/api/v1/ui/components',
      handle: (r) => {
        const reg = r.params.get('registry');
        const items = COMPONENTS.filter((c) => !reg || c.registryVersion === reg);
        return out({ items, nextCursor: null });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/ui/forms',
      handle: () =>
        out({
          items: [...state.forms.values()].map((list) => {
            const f = list[list.length - 1];
            return {
              formKey: f.formKey,
              latestVersion: f.version,
              status: f.status,
              policyPack: f.policyPack,
              updatedAt: f.updatedAt,
            };
          }),
          nextCursor: null,
        }),
    },
    { method: 'POST', pattern: '/api/v1/ui/forms/:key/versions', handle: createFormVersion },
    {
      method: 'GET',
      pattern: '/api/v1/ui/forms/:key/versions/:v',
      handle: (r) => {
        const f = findForm(r);
        return f ? out(f, etag(f.rowVersion)) : notFound();
      },
    },
    { method: 'PUT', pattern: '/api/v1/ui/forms/:key/versions/:v', handle: saveForm },
    {
      method: 'POST',
      pattern: '/api/v1/ui/forms/:key/versions/:v/test-runs',
      handle: runFormTests,
    },
  ],
};
