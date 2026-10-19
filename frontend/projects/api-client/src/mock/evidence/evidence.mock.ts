import {
  Deliverable,
  Evidence,
  EvidenceExternalDoc,
  EvidenceVersion,
  EvidenceVersionDraft,
  EvidenceVersionSource,
  EvidenceVersionStatus,
  FileObject,
  FileUploadCreate,
  FileUploadPart,
} from '../../lib/models/evidence';
import { FieldError } from '../../lib/problem-details';
import { MOCK_ME, mockSession } from '../mock-session';
import {
  MockModule,
  MockRequest,
  MockResponse,
  ok,
  problem,
  resolveRelativeDates,
} from '../mock-router';
import tasksFixture from '../tasks/tasks.json';
import fixture from './evidence.json';

/**
 * Giả lập `/api/v1/products`, `/api/v1/evidences` (MOD-03) và `/api/v1/files` (SVC-08) theo hợp đồng
 * `products.yaml` cho SCR-0701 (WEB-032): tải lên nhiều phần, hoàn tất (băm lại, tra trùng, xếp quét), quét
 * mã độc bất đồng bộ, lưu nháp và nộp phiên bản minh chứng. Máy chủ chặn BR-07-01 … BR-07-04.
 * Tên tệp chứa `sai-bam` giả lập mã băm không khớp (MSG-07-03); chứa `eicar` giả lập tệp nhiễm (MSG-07-02).
 */

type StoredVersion = EvidenceVersion & {
  decidedAt?: string;
  decisionNote?: string;
  decidedByName?: string;
};
type StoredEvidence = Omit<Evidence, 'task' | 'versions' | 'currentVersionNo' | 'currentStatus'> & {
  taskId: string;
  versions: StoredVersion[];
};
type StoredObject = FileObject & { scanPolls: number };
interface StoredUpload {
  uploadId: string;
  body: FileUploadCreate;
  status: 'MO' | 'HOAN_TAT' | 'HUY';
  partSize: number;
  partCount: number;
  parts: Map<number, FileUploadPart>;
  expiresAt: string;
}

const PART_SIZE = 5 * 1024 * 1024;
const OPEN: EvidenceVersionStatus[] = ['NHAP', 'DA_NOP', 'DANG_NGHIEM_THU'];
const SOURCES: EvidenceVersionSource[] = ['TAI_LEN', 'VAN_BAN_HE_THONG', 'OCR_DI_DONG', 'TICH_HOP'];

const VERSION_DEFAULTS: Partial<StoredVersion> = {
  hasE2ee: false,
  aiPrefill: false,
  isDuplicateSuspect: false,
  scanStatus: 'SACH',
};

let evidences: StoredEvidence[] | null = null;
let objects: StoredObject[] | null = null;
let uploads: StoredUpload[] = [];
let seq = 0;

const uuid = (kind: string) => `0192a1${kind}-0000-7000-8000-${String(++seq).padStart(12, '0')}`;
const now = () => new Date().toISOString();

function store(): StoredEvidence[] {
  if (evidences) return evidences;
  const raw = resolveRelativeDates(structuredClone(fixture)) as unknown as typeof fixture;
  evidences = (raw.evidences as unknown as StoredEvidence[]).map((e) => ({
    ...e,
    // fixture chỉ ghi trường khác mặc định
    versions: (e.versions as Partial<StoredVersion>[]).map((v) => ({
      ...VERSION_DEFAULTS,
      ...(v as StoredVersion),
      id: `${e.id}-v${v.versionNo}`,
      evidenceId: e.id,
      taskId: e.taskId,
    })),
  }));
  return evidences;
}

const deliverables = () =>
  resolveRelativeDates(structuredClone(fixture.deliverables)) as unknown as Deliverable[];
const externalDocs = () =>
  resolveRelativeDates(structuredClone(fixture.externalDocs)) as unknown as EvidenceExternalDoc[];
const dedupIndex = () =>
  resolveRelativeDates(structuredClone(fixture.dedupIndex)) as {
    sha256: string;
    taskCode: string;
    ownerName: string;
    submittedAt: string;
  }[];

function taskRef(id: string) {
  const t = tasksFixture.tasks.find((x) => x.id === id) as
    { code?: string; title?: string } | undefined;
  return { id, code: t?.code ?? id, name: t?.title ?? '' };
}

const latest = (e: StoredEvidence) =>
  e.versions[e.versions.length - 1] as StoredVersion | undefined;
/** Kho đối tượng SVC-08, nạp lười tách khỏi kho minh chứng để tệp vừa tải lên không bị nạp đè. */
function objectStore(): StoredObject[] {
  objects ??= (
    resolveRelativeDates(structuredClone(fixture.objects)) as unknown as FileObject[]
  ).map((o) => ({ ...o, scanPolls: 0 }));
  return objects;
}
const findObject = (id?: string | null) => objectStore().find((o) => o.objectId === id);

/** Trường tệp của phiên bản lấy từ SVC-08 tại thời điểm đọc (trạng thái quét thay đổi bất đồng bộ). */
function view(v: StoredVersion): EvidenceVersion {
  const { decidedAt: _d, decisionNote: _n, decidedByName: _b, ...rest } = structuredClone(v);
  const o = findObject(v.objectId);
  if (!o) return { ...rest, objectVersionId: null, contentSha256: null };
  const dup = o.dedup?.matches?.[0] ?? null;
  return {
    ...rest,
    objectVersionId: o.objectVersionId,
    contentSha256: o.sha256,
    sizeBytes: o.sizeBytes,
    mediaType: o.mediaType,
    fileName: o.fileName,
    scanStatus: o.scanStatus,
    isDuplicateSuspect: !!dup,
    duplicateOf: dup,
  };
}

function evidenceView(e: StoredEvidence, withVersions: boolean): Evidence {
  const last = latest(e);
  const lastView = last && view(last);
  return {
    id: e.id,
    deliverableId: e.deliverableId,
    task: taskRef(e.taskId),
    title: e.title,
    sourceType: e.sourceType,
    externalDocRef: e.externalDocRef ?? null,
    currentVersionNo: last?.versionNo ?? null,
    currentStatus: last?.status ?? null,
    isDuplicateSuspect: lastView?.isDuplicateSuspect ?? false,
    submittedBy: [...e.versions].reverse().find((v) => v.submittedBy)?.submittedBy,
    submittedAt: [...e.versions].reverse().find((v) => v.submittedAt)?.submittedAt,
    versions: withVersions
      ? e.versions.map((v) => ({
          versionNo: v.versionNo,
          status: v.status,
          submittedAt: v.submittedAt,
          decidedAt: v.decidedAt,
          decisionNote: v.decisionNote,
        }))
      : [],
    rowVersion: e.rowVersion,
  };
}

function locate(req: MockRequest): { e: StoredEvidence; v?: StoredVersion } | MockResponse {
  const e = store().find((x) => x.id === req.pathParams['id']);
  if (!e) return problem(404, 'Không tìm thấy minh chứng');
  if (!req.pathParams['v']) return { e };
  const v = e.versions.find((x) => x.versionNo === Number(req.pathParams['v']));
  return v ? { e, v } : problem(404, 'Không tìm thấy phiên bản minh chứng');
}

const etag = (n: number) => ({ ETag: `"${n}"` });

function ifMatch(v: StoredVersion, req: MockRequest): MockResponse | null {
  const h = req.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu If-Match');
  return h.replace(/"/g, '') === String(v.rowVersion)
    ? null
    : problem(409, 'Phiên bản minh chứng vừa được cập nhật ở nơi khác. Vui lòng tải lại.', {
        code: 'PRODUCT-409-VERSION-CONFLICT',
      });
}

/** Kiểm nội dung nháp theo `EvidenceVersionDraft`; trả lỗi 422 theo trường. */
function validateDraft(d: EvidenceVersionDraft): FieldError[] {
  const errors: FieldError[] = [];
  if (!SOURCES.includes(d.source)) errors.push({ field: 'source', message: 'Nguồn không hợp lệ' });
  if (d.objectId && !findObject(d.objectId))
    errors.push({ field: 'objectId', message: 'Không tìm thấy tệp đã tải lên' });
  if (d.sourceDocRef && !externalDocs().some((x) => x.ref === d.sourceDocRef))
    errors.push({ field: 'sourceDocRef', message: 'Không tìm thấy văn bản trong vOffice' });
  const j = d.reuseJustification?.trim();
  if (j && (j.length < 20 || j.length > 1000))
    errors.push({ field: 'reuseJustification', message: 'Giải trình từ 20 đến 1.000 ký tự' });
  if ((d.docSummary ?? '').length > 500)
    errors.push({ field: 'docSummary', message: 'Trích yếu tối đa 500 ký tự' });
  if ((d.note ?? '').length > 2000)
    errors.push({ field: 'note', message: 'Ghi chú tối đa 2.000 ký tự' });
  return errors;
}

function applyDraft(v: StoredVersion, d: EvidenceVersionDraft) {
  const doc = d.sourceDocRef ? externalDocs().find((x) => x.ref === d.sourceDocRef) : undefined;
  Object.assign(v, {
    source: d.source,
    sourceDocRef: d.sourceDocRef ?? null,
    objectId: d.objectId ?? null,
    reuseJustification: d.reuseJustification?.trim() || null,
    // BR-07-13: số, ngày, trích yếu của văn bản lấy từ vOffice
    docNumber: doc?.docNumber ?? d.docNumber ?? null,
    docDate: doc?.docDate ?? d.docDate ?? null,
    docSummary: doc?.summary ?? d.docSummary ?? null,
    note: d.note ?? null,
  });
}

function createVersion(req: MockRequest): MockResponse {
  const hit = locate(req);
  if ('status' in hit) return hit;
  const { e } = hit;
  const last = latest(e);
  if (last && OPEN.includes(last.status))
    return problem(409, 'Phiên bản mới nhất chưa kết thúc, không tạo được phiên bản mới.', {
      code: 'PRODUCT-409-OPEN-VERSION-EXISTS',
    });
  const d = req.body as EvidenceVersionDraft;
  const errors = validateDraft(d);
  if (errors.length) return problem(422, 'Dữ liệu không hợp lệ', { errors });
  const v: StoredVersion = {
    id: uuid('d4'),
    evidenceId: e.id,
    taskId: e.taskId,
    versionNo: (last?.versionNo ?? 0) + 1,
    status: 'NHAP',
    source: d.source,
    scanStatus: 'SACH',
    isDuplicateSuspect: false,
    hasE2ee: false,
    aiPrefill: false,
    rowVersion: 1,
  };
  applyDraft(v, d);
  e.versions.push(v);
  e.rowVersion++;
  return {
    status: 201,
    body: view(v),
    headers: {
      ...etag(v.rowVersion),
      Location: `/api/v1/evidences/${e.id}/versions/${v.versionNo}`,
    },
  };
}

function saveVersion(req: MockRequest): MockResponse {
  const hit = locate(req);
  if ('status' in hit) return hit;
  const v = hit.v!;
  const conflict = ifMatch(v, req);
  if (conflict) return conflict;
  if (v.status !== 'NHAP')
    return problem(409, 'Phiên bản đã nộp không sửa được (BR-07-01).', {
      code: 'PRODUCT-409-INVALID-STATE',
    });
  const d = req.body as EvidenceVersionDraft;
  const errors = validateDraft(d);
  if (errors.length) return problem(422, 'Dữ liệu không hợp lệ', { errors });
  applyDraft(v, d);
  v.rowVersion++;
  return ok(view(v), etag(v.rowVersion));
}

function submit(req: MockRequest): MockResponse {
  const hit = locate(req);
  if ('status' in hit) return hit;
  const { e } = hit;
  const v = hit.v!;
  const conflict = ifMatch(v, req);
  if (conflict) return conflict;
  if (v.status !== 'NHAP')
    return problem(409, 'Phiên bản không ở trạng thái nháp.', {
      code: 'PRODUCT-409-INVALID-STATE',
    });
  if ((req.body as { attested?: boolean })?.attested !== true)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'attested', message: 'Xác nhận cam kết trước khi nộp' }],
    });
  if (v.source === 'VAN_BAN_HE_THONG' ? !v.sourceDocRef : !v.objectId)
    return problem(422, 'Chưa có minh chứng để nộp', {
      errors: [
        {
          field: v.source === 'VAN_BAN_HE_THONG' ? 'sourceDocRef' : 'objectId',
          message: v.source === 'VAN_BAN_HE_THONG' ? 'Chọn văn bản' : 'Tải tệp minh chứng',
        },
      ],
    });
  const o = findObject(v.objectId);
  if (o?.scanStatus === 'NHIEM')
    return problem(422, `Tệp "${o.fileName}" không an toàn và đã được cách ly.`, {
      code: 'FILE-422-INFECTED',
    });
  if (o && o.scanStatus !== 'SACH')
    return problem(422, 'Tệp chưa quét xong mã độc, chưa nộp được (BR-07-04).', {
      code: 'FILE-422-NOT-SCANNED',
    });
  if (o?.dedup?.duplicate && (v.reuseJustification ?? '').length < 20)
    return problem(422, 'Thiếu giải trình dùng lại minh chứng (BR-07-03)', {
      errors: [{ field: 'reuseJustification', message: 'Ghi giải trình từ 20 đến 1.000 ký tự' }],
    });
  const at = now();
  Object.assign(v, {
    status: 'DA_NOP',
    submittedAt: at,
    attestedAt: at,
    submittedBy: { id: MOCK_ME.id, name: MOCK_ME.name },
    rowVersion: v.rowVersion + 1,
  });
  e.rowVersion++;
  // nhiệm vụ sang Chờ nghiệm thu khi mọi sản phẩm của nó đã có phiên bản nộp (EVT-06)
  const ds = deliverables().filter((d) => d.taskId === e.taskId);
  const submitted = (id: string) =>
    store().some(
      (x) => x.deliverableId === id && x.versions.some((y) => y.status !== 'NHAP' && y.submittedAt),
    );
  return ok(
    {
      version: view(v),
      reviewer: { id: 'u-lmchau', name: fixture.reviewerName },
      taskStatusChanged: ds.every((d) => submitted(d.id)) ? 'CHO_NGHIEM_THU' : null,
    },
    etag(v.rowVersion),
  );
}

// ── SVC-08: tải lên nhiều phần ──

function createUpload(req: MockRequest): MockResponse {
  const body = req.body as FileUploadCreate;
  if (body.declaredSize > 50 * 1024 * 1024)
    return problem(413, 'Tệp vượt 50 MB', { code: 'FILE-413-TOO-LARGE' });
  if (!/^[0-9a-f]{64}$/.test(body.declaredSha256 ?? ''))
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'declaredSha256', message: 'Mã băm SHA-256 không hợp lệ' }],
    });
  const u: StoredUpload = {
    uploadId: uuid('c2'),
    body,
    status: 'MO',
    partSize: PART_SIZE,
    partCount: Math.max(1, Math.ceil(body.declaredSize / PART_SIZE)),
    parts: new Map(),
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
  };
  uploads.push(u);
  return {
    status: 201,
    body: sessionView(u),
    headers: { Location: `/api/v1/files/uploads/${u.uploadId}` },
  };
}

const sessionView = (u: StoredUpload) => ({
  uploadId: u.uploadId,
  status: u.status,
  partSize: u.partSize,
  partCount: u.partCount,
  partsDone: u.parts.size,
  receivedParts: [...u.parts.values()],
  expiresAt: u.expiresAt,
});

const upload = (req: MockRequest) => uploads.find((u) => u.uploadId === req.pathParams['uploadId']);

function putPart(req: MockRequest): MockResponse {
  const u = upload(req);
  if (!u) return problem(404, 'Không tìm thấy phiên tải lên');
  if (u.status !== 'MO')
    return problem(409, 'Phiên tải lên đã đóng', { code: 'FILE-409-UPLOAD-CLOSED' });
  const n = Number(req.pathParams['n']);
  // mất kết nối giữa chừng (QT-07): lỗi 503 không được lưu theo Idempotency-Key nên gửi lại sẽ qua
  if (mockSession.takeUploadFailure(n)) return problem(503, 'Mất kết nối khi gửi phần tệp');
  const size = req.body instanceof Blob ? req.body.size : 0;
  if (size > u.partSize)
    return problem(413, 'Phần vượt kích thước', { code: 'FILE-413-PART-TOO-LARGE' });
  const part = { partNo: n, size, etag: `"p${n}-${u.uploadId.slice(-6)}"` };
  u.parts.set(n, part);
  return ok(part);
}

function complete(req: MockRequest): MockResponse {
  const u = upload(req);
  if (!u) return problem(404, 'Không tìm thấy phiên tải lên');
  if (u.status !== 'MO')
    return problem(409, 'Phiên tải lên đã đóng', { code: 'FILE-409-UPLOAD-CLOSED' });
  if (u.parts.size < u.partCount)
    return problem(422, 'Chưa nhận đủ các phần của tệp', { code: 'FILE-422-MISSING-PARTS' });
  // BR-07-02: máy chủ băm lại toàn tệp và so với băm khai báo
  if (/sai-bam/i.test(u.body.fileName))
    return problem(422, 'Mã băm kiểm tra ở máy chủ không khớp. Vui lòng tải lại tệp.', {
      code: 'FILE-422-HASH-MISMATCH',
    });
  u.status = 'HOAN_TAT';
  const matches = dedupIndex()
    .filter((d) => d.sha256 === u.body.declaredSha256)
    .map(({ sha256: _s, ...m }) => m);
  const o: StoredObject = {
    objectId: uuid('c3'),
    objectVersionId: uuid('c3'),
    versionNo: 1,
    fileName: u.body.fileName,
    mediaType: u.body.mediaType,
    sizeBytes: u.body.declaredSize,
    sha256: u.body.declaredSha256,
    classification: u.body.classification,
    e2ee: false,
    scanStatus: 'CHO_QUET',
    dedup: { duplicate: matches.length > 0, matches },
    createdAt: now(),
    scanPolls: 0,
  };
  objectStore().push(o);
  const { scanPolls: _p, ...out } = o;
  return ok(out);
}

/** Quét bất đồng bộ (SEC-47): lần hỏi thứ hai trả kết luận. */
function scan(req: MockRequest): MockResponse {
  const o = findObject(req.pathParams['objectId']);
  if (!o) return problem(404, 'Không tìm thấy tệp');
  if (o.scanStatus === 'CHO_QUET' && ++o.scanPolls >= 2)
    o.scanStatus = /eicar/i.test(o.fileName ?? '') ? 'NHIEM' : 'SACH';
  return ok({
    objectId: o.objectId,
    objectVersionId: o.objectVersionId,
    scanStatus: o.scanStatus,
    verdict: o.scanStatus === 'CHO_QUET' ? undefined : o.scanStatus,
    scannedAt: o.scanStatus === 'CHO_QUET' ? undefined : now(),
  });
}

export const EVIDENCE_MOCK: MockModule = {
  name: 'evidence',
  reset: () => {
    evidences = null;
    objects = null;
    uploads = [];
    seq = 0;
  },
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/products',
      handle: (r) => {
        const task = r.params.get('task');
        return ok({
          items: deliverables().filter((d) => !task || d.taskId === task),
          nextCursor: null,
        });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/products/:id',
      handle: (r) => {
        const d = deliverables().find((x) => x.id === r.pathParams['id']);
        return d ? ok(d, etag(d.rowVersion)) : problem(404, 'Không tìm thấy sản phẩm');
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/evidences/external-docs',
      handle: (r) => {
        const q = (r.params.get('q') ?? '').toLowerCase().trim();
        const items = externalDocs().filter(
          (d) => !q || `${d.docNumber} ${d.summary}`.toLowerCase().includes(q),
        );
        return ok({ items, nextCursor: null });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/evidences',
      handle: (r) => {
        const task = r.params.get('task');
        const deliverable = r.params.get('deliverable');
        const status = r.params.get('status')?.split(',');
        const items = store()
          .filter((e) => !task || e.taskId === task)
          .filter((e) => !deliverable || e.deliverableId === deliverable)
          .filter((e) => !status || status.includes(latest(e)?.status ?? ''))
          .map((e) => evidenceView(e, false));
        return ok({ items, nextCursor: null });
      },
    },
    {
      method: 'POST',
      pattern: '/api/v1/evidences',
      handle: (r) => {
        const b = r.body as { deliverableId?: string; title?: string; sourceType?: string };
        const d = deliverables().find((x) => x.id === b?.deliverableId);
        if (!d)
          return problem(422, 'Dữ liệu không hợp lệ', {
            errors: [{ field: 'deliverableId', message: 'Không tìm thấy sản phẩm' }],
          });
        if (!b.title?.trim())
          return problem(422, 'Dữ liệu không hợp lệ', {
            errors: [{ field: 'title', message: 'Nhập tên minh chứng' }],
          });
        const e: StoredEvidence = {
          id: uuid('b2'),
          deliverableId: d.id,
          taskId: d.taskId,
          title: b.title.trim(),
          sourceType: (b.sourceType as StoredEvidence['sourceType']) ?? 'TAI_LEN',
          externalDocRef: (r.body as { externalDocRef?: string }).externalDocRef ?? null,
          rowVersion: 0,
          versions: [],
        };
        store().push(e);
        return {
          status: 201,
          body: evidenceView(e, true),
          headers: { ...etag(0), Location: `/api/v1/evidences/${e.id}` },
        };
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/evidences/:id',
      handle: (r) => {
        const hit = locate(r);
        return 'status' in hit ? hit : ok(evidenceView(hit.e, true), etag(hit.e.rowVersion));
      },
    },
    { method: 'POST', pattern: '/api/v1/evidences/:id/versions', handle: createVersion },
    {
      method: 'GET',
      pattern: '/api/v1/evidences/:id/versions/:v',
      handle: (r) => {
        const hit = locate(r);
        return 'status' in hit ? hit : ok(view(hit.v!), etag(hit.v!.rowVersion));
      },
    },
    { method: 'PUT', pattern: '/api/v1/evidences/:id/versions/:v', handle: saveVersion },
    { method: 'POST', pattern: '/api/v1/evidences/:id/versions/:v/submit', handle: submit },
    { method: 'POST', pattern: '/api/v1/files/uploads', handle: createUpload },
    {
      method: 'GET',
      pattern: '/api/v1/files/uploads/:uploadId',
      handle: (r) => {
        const u = upload(r);
        return u ? ok(sessionView(u)) : problem(404, 'Không tìm thấy phiên tải lên');
      },
    },
    { method: 'PUT', pattern: '/api/v1/files/uploads/:uploadId/parts/:n', handle: putPart },
    { method: 'POST', pattern: '/api/v1/files/uploads/:uploadId/complete', handle: complete },
    { method: 'GET', pattern: '/api/v1/files/:objectId/scan', handle: scan },
  ],
};
