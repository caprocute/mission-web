import { Job } from '../../lib/models/job';
import {
  DiscrepancyResolution,
  DiscrepancySeverity,
  FIELD_PERSON_MATCH,
  OrgDiscrepancy,
  OrgSyncRun,
  OrgSyncSchedule,
  SYNC_SOURCE_LABEL,
  SyncSource,
} from '../../lib/models/org-sync';
import { MAX_PAGE_LIMIT } from '../../lib/paging';
import {
  MockModule,
  MockRequest,
  MockResponse,
  ok,
  problem,
  resolveRelativeDates,
} from '../mock-router';
import { ORG_PERSONS } from '../org/org.mock';

/**
 * Giả lập đồng bộ và đối soát nhân sự SCR-0104 của Tỉnh Thái Nguyên (TL-02a Hình 6.10) theo
 * `org.yaml`: nhật ký có lần CBCCVC lỗi hết thời gian còn thông điệp trong `integ.dead_letter`,
 * 412 mục lệch. Dữ liệu hư cấu, giá trị C2 đã che. Đồng bộ ngay và chạy lại trả 202 + `/jobs/{id}`.
 * Trường chờ hợp đồng (`reason`, `matchedBy`, `effectiveFrom`, `countsByField`,
 * `assignmentsUpdated`) cũng được trả để màn hình đủ nội dung.
 */

/** Bản sao như khi qua mạng: trang không thấy dữ liệu đổi ngầm. */
const out = (body: unknown, headers?: Record<string, string>) => ok(structuredClone(body), headers);

const fieldErr = (title: string, field: string) =>
  problem(422, title, { errors: [{ field, message: title }] } as never);

let runs: OrgSyncRun[] = [];
let items: OrgDiscrepancy[] = [];
let schedules: OrgSyncSchedule[] = [];
let scheduleVersion = 0;
let jobs = new Map<string, { job: Job; polls: number; done: () => string }>();
let seq = 0;

const uuid = (prefix: string, n: number) => `${prefix}-7000-8000-${String(n).padStart(12, '0')}`;
const RUN = (n: number) => uuid('01928f3e-5a00', n);

function seedRuns(): OrgSyncRun[] {
  return resolveRelativeDates([
    {
      id: RUN(8844),
      connector: 'SCIM',
      startedAt: '@todayT07:45:00',
      finishedAt: '@todayT07:52:00',
      status: 'THANH_CONG_MOT_PHAN',
      records: 1240,
      changes: 18,
      errors: 3,
      deadLetters: 0,
      reason: '3 bản ghi sai cấu trúc ở thuộc tính nhóm',
    },
    {
      id: RUN(8843),
      connector: 'DANG_VIEN',
      startedAt: '@todayT02:30:00',
      finishedAt: '@todayT02:41:00',
      status: 'THANH_CONG',
      records: 48966,
      changes: 12,
      errors: 0,
      deadLetters: 0,
    },
    {
      id: RUN(8842),
      connector: 'CBCCVC',
      startedAt: '@todayT02:00:00',
      finishedAt: '@todayT02:24:00',
      status: 'THANH_CONG',
      records: 61204,
      changes: 37,
      errors: 0,
      deadLetters: 0,
    },
    {
      id: RUN(8838),
      connector: 'CBCCVC',
      startedAt: '@today-1T02:00:00',
      finishedAt: '@today-1T02:02:00',
      status: 'LOI',
      records: 0,
      changes: 0,
      errors: 1,
      deadLetters: 1,
      reason: 'NDXP không phản hồi sau 120 giây',
    },
    {
      id: RUN(8835),
      connector: 'DANG_VIEN',
      startedAt: '@today-1T02:30:00',
      finishedAt: '@today-1T02:40:00',
      status: 'THANH_CONG',
      records: 48960,
      changes: 4,
      errors: 0,
      deadLetters: 0,
    },
  ] as OrgSyncRun[]);
}

const HO = [
  'Nguyễn',
  'Trần',
  'Lê',
  'Phạm',
  'Hoàng',
  'Vũ',
  'Đặng',
  'Bùi',
  'Đỗ',
  'Ngô',
  'Dương',
  'Lý',
];
const DEM = ['Văn', 'Thị', 'Minh', 'Quang', 'Thu', 'Đức', 'Thanh', 'Hồng', 'Ngọc', 'Hữu'];
const TEN = [
  'An',
  'Bình',
  'Châu',
  'Dũng',
  'Giang',
  'Hà',
  'Hải',
  'Hoa',
  'Khánh',
  'Linh',
  'Nam',
  'Phúc',
];
const UNITS = [
  'Sở Nội vụ',
  'Sở Tài chính',
  'Sở Y tế',
  'Sở Xây dựng',
  'UBND xã Phú Từ',
  'UBND phường Đại Cương',
];
const TITLES = ['Chuyên viên', 'Chuyên viên chính', 'Phó Trưởng phòng', 'Trưởng phòng', 'Cán sự'];
const MATCH = 'Số định danh cá nhân và ngày sinh';

/**
 * Phân bố như Hình 6.10 (chức vụ 168, đơn vị 121, không khớp người 74) cộng đảng tịch 47 và hai
 * dòng kiêm nhiệm, trạng thái: tổng 412 mục.
 */
const PLAN: [OrgDiscrepancy['field'], number][] = [
  ['job_title_id', 167],
  ['org_unit_id', 120],
  [FIELD_PERSON_MATCH, 73],
  ['party_membership', 47],
];

function base(
  i: number,
  field: OrgDiscrepancy['field'],
  severity: DiscrepancySeverity,
): OrgDiscrepancy {
  const run = field === 'party_membership' ? runs[1] : runs[2];
  return {
    id: uuid('01928f3e-d100', i),
    personId: uuid('01928c1e-7a3f', i),
    personName: `${HO[i % HO.length]} ${DEM[(i * 3) % DEM.length]} ${TEN[(i * 7) % TEN.length]}`,
    maskedNationalId: `***${String(1000 + ((i * 7919) % 9000)).slice(-4)}`,
    field,
    sourceConnector: run.connector,
    sourceValueMasked: '',
    currentValueMasked: '',
    severity,
    suggestedAction: 'LAY_THEO_NGUON',
    externalId: `${run.connector}: 000.20.21.H56-${String(i).padStart(5, '0')}`,
    syncRunId: run.id,
    matchedBy: MATCH,
  };
}

function generate(): OrgDiscrepancy[] {
  const acc: OrgDiscrepancy[] = [];
  let i = 10;
  for (const [field, n] of PLAN) {
    for (let k = 0; k < n; k++, i++) {
      const sev: DiscrepancySeverity = k % 11 === 0 ? 'CAO' : k % 3 === 0 ? 'VUA' : 'THAP';
      const d = base(i, field, sev);
      const unit = UNITS[i % UNITS.length];
      if (field === 'job_title_id') {
        d.sourceValueMasked = `${TITLES[(i + 1) % TITLES.length]} ${unit}`;
        d.currentValueMasked = `${TITLES[i % TITLES.length]} ${unit}`;
        d.effectiveFrom = resolveRelativeDates(`@today-${(i % 40) + 1}`);
      } else if (field === 'org_unit_id') {
        d.sourceValueMasked = UNITS[(i + 2) % UNITS.length];
        d.currentValueMasked = unit;
        d.effectiveFrom = resolveRelativeDates(`@today-${(i % 30) + 1}`);
      } else if (field === 'party_membership') {
        d.sourceValueMasked = `Chuyển sinh hoạt đến Chi bộ ${unit}`;
        d.currentValueMasked = `Chi bộ ${UNITS[(i + 3) % UNITS.length]}`;
      } else {
        d.personId = null;
        d.sourceValueMasked = `Hồ sơ mới, sinh ${1965 + (i % 30)}`;
        d.currentValueMasked = 'Chưa có hồ sơ nội bộ khớp';
        d.matchedBy = 'Không khớp: chỉ trùng họ tên';
        d.suggestedAction = 'DOI_SOAT_THU_CONG';
      }
      acc.push(d);
    }
  }
  return acc;
}

function seedItems(): OrgDiscrepancy[] {
  const special: OrgDiscrepancy[] = [
    {
      ...base(1, 'job_title_id', 'CAO'),
      personName: 'Nguyễn Thị Lan',
      maskedNationalId: '***4521',
      sourceValueMasked: 'Phó Giám đốc Sở Nội vụ (QĐ 125/QĐ-UBND, 12/10/2026)',
      currentValueMasked: 'Trưởng phòng Công chức',
      externalId: 'CBCCVC: 000.20.21.H56-00412',
      effectiveFrom: '2026-10-12',
    },
    {
      ...base(2, 'org_unit_id', 'VUA'),
      personName: 'Vũ Văn Toàn',
      sourceValueMasked: 'Sở Tài chính từ 01/11/2026',
      currentValueMasked: 'Sở Nội vụ',
      suggestedAction: 'TAO_DIEU_DONG_TUONG_LAI',
      effectiveFrom: '2026-11-01',
    },
    {
      ...base(3, FIELD_PERSON_MATCH, 'CAO'),
      personId: null,
      personName: 'Trần Văn Khải',
      sourceConnector: 'DANG_VIEN',
      sourceValueMasked: 'Số thẻ 81.012.3456 · sinh 1971',
      currentValueMasked: '2 hồ sơ trùng tên, khác ngày sinh',
      suggestedAction: 'DOI_SOAT_THU_CONG',
      matchedBy: 'Không khớp: trùng họ tên, khác ngày sinh',
    },
    {
      ...base(4, 'concurrent_assignment', 'THAP'),
      personName: 'Lương Thị Hoa',
      sourceConnector: 'SCIM',
      sourceValueMasked: 'Thiếu nhóm "Chi uỷ viên"',
      currentValueMasked: 'Chi uỷ viên Chi bộ 2',
      suggestedAction: 'GIU_NOI_BO',
    },
    {
      ...base(5, 'employment_status', 'VUA'),
      personName: 'Phạm Quốc Bảo',
      sourceValueMasked: 'Nghỉ hưu từ 01/10/2026',
      currentValueMasked: 'Đang công tác',
      suggestedAction: 'KET_THUC_PHAN_CONG',
      effectiveFrom: '2026-10-01',
    },
  ];
  return [...special, ...generate()];
}

function reset() {
  runs = seedRuns();
  items = seedItems();
  schedules = [
    { connector: 'CBCCVC', enabled: true, cron: '0 2 * * *' },
    { connector: 'DANG_VIEN', enabled: true, cron: '30 2 * * *' },
    { connector: 'SCIM', enabled: true, cron: '45 7 * * *' },
  ];
  scheduleVersion = 0;
  jobs = new Map();
  seq = 0;
}
reset();

const open = () => items.filter((d) => !d.resolution);
const etag = () => ({ ETag: `"${scheduleVersion}"` });

function listRuns(r: MockRequest): MockResponse {
  const c = r.params.get('connector');
  const rows = c ? runs.filter((x) => x.connector === c) : runs;
  const limit = Math.min(Number(r.params.get('limit') ?? 20), MAX_PAGE_LIMIT);
  const start = Number(r.params.get('cursor') ?? 0);
  return out({
    items: rows.slice(start, start + limit),
    nextCursor: start + limit < rows.length ? String(start + limit) : null,
  });
}

function list(r: MockRequest): MockResponse {
  const field = r.params.get('field');
  const severity = r.params.get('severity');
  const o = open();
  const bySev = severity ? o.filter((d) => d.severity === severity) : o;
  const rows = field ? bySev.filter((d) => d.field === field) : bySev;
  const limit = Math.min(Number(r.params.get('limit') ?? 20), MAX_PAGE_LIMIT);
  const start = Number(r.params.get('cursor') ?? 0);
  const counts = { CAO: 0, VUA: 0, THAP: 0 };
  for (const d of field ? o.filter((x) => x.field === field) : o) counts[d.severity]++;
  const countsByField: Record<string, number> = {};
  for (const d of bySev) countsByField[d.field] = (countsByField[d.field] ?? 0) + 1;
  return out({
    items: rows.slice(start, start + limit),
    nextCursor: start + limit < rows.length ? String(start + limit) : null,
    counts,
    countsByField,
  });
}

const UPDATES_ASSIGNMENT = new Set([
  'job_title_id',
  'org_unit_id',
  'concurrent_assignment',
  'employment_status',
]);
const MSG_01_15 = 'Hai hồ sơ trùng tên khác ngày sinh. Vui lòng đối soát thủ công.';

/** Kết quả từng mục (BatchItemResult); mục lỗi không làm hỏng mục khác. */
function resolve(r: MockRequest): MockResponse {
  const body = r.body as { ids?: string[]; resolution?: DiscrepancyResolution; note?: string };
  const ids = body.ids ?? [];
  if (!ids.length) return fieldErr('Chọn ít nhất một dòng lệch.', 'ids');
  if (ids.length > 200) return fieldErr('Mỗi lần xử lý tối đa 200 dòng.', 'ids');
  if (body.resolution !== 'NHAN_NGUON' && body.resolution !== 'GIU_HIEN_TAI') {
    return fieldErr('Chỉ lấy theo nguồn hoặc giữ dữ liệu nội bộ.', 'resolution');
  }
  let updated = 0;
  const results = ids.map((id) => {
    const d = items.find((x) => x.id === id);
    if (!d)
      return { id, status: 404, problem: { title: 'Không tìm thấy dòng lệch.', status: 404 } };
    if (d.resolution) {
      return { id, status: 409, problem: { title: 'Dòng đã được xử lý.', status: 409 } };
    }
    if (d.field === FIELD_PERSON_MATCH && body.resolution === 'NHAN_NGUON') {
      return { id, status: 422, problem: { title: MSG_01_15, status: 422 } };
    }
    d.resolution = body.resolution;
    d.resolvedByName = 'Hoàng Đức Dũng';
    d.resolvedAt = new Date().toISOString();
    if (body.resolution === 'NHAN_NGUON' && UPDATES_ASSIGNMENT.has(d.field)) updated++;
    return { id, status: 200 };
  });
  return out({ results, assignmentsUpdated: updated });
}

function mapPerson(r: MockRequest): MockResponse {
  const d = items.find((x) => x.id === r.pathParams['id']);
  if (!d) return problem(404, 'Không tìm thấy dòng lệch.');
  if (d.resolution) return problem(409, 'Dòng đã được xử lý.');
  if (d.field !== FIELD_PERSON_MATCH)
    return problem(409, 'Chỉ ánh xạ thủ công dòng không khớp người.');
  const id = (r.body as { personId?: string }).personId;
  if (!ORG_PERSONS.some((p) => p.id === id)) return fieldErr('Chọn hồ sơ cán bộ.', 'personId');
  if (items.some((x) => x.personId === id && x.field === FIELD_PERSON_MATCH)) {
    return problem(409, 'Mã tại nguồn đã được ánh xạ tới người khác.', {
      code: 'ORG-409-XREF-EXISTS',
    });
  }
  Object.assign(d, {
    personId: id,
    resolution: 'SUA_TAY',
    resolvedByName: 'Hoàng Đức Dũng',
    resolvedAt: new Date().toISOString(),
  });
  return out(d);
}

function assign(r: MockRequest): MockResponse {
  const d = items.find((x) => x.id === r.pathParams['id']);
  if (!d) return problem(404, 'Không tìm thấy dòng lệch.');
  const body = r.body as { assigneeId?: string };
  const who = ORG_PERSONS.find((p) => p.id === body.assigneeId);
  if (!who) return fieldErr('Chọn cán bộ nhận kiểm tra.', 'assigneeId');
  d.assigneeName = who.name;
  return out(d);
}

function startJob(type: string, done: () => string): MockResponse {
  const id = crypto.randomUUID();
  const job: Job = { id, type, status: 'CHO', progress: 0 };
  jobs.set(id, { job, polls: 0, done });
  return { status: 202, body: job, headers: { Location: `/api/v1/jobs/${id}` } };
}

function addRun(connector: SyncSource, over: Partial<OrgSyncRun> = {}): OrgSyncRun {
  const prev = runs.find((x) => x.connector === connector && x.status !== 'LOI');
  const now = new Date().toISOString();
  const run: OrgSyncRun = {
    id: RUN(9000 + ++seq),
    connector,
    startedAt: now,
    finishedAt: now,
    status: 'THANH_CONG',
    records: prev?.records ?? 0,
    changes: 0,
    errors: 0,
    deadLetters: 0,
    ...over,
  };
  runs.unshift(run);
  return run;
}

function syncNow(r: MockRequest): MockResponse {
  const one = (r.body as { connector?: SyncSource } | null)?.connector;
  if (jobs.size && [...jobs.values()].some((e) => e.job.status !== 'HOAN_TAT')) {
    return problem(409, 'Đang có lần đồng bộ chạy. Vui lòng chờ lần đó kết thúc.', {
      code: 'ORG-409-SYNC-RUNNING',
    });
  }
  return startJob('ORG_SYNC', () => {
    if (!one || one === 'CBCCVC') addRun('CBCCVC', { changes: 2 });
    if (!one || one === 'DANG_VIEN') addRun('DANG_VIEN', { changes: 1 });
    if (!one || one === 'SCIM') {
      addRun('SCIM', {
        status: 'THANH_CONG_MOT_PHAN',
        errors: 3,
        reason: '3 bản ghi sai cấu trúc ở thuộc tính nhóm',
      });
    }
    return one
      ? `Đã đồng bộ nguồn ${SYNC_SOURCE_LABEL[one]}.`
      : 'Đã đồng bộ ba nguồn: CBCCVC 2 thay đổi, Đảng viên 1 thay đổi, tài khoản đăng nhập 3 bản ghi sai cấu trúc.';
  });
}

function retry(r: MockRequest): MockResponse {
  const run = runs.find((x) => x.id === r.pathParams['id']);
  if (!run) return problem(404, 'Không tìm thấy lần đồng bộ.');
  if (!run.deadLetters) return problem(409, 'Lần đồng bộ này không có dữ liệu lỗi chờ chạy lại.');
  return startJob('ORG_SYNC_RETRY', () => {
    run.deadLetters = 0;
    const n = addRun(run.connector, { changes: 5 });
    return `Đã chạy lại dữ liệu lỗi của lần đồng bộ ${SYNC_SOURCE_LABEL[run.connector]} lúc ${run.startedAt.slice(11, 16)}: ${n.records.toLocaleString('vi-VN')} bản ghi, ${n.changes} thay đổi.`;
  });
}

function job(r: MockRequest): MockResponse {
  const e = jobs.get(r.pathParams['id']);
  if (!e) return problem(404, 'Không tìm thấy yêu cầu xử lý.');
  e.polls++;
  if (e.polls >= 2 && e.job.status !== 'HOAN_TAT') {
    e.job = {
      id: e.job.id,
      type: e.job.type,
      status: 'HOAN_TAT',
      progress: 100,
      message: e.done(),
    };
  } else if (e.job.status !== 'HOAN_TAT') {
    e.job = {
      ...e.job,
      status: 'DANG_CHAY',
      progress: 50,
      message: 'Đang lấy dữ liệu từ các nguồn…',
    };
  }
  return out(e.job);
}

function saveSchedules(r: MockRequest): MockResponse {
  const m = r.headers.get('If-Match');
  if (!m) return problem(428, 'Thiếu thông tin phiên bản của lịch. Vui lòng tải lại trang.');
  if (m !== `"${scheduleVersion}"`) {
    return problem(409, 'Lịch đã được người khác thay đổi. Vui lòng tải lại.', {
      code: 'ORG-409-CONFLICT',
    });
  }
  const next = (r.body as { items?: OrgSyncSchedule[] }).items ?? [];
  if (next.some((s) => !/^\d{1,2} \d{1,2} \* \* \*$|^[\d*/,\- ]+$/.test(s.cron ?? ''))) {
    return fieldErr('Lịch đồng bộ không hợp lệ.', 'items');
  }
  schedules = next.map((s) => ({ connector: s.connector, enabled: !!s.enabled, cron: s.cron }));
  scheduleVersion++;
  return out({ items: schedules }, etag());
}

export const ORG_SYNC_MOCK: MockModule = {
  name: 'org/sync (đồng bộ và đối soát nhân sự)',
  reset,
  routes: [
    { method: 'GET', pattern: '/api/v1/org/sync-runs', handle: listRuns },
    { method: 'POST', pattern: '/api/v1/org/sync-runs', handle: syncNow },
    { method: 'POST', pattern: '/api/v1/org/sync-runs/:id/retry', handle: retry },
    // Chỉ biết tác vụ của module này; module khác dùng `/jobs/{id}` thì cần bộ tác vụ chung.
    { method: 'GET', pattern: '/api/v1/jobs/:id', handle: job },
    {
      method: 'GET',
      pattern: '/api/v1/org/sync-schedules',
      handle: () => out({ items: schedules }, etag()),
    },
    { method: 'PUT', pattern: '/api/v1/org/sync-schedules', handle: saveSchedules },
    { method: 'GET', pattern: '/api/v1/org/discrepancies', handle: list },
    { method: 'POST', pattern: '/api/v1/org/discrepancies/resolve', handle: resolve },
    { method: 'POST', pattern: '/api/v1/org/discrepancies/:id/map', handle: mapPerson },
    { method: 'POST', pattern: '/api/v1/org/discrepancies/:id/assign', handle: assign },
  ],
};
