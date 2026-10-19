import { Job } from '../../lib/models/job';
import {
  LEDGER_MAX_RANGE_DAYS,
  LedgerEntry,
  LedgerEntryDetail,
  LedgerVerifyKind,
  LedgerVerifyRun,
} from '../../lib/models/ledger';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import {
  StoredEntry,
  TAMPERED_PARTITION,
  TAMPERED_RECOMPUTED,
  TAMPERED_SEQ,
  TAMPERED_STORED,
  chainAround,
  fakeHash,
  ledgerFixture,
  verifyRunFixtures,
} from './ledger.fixture';

/**
 * Giả lập sổ cái SVC-09 (QT-13, SCR-1302, WEB-043) theo `contracts/openapi/modules/oversight.yaml`: tra cứu
 * có lọc và phân trang con trỏ (422 `LEDGER-422-RANGE-TOO-LONG` khi quá 2 năm), chi tiết bản ghi kèm niêm
 * phong theo giờ (bản ghi trong giờ hiện tại chưa niêm phong) và bản neo, kiểm tra toàn vẹn chạy nền
 * (202; lần kiểm tra tiến một nhịp mỗi lần đọc, xong sau hai nhịp, phân vùng 17 luôn phá vỡ tại 1042) và
 * xuất bằng chứng (202, job hoàn tất ngay).
 */

let entries: StoredEntry[] = ledgerFixture();
let runs: LedgerVerifyRun[] = verifyRunFixtures(entries);
/** Số lần đọc còn lại trước khi lần kiểm tra đang chạy xong. */
const pending = new Map<string, number>();

const DAY = 86_400_000;

const publicEntry = (e: StoredEntry): LedgerEntry => {
  const { prevHash: _p, payloadHash: _h, metadataHash: _m, recomputedHash: _r, ...rest } = e;
  return structuredClone(rest);
};

function rangeError(from?: string | null, to?: string | null): MockResponse | null {
  const f = from ? Date.parse(from) : NaN;
  const t = to ? Date.parse(to) : NaN;
  if (!Number.isNaN(f) && !Number.isNaN(t)) {
    if (f > t)
      return problem(422, 'Dữ liệu không hợp lệ', {
        errors: [{ field: 'from', message: 'Ngày bắt đầu phải trước ngày kết thúc.' }],
      });
    if (t - f > LEDGER_MAX_RANGE_DAYS * DAY)
      return problem(422, 'Mỗi lần tra cứu chỉ được chọn khoảng thời gian tối đa 2 năm.', {
        code: 'LEDGER-422-RANGE-TOO-LONG',
        errors: [{ field: 'from', message: 'Chọn khoảng thời gian không quá 2 năm.' }],
      });
  }
  return null;
}

function list(req: MockRequest): MockResponse {
  const p = req.params;
  const err = rangeError(p.get('from'), p.get('to'));
  if (err) return err;
  const from = p.get('from') ? Date.parse(p.get('from')!) : -Infinity;
  const to = p.get('to') ? Date.parse(p.get('to')!) : Infinity;
  const part = p.get('chainPartition');
  const hits = entries
    .filter((e) => !p.get('aggregateType') || e.aggregateType === p.get('aggregateType'))
    .filter((e) => !p.get('aggregateId') || e.aggregateId === p.get('aggregateId'))
    .filter((e) => !p.get('eventType') || e.eventType === p.get('eventType'))
    .filter((e) => part === null || e.chainPartition === Number(part))
    .filter((e) => {
      const t = Date.parse(e.occurredAt);
      return t >= from && t <= to;
    })
    .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt));
  const limit = Math.min(Number(p.get('limit') ?? 50) || 50, 200);
  const start = Number(p.get('cursor') ?? 0) || 0;
  const page = hits.slice(start, start + limit).map(publicEntry);
  return ok({
    items: page,
    nextCursor: start + limit < hits.length ? String(start + limit) : null,
  });
}

function detail(req: MockRequest): MockResponse {
  const e = entries.find((x) => x.id === req.pathParams['id']);
  if (!e) return problem(404, 'Không tìm thấy bản ghi sổ cái.');
  const t = new Date(e.occurredAt);
  t.setMinutes(0, 0, 0);
  const windowStart = t.toISOString();
  const windowEnd = new Date(t.getTime() + 3_600_000).toISOString();
  const sealed = Date.parse(windowEnd) <= Date.now();
  const body: LedgerEntryDetail = {
    ...publicEntry(e),
    prevHash: e.prevHash,
    payloadHash: e.payloadHash,
    metadataHash: e.metadataHash,
    seal: sealed
      ? {
          id: `seal-${e.id}`,
          windowStart,
          windowEnd,
          entryCount: 1_180 + (e.seqNo % 97),
          tenantRoot: e.seqNo === TAMPERED_SEQ ? TAMPERED_STORED : fakeHash(`root-${windowStart}`),
          prevTenantRoot: fakeHash(`root-${new Date(t.getTime() - 3_600_000).toISOString()}`),
          cellSealId: `cell-seal-${windowStart}`,
          cellRoot: fakeHash(`cell-${windowStart}`),
          signatureValid: true,
          tsaGenTime: new Date(Date.parse(windowEnd) + 120_000).toISOString(),
          tsaLate: false,
        }
      : null,
    anchors: sealed
      ? [
          {
            id: `anc-vptw-${e.id}`,
            anchorTarget: 'KHO_VPTW',
            receipt: `Biên nhận số ${e.seqNo}/VPTW, ký bằng chứng thư số của cơ quan giám sát`,
            anchoredAt: new Date(Date.parse(windowEnd) + 300_000).toISOString(),
          },
          {
            id: `anc-tsa-${e.id}`,
            anchorTarget: 'TSA_DOC_LAP',
            receipt: 'Dấu thời gian được chứng thực của Viettel-CA',
            anchoredAt: new Date(Date.parse(windowEnd) + 120_000).toISOString(),
          },
        ]
      : [],
  };
  return ok(body);
}

function listRuns(req: MockRequest): MockResponse {
  const kind = req.params.get('verifyKind');
  const items = runs
    .filter((r) => !kind || r.verifyKind === kind)
    .sort((a, b) => b.startedAt.localeCompare(a.startedAt));
  return ok({ items: structuredClone(items), nextCursor: null });
}

function getRun(req: MockRequest): MockResponse {
  const r = runs.find((x) => x.id === req.pathParams['id']);
  if (!r) return problem(404, 'Không tìm thấy lần kiểm tra.');
  const left = pending.get(r.id);
  if (left !== undefined) {
    if (left <= 1) {
      pending.delete(r.id);
      finish(r);
    } else {
      pending.set(r.id, left - 1);
      r.entriesChecked = Math.round(r.entriesChecked + totalFor(r) / 2);
    }
  }
  return ok(structuredClone(r));
}

const totalFor = (r: LedgerVerifyRun) => (r.chainPartition == null ? 1_284_311 : 20_068);

/** Kết thúc lần kiểm tra: phân vùng 17 (hoặc toàn bộ) phá vỡ tại 1042, phân vùng khác khớp. */
function finish(r: LedgerVerifyRun) {
  const all = r.chainPartition == null;
  const broken = all || r.chainPartition === TAMPERED_PARTITION;
  const hit = entries.find((e) => e.seqNo === TAMPERED_SEQ)!;
  r.entriesChecked = totalFor(r);
  r.sealsChecked = all ? 7_224 : 113;
  r.partitionsTotal = all ? 64 : 1;
  r.partitionsMatched = r.partitionsTotal - (broken ? 1 : 0);
  r.mismatches = broken ? 1 : 0;
  r.firstMismatch = broken
    ? {
        chainPartition: TAMPERED_PARTITION,
        seqNo: TAMPERED_SEQ,
        entryId: hit.id,
        storedHash: TAMPERED_STORED,
        recomputedHash: TAMPERED_RECOMPUTED,
        sealId: `seal-${hit.id}`,
        anchorMatches: true,
      }
    : null;
  r.chain = broken ? chainAround(entries, TAMPERED_SEQ) : [];
  r.alertId = broken ? 'cb-2026-0140' : null;
  r.finishedAt = new Date(Date.parse(r.startedAt) + (all ? 48_000 : 3_000)).toISOString();
}

function nextRunCode(): string {
  const year = new Date().getFullYear();
  const nums = runs.map((r) => Number((r.code ?? '').slice(-4)) || 0);
  return `VR-${year}-${String(Math.max(0, ...nums) + 1).padStart(4, '0')}`;
}

const accepted = (job: Job): MockResponse => ({
  status: 202,
  body: job,
  headers: { Location: `/api/v1/jobs/${job.id}`, 'Retry-After': '1' },
});

const KINDS: LedgerVerifyKind[] = ['DAU_CHUOI', 'NIEM_PHONG', 'TOAN_BO', 'LUU_TRU'];

function startRun(req: MockRequest): MockResponse {
  const b = (req.body ?? {}) as {
    verifyKind?: LedgerVerifyKind;
    chainPartition?: number | null;
    rangeFrom?: string;
    rangeTo?: string;
  };
  if (!b.verifyKind || !KINDS.includes(b.verifyKind))
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'verifyKind', message: 'Chọn loại kiểm tra.' }],
    });
  const part = b.chainPartition;
  if (part != null && (!Number.isInteger(part) || part < 0 || part > 63))
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'chainPartition', message: 'Phân vùng là số từ 0 đến 63.' }],
    });
  const err = rangeError(b.rangeFrom, b.rangeTo);
  if (err) return err;
  const now = new Date();
  const run: LedgerVerifyRun = {
    id: crypto.randomUUID(),
    code: nextRunCode(),
    verifyKind: b.verifyKind,
    chainPartition: part ?? null,
    rangeFrom: b.rangeFrom ?? new Date(now.getTime() - 730 * DAY).toISOString(),
    rangeTo: b.rangeTo ?? now.toISOString(),
    entriesChecked: 0,
    mismatches: 0,
    firstMismatch: null,
    chain: [],
    alertId: null,
    startedAt: now.toISOString(),
    finishedAt: null,
  };
  runs.unshift(run);
  pending.set(run.id, 2);
  return accepted({
    id: crypto.randomUUID(),
    type: 'LEDGER_VERIFY',
    status: 'DANG_CHAY',
    progress: 0,
    message: 'Đang kiểm tra toàn vẹn sổ cái',
    resultLocation: `/api/v1/ledger/verify-runs/${run.id}`,
    updatedAt: now.toISOString(),
  });
}

function startExport(req: MockRequest): MockResponse {
  const b = (req.body ?? {}) as { from?: string; to?: string };
  if (!b.from || !b.to)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'from', message: 'Chọn khoảng thời gian cần xuất.' }],
    });
  const err = rangeError(b.from, b.to);
  if (err) return err;
  return accepted({
    id: crypto.randomUUID(),
    type: 'EXPORT',
    status: 'HOAN_TAT',
    progress: 100,
    message: 'Tệp bằng chứng đã sẵn sàng.',
    resultLocation: `/api/v1/files/${crypto.randomUUID()}`,
    updatedAt: new Date().toISOString(),
  });
}

export const LEDGER_MOCK: MockModule = {
  name: 'ledger',
  routes: [
    { method: 'GET', pattern: '/api/v1/ledger/entries', handle: list },
    { method: 'GET', pattern: '/api/v1/ledger/entries/:id', handle: detail },
    { method: 'GET', pattern: '/api/v1/ledger/verify-runs', handle: listRuns },
    { method: 'POST', pattern: '/api/v1/ledger/verify-runs', handle: startRun },
    { method: 'GET', pattern: '/api/v1/ledger/verify-runs/:id', handle: getRun },
    { method: 'POST', pattern: '/api/v1/ledger/exports', handle: startExport },
  ],
  reset: () => {
    entries = ledgerFixture();
    runs = verifyRunFixtures(entries);
    pending.clear();
  },
};
