import { MAX_PAGE_LIMIT } from '../../lib/paging';
import {
  CatalogImportJob,
  CatalogItem,
  CatalogItemInput,
  WorkCatalog,
} from '../../lib/models/catalog';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import fixture from './catalog.json';

/**
 * Giả lập API `/api/v1/catalog` của MOD-02 (QT-04): phiên bản danh mục, mục theo Bảng 3, thêm, sửa
 * (BR-04-13: mục đã dùng ở kỳ khoá sang phiên bản mới, MSG-04-01), nhân bản phiên bản, nhập Excel (202,
 * MSG-04-02). Danh sách mục áp dụng cho người dùng (`/catalog/items`) phục vụ SCR-0503, SCR-0501.
 */

type StoredItem = CatalogItem & { catalogId: string; newInV3?: boolean };
type StoredCatalog = Omit<WorkCatalog, 'itemCount' | 'positionCount'>;

let catalogs: StoredCatalog[] | null = null;
let items: StoredItem[] | null = null;
let jobs = new Map<string, { job: CatalogImportJob; polls: number }>();
let latest = new Map<string, string>();

function load(): void {
  if (catalogs && items) return;
  catalogs = fixture.catalogs.map((c) => ({
    ...(c as Omit<StoredCatalog, 'rowVersion' | 'difficultyFactors' | 'scoreSuggestion'>),
    rowVersion: 0,
    difficultyFactors: fixture.difficultyFactors,
    scoreSuggestion: fixture.scoreSuggestion,
  }));
  const v3 = (fixture.items as StoredItem[]).map((i) => ({
    ...i,
    catalogId: 'cat-cvdv-v3',
    rowVersion: 0,
  }));
  // phiên bản 2 có cùng mục trừ mục mới thêm ở phiên bản 3; mọi mục v2 đều thuộc kỳ đã khoá
  const v2 = v3
    .filter((i) => !i.newInV3)
    .map((i) => ({
      ...i,
      id: `${i.id}-v2`,
      catalogId: 'cat-cvdv-v2',
      usedInLockedPeriod: true,
      status: 'AP_DUNG' as const,
    }));
  items = [...v3, ...v2];
  jobs = new Map([
    [fixture.latestImport.id, { job: fixture.latestImport as CatalogImportJob, polls: 1 }],
  ]);
  latest = new Map([['cat-cvdv-v3', fixture.latestImport.id]]);
}

const positionName = (id?: string) => fixture.positions.find((p) => p.id === id)?.name;
const itemsOf = (catalogId: string) => items!.filter((i) => i.catalogId === catalogId);
const findCatalog = (id: string) => catalogs!.find((c) => c.id === id);

function view(c: StoredCatalog): WorkCatalog {
  const list = itemsOf(c.id);
  return {
    ...c,
    itemCount: list.length,
    positionCount: new Set(list.map((i) => i.positionId)).size,
  };
}

function out(i: StoredItem): CatalogItem {
  const { newInV3: _n, ...rest } = i;
  void _n;
  return { ...rest, positionName: positionName(i.positionId) };
}

/** Danh mục đang áp dụng của người dùng (một phiên bản áp dụng mỗi kỳ). */
const applied = () => catalogs!.find((c) => c.status === 'AP_DUNG')!;

function listCatalogs(): MockResponse {
  load();
  const order = { NHAP: 0, AP_DUNG: 1, HET_HIEU_LUC: 2 } as const;
  const list = [...catalogs!].sort(
    (a, b) => order[a.status] - order[b.status] || b.versionNo - a.versionNo,
  );
  return ok({ items: list.map(view), nextCursor: null });
}

function listItems(r: MockRequest): MockResponse {
  load();
  const c = findCatalog(r.pathParams['id']);
  if (!c) return problem(404, 'Không tìm thấy danh mục');
  const q = r.params.get('q')?.toLowerCase();
  const pos = r.params.get('position');
  const diff = Number(r.params.get('difficulty')) || 0;
  const status = r.params.get('status');
  const limit = Math.min(Number(r.params.get('limit') ?? 20) || 20, MAX_PAGE_LIMIT);
  const offset = Number(r.params.get('cursor') ?? 0) || 0;
  const filtered = itemsOf(c.id).filter(
    (i) =>
      (!q || [i.code, i.name, i.productName].some((s) => s.toLowerCase().includes(q))) &&
      (!pos || i.positionId === pos) &&
      (!diff || i.difficulty === diff) &&
      (!status || i.status === status),
  );
  const page = filtered.slice(offset, offset + limit);
  return ok({
    items: page.map(out),
    nextCursor: offset + limit < filtered.length ? String(offset + limit) : null,
    total: filtered.length,
    positionCount: new Set(filtered.map((i) => i.positionId)).size,
  });
}

/** Kiểm tra theo SCR-0401 trường 3–14 và BR-04-14; trả 422 với lỗi theo trường. */
function validate(
  c: StoredCatalog,
  b: Partial<CatalogItemInput>,
  selfId?: string,
): MockResponse | null {
  const errors: { field: string; message: string }[] = [];
  const req = (v: unknown, field: string, label: string, max: number) => {
    if (typeof v !== 'string' || !v.trim())
      errors.push({ field, message: `${label} không được để trống` });
    else if (v.length > max) errors.push({ field, message: `${label} tối đa ${max} ký tự` });
  };
  if (!fixture.positions.some((p) => p.id === b.positionId))
    errors.push({ field: 'positionId', message: 'Chọn vị trí việc làm' });
  req(b.name, 'name', 'Tên công việc', 255);
  req(b.productName, 'productName', 'Sản phẩm', 255);
  req(b.unit, 'unit', 'Đơn vị tính', 30);
  req(b.qualityCriteria, 'qualityCriteria', 'Tiêu chuẩn chất lượng', 1000);
  req(b.evidenceRequired, 'evidenceRequired', 'Minh chứng yêu cầu', 500);
  const vague = fixture.vagueWords.find((w) => b.name?.toLowerCase().includes(w));
  if (vague)
    errors.push({
      field: 'name',
      message: `Tên công việc chứa từ mơ hồ "${vague}". Vui lòng ghi rõ sản phẩm, kết quả.`,
    });
  if (!Number.isInteger(b.difficulty) || b.difficulty! < 1 || b.difficulty! > 5)
    errors.push({ field: 'difficulty', message: 'Mức khó từ 1 đến 5' });
  if (typeof b.baseScore !== 'number' || b.baseScore < 0 || b.baseScore > 100)
    errors.push({ field: 'baseScore', message: 'Điểm chuẩn từ 0 đến 100' });
  if (b.standardDays != null && !(b.standardDays > 0))
    errors.push({ field: 'standardDays', message: 'Thời hạn chuẩn phải lớn hơn 0' });
  if (b.code) {
    if (!/^TC-[A-Z]{2}-\d{2,3}$/.test(b.code) || b.code.length > 20)
      errors.push({ field: 'code', message: 'Mã dạng TC-xx-nn, tối đa 20 ký tự' });
    else if (itemsOf(c.id).some((i) => i.code === b.code && i.id !== selfId))
      errors.push({ field: 'code', message: `Mã ${b.code} đã có trong danh mục` });
  }
  return errors.length ? problem(422, 'Dữ liệu không hợp lệ', { errors }) : null;
}

/** Phiên bản nháp kế tiếp của danh mục; tạo mới bằng cách nhân bản nếu chưa có. */
function draftOf(c: StoredCatalog): StoredCatalog {
  if (c.status === 'NHAP') return c;
  const existing = catalogs!.find((x) => x.name === c.name && x.status === 'NHAP');
  return existing ?? clone(c);
}

function clone(c: StoredCatalog): StoredCatalog {
  const maxNo = Math.max(...catalogs!.filter((x) => x.name === c.name).map((x) => x.versionNo));
  const n: StoredCatalog = {
    ...c,
    id: `cat-${crypto.randomUUID().slice(0, 8)}`,
    versionNo: maxNo + 1,
    status: 'NHAP',
    effectiveFromLabel: undefined,
    effectiveFromDate: undefined,
    rowVersion: 0,
  };
  catalogs!.push(n);
  // mục chép sang phiên bản mới giữ mã; số lần dùng tính theo phiên bản nên về 0
  items!.push(
    ...itemsOf(c.id).map((i) => ({
      ...i,
      id: `${i.code.toLowerCase()}-${n.id}`,
      catalogId: n.id,
      usageCount: 0,
      usagePeriods: 0,
      usedInLockedPeriod: false,
      rowVersion: 0,
    })),
  );
  return n;
}

function nextCode(c: StoredCatalog, positionId: string): string {
  const key =
    Object.entries({ 'vt-cvdv': 'DV', 'vt-cvtc': 'TC', 'vt-cvcb': 'CB' }).find(
      ([k]) => k === positionId,
    )?.[1] ?? 'KH';
  const nums = itemsOf(c.id)
    .filter((i) => i.code.startsWith(`TC-${key}-`))
    .map((i) => Number(i.code.split('-')[2]));
  return `TC-${key}-${String(Math.max(0, ...nums) + 1).padStart(2, '0')}`;
}

function addItem(r: MockRequest): MockResponse {
  load();
  const c = findCatalog(r.pathParams['id']);
  if (!c) return problem(404, 'Không tìm thấy danh mục');
  if (c.status === 'HET_HIEU_LUC')
    return problem(409, 'Phiên bản đã hết hiệu lực, không thêm được');
  const b = (r.body ?? {}) as CatalogItemInput;
  const target = draftOf(c);
  const bad = validate(target, b);
  if (bad) return bad;
  const item: StoredItem = {
    ...b,
    id: crypto.randomUUID(),
    catalogId: target.id,
    code: b.code || nextCode(target, b.positionId!),
    status: b.status ?? 'AP_DUNG',
    usageCount: 0,
    usagePeriods: 0,
    usedInLockedPeriod: false,
    rowVersion: 0,
  };
  items!.push(item);
  return {
    status: 201,
    body: { item: out(item), catalog: view(target), movedToNewVersion: target.id !== c.id },
    headers: { Location: `/api/v1/catalog/${target.id}/items/${item.id}` },
  };
}

function updateItem(r: MockRequest): MockResponse {
  load();
  const c = findCatalog(r.pathParams['id']);
  const cur = items!.find(
    (i) => i.id === r.pathParams['itemId'] && i.catalogId === r.pathParams['id'],
  );
  if (!c || !cur) return problem(404, 'Không tìm thấy mục danh mục');
  if (c.status === 'HET_HIEU_LUC') return problem(409, 'Phiên bản đã hết hiệu lực, không sửa được');
  const ifMatch = r.headers.get('If-Match');
  if (!ifMatch) return problem(428, 'Thiếu If-Match');
  if (ifMatch.replace(/"/g, '') !== String(cur.rowVersion))
    return problem(409, 'Mục vừa được người khác cập nhật. Vui lòng tải lại.');
  const b = (r.body ?? {}) as CatalogItemInput;
  // BR-04-13: mục đã dùng ở kỳ đã khoá không sửa tại chỗ, thay đổi đi vào phiên bản nháp kế tiếp
  const target = cur.usedInLockedPeriod ? draftOf(c) : c;
  const into =
    target.id === c.id
      ? cur
      : items!.find((i) => i.catalogId === target.id && i.code === cur.code)!;
  const bad = validate(target, b, into.id);
  if (bad) return bad;
  Object.assign(into, b, { code: b.code || into.code, rowVersion: into.rowVersion! + 1 });
  return ok({ item: out(into), catalog: view(target), movedToNewVersion: target.id !== c.id });
}

function cloneVersion(r: MockRequest): MockResponse {
  load();
  const c = findCatalog(r.pathParams['id']);
  if (!c) return problem(404, 'Không tìm thấy danh mục');
  if (catalogs!.some((x) => x.name === c.name && x.status === 'NHAP'))
    return problem(409, 'Danh mục đã có một phiên bản nháp, hãy sửa trên phiên bản đó');
  return { status: 201, body: view(clone(c)) };
}

function importExcel(r: MockRequest): MockResponse {
  load();
  const c = findCatalog(r.pathParams['id']);
  if (!c) return problem(404, 'Không tìm thấy danh mục');
  const file = r.body instanceof FormData ? r.body.get('file') : null;
  const name = file instanceof File ? file.name : 'danh-muc.xlsx';
  if (!/\.(xlsx|xls|csv)$/i.test(name))
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'file', message: 'Chỉ nhận tệp Excel (.xlsx, .xls) hoặc .csv' }],
    });
  const id = `imp-${crypto.randomUUID().slice(0, 8)}`;
  // tệp giả lập: kết quả cố định để thấy cả dòng hợp lệ và dòng lỗi
  const job: CatalogImportJob = {
    id,
    fileName: name,
    status: 'DANG_XU_LY',
    totalRows: 50,
    validRows: 47,
    errorGroups: [
      { reason: 'thiếu đơn vị tính hoặc mức khó', count: 2, kind: 'THIEU' },
      { reason: 'tên mơ hồ ("tăng cường")', count: 1, kind: 'MO_HO' },
    ],
  };
  jobs.set(id, { job, polls: 0 });
  latest.set(c.id, id);
  return { status: 202, body: null, headers: { Location: `/api/v1/catalog/imports/${id}` } };
}

function importJob(r: MockRequest): MockResponse {
  load();
  const j = jobs.get(r.pathParams['jobId']);
  if (!j) return problem(404, 'Không tìm thấy tác vụ nhập');
  // lần hỏi đầu còn đang xử lý, lần sau xong
  if (j.polls++ > 0 && j.job.status === 'DANG_XU_LY')
    j.job = { ...j.job, status: 'XONG', finishedAt: new Date().toISOString() };
  return ok(j.job);
}

function importErrors(r: MockRequest): MockResponse {
  load();
  const j = jobs.get(r.pathParams['jobId']);
  if (!j) return problem(404, 'Không tìm thấy tác vụ nhập');
  const rows = [
    'dong,ly_do',
    ...j.job.errorGroups.flatMap((g, k) =>
      Array.from(
        { length: Math.min(g.count, 20) },
        (_, i) => `${(k + 1) * 100 + i},${g.reason.replace(/[",]/g, ' ')}`,
      ),
    ),
  ];
  return {
    status: 200,
    body: new Blob(['﻿' + rows.join('\n')], { type: 'text/csv' }),
    headers: { 'Content-Type': 'text/csv' },
  };
}

export const CATALOG_MOCK: MockModule = {
  name: 'catalog',
  reset: () => {
    catalogs = null;
    items = null;
  },
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/catalog/items',
      handle: (r) => {
        load();
        const limit = Math.min(Number(r.params.get('limit') ?? 50) || 50, MAX_PAGE_LIMIT);
        const q = r.params.get('q')?.toLowerCase();
        let list = itemsOf(applied().id).filter((i) => i.status === 'AP_DUNG');
        if (q)
          list = list.filter(
            (i) => i.name.toLowerCase().includes(q) || i.code.toLowerCase().includes(q),
          );
        return ok({ items: list.slice(0, limit).map(out), nextCursor: null });
      },
    },
    { method: 'GET', pattern: '/api/v1/catalog', handle: listCatalogs },
    { method: 'GET', pattern: '/api/v1/catalog/imports/:jobId', handle: importJob },
    { method: 'GET', pattern: '/api/v1/catalog/imports/:jobId/errors', handle: importErrors },
    { method: 'GET', pattern: '/api/v1/catalog/:id/items', handle: listItems },
    {
      method: 'GET',
      pattern: '/api/v1/catalog/:id/positions',
      handle: () => ok({ items: fixture.positions }),
    },
    {
      method: 'GET',
      pattern: '/api/v1/catalog/:id/imports/latest',
      handle: (r) => {
        load();
        const id = latest.get(r.pathParams['id']);
        return ok(id ? jobs.get(id)!.job : null);
      },
    },
    { method: 'POST', pattern: '/api/v1/catalog/:id/items', handle: addItem },
    { method: 'PUT', pattern: '/api/v1/catalog/:id/items/:itemId', handle: updateItem },
    { method: 'POST', pattern: '/api/v1/catalog/:id/versions', handle: cloneVersion },
    { method: 'POST', pattern: '/api/v1/catalog/:id/imports', handle: importExcel },
  ],
};
