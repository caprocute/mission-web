import {
  KeyResult,
  KrCheckin,
  Objective,
  OkrCheckin,
  OkrCheckinCreate,
  OkrCheckinResult,
  OkrKeyResultDetail,
  OkrKeyResultListItem,
  OkrPeriod,
} from '../../lib/models/okr';
import { krProgress, objectiveProgress } from '../../lib/okr-progress';
import { MockRequest, MockResponse, MockRoute, ok, problem } from '../mock-router';
import fixture from './okr.json';

/**
 * Giả lập check-in KR (QT-03, SCR-0301; WEB-024) trên cùng dữ liệu mục tiêu của cây SCR-0201:
 * gửi check-in cập nhật giá trị hiện tại, mức tự tin, cờ rủi ro của KR nên cây thấy ngay.
 * Quy tắc máy chủ: BR-03-01 (409), BR-03-02 (422), BR-03-04 (422 MSG-03-04), BR-03-05 (422 MSG-03-02),
 * BR-03-06 (cờ `THEO_DOI` → `RUI_RO`). Nguồn số liệu lỗi khi URL trang có `?mock-source=loi` (MSG-03-03).
 */

type Kr = Omit<KeyResult, 'progress'>;
type Obj = Omit<Objective, 'progress' | 'keyResults'> & { keyResults: Kr[] };

interface CheckinState {
  history: Map<string, KrCheckin[]>;
  drafts: Map<string, OkrCheckin>;
  /** Số lần check-in liên tiếp không Thấp, để KR rời `RUI_RO` sau hai lần (QT-03 Hình 7.7). */
  calmStreak: Map<string, number>;
}

let state: CheckinState | null = null;
const checkinState = () =>
  (state ??= {
    history: new Map(
      Object.entries(structuredClone(fixture.checkins) as unknown as Record<string, KrCheckin[]>),
    ),
    drafts: new Map(),
    calmStreak: new Map(),
  });

export function resetCheckins() {
  state = null;
}

const periods = fixture.periods as OkrPeriod[];
const conf = fixture.checkin;

function sourceFails(): boolean {
  return new URLSearchParams(globalThis.location?.search ?? '').get('mock-source') === 'loi';
}

/** Tìm KR trong mọi kỳ của dữ liệu ghi được. */
function findKr(objectives: Record<string, Obj[]>, id: string) {
  for (const [periodId, list] of Object.entries(objectives)) {
    for (const o of list) {
      const kr = o.keyResults.find((k) => k.id === id);
      if (kr) return { periodId, objective: o, kr };
    }
  }
  return null;
}

const lastSent = (id: string) => checkinState().history.get(id)?.[0] ?? null;

function needCheckin(kr: Kr): boolean {
  const at = kr.lastCheckin?.checkinAt;
  return !at || Date.parse(at) < Date.parse(conf.cycleStart);
}

function listItem(o: Obj, kr: Kr): OkrKeyResultListItem {
  const last = lastSent(kr.id);
  return {
    id: kr.id,
    code: kr.code,
    title: kr.title,
    objectiveId: o.id,
    objectiveCode: o.code,
    unit: kr.unit,
    targetValue: kr.targetValue ?? undefined,
    currentValue: kr.currentValue,
    progress: krProgress(kr),
    confidence: kr.confidence ?? undefined,
    riskFlag: kr.riskFlag,
    dueDate: kr.dueDate ?? undefined,
    nextCheckinDue: conf.due,
    lastCheckin:
      last && last.value !== null
        ? { value: last.value, confidence: last.confidence, checkinAt: last.checkinAt }
        : undefined,
  };
}

/** `GET /okr/key-results?owner=me[&needCheckin=true]`: KR của người xem trong mục tiêu `DA_DUYET`. */
export function myKeyResults(req: MockRequest, objectives: Record<string, Obj[]>): MockResponse {
  const periodId = req.params.get('period') ?? fixture.currentPeriodId;
  const period = periods.find((p) => p.id === periodId);
  const all = (objectives[periodId] ?? []).flatMap((o) =>
    o.status === 'DA_DUYET'
      ? o.keyResults.filter((k) => k.viewerIsOwner).map((k) => ({ o, k }))
      : [],
  );
  const need = all.filter(({ k }) => needCheckin(k));
  const rows = req.params.get('needCheckin') === 'true' ? need : all;
  return ok({
    items: rows.map(({ o, k }) => listItem(o, k)),
    nextCursor: null,
    counts: { needCheckin: need.length },
    period,
  });
}

function detail(objectives: Record<string, Obj[]>, id: string): MockResponse {
  const found = findKr(objectives, id);
  if (!found) return problem(404, 'Không tìm thấy kết quả then chốt');
  const { periodId, objective: o, kr } = found;
  const last = lastSent(id);
  const body: OkrKeyResultDetail = {
    id: kr.id,
    objectiveId: o.id,
    periodId,
    code: kr.code,
    title: kr.title,
    unit: kr.unit,
    baselineValue: kr.baselineValue,
    targetValue: kr.targetValue ?? 0,
    currentValue: kr.currentValue,
    weight: kr.weight,
    dueDate: kr.dueDate,
    verifier: kr.verifier,
    owner: kr.owner,
    dataSource: kr.dataSource ?? undefined,
    formulaText: (conf.formulas as Record<string, string>)[id],
    confidence: kr.confidence ?? undefined,
    progress: krProgress(kr),
    status: o.status,
    riskFlag: kr.riskFlag,
    checkinFrequency: 'TUAN',
    lastCheckin:
      last && last.value !== null
        ? { value: last.value, confidence: last.confidence, checkinAt: last.checkinAt }
        : undefined,
    myDraftCheckin: checkinState().drafts.get(id),
    nextCheckinDue: conf.due,
    rowVersion: 1,
    objective: { id: o.id, code: o.code, title: o.title },
    linkedWork: kr.linkedWork,
    linkedTaskCount: kr.linkedTaskCount,
  };
  return ok(body, { ETag: '"1"' });
}

function sourceValue(objectives: Record<string, Obj[]>, id: string): MockResponse {
  const found = findKr(objectives, id);
  if (!found) return problem(404, 'Không tìm thấy kết quả then chốt');
  const src = (conf.sources as Record<string, { value: number; fetchedAt: string }>)[id];
  if (!found.kr.dataSource || !src) {
    return problem(404, 'KR không có nguồn số liệu');
  }
  if (sourceFails()) {
    return problem(
      502,
      `Không lấy được số liệu từ ${found.kr.dataSource}. Đồng chí có thể nhập tay.`,
      {
        code: 'OKR-502-SOURCE-UNAVAILABLE',
      },
    );
  }
  return ok({ ...src, dataSource: found.kr.dataSource });
}

const pct = (n: number) => Math.round(n * 100);

/** Cờ rủi ro sau một lần check-in (QT-03 Hình 7.7, BR-03-06). */
function nextRiskFlag(id: string, current: Kr['riskFlag'], low: boolean): Kr['riskFlag'] {
  const s = checkinState();
  if (low) {
    s.calmStreak.set(id, 0);
    return current === 'BINH_THUONG' ? 'THEO_DOI' : 'RUI_RO';
  }
  const calm = (s.calmStreak.get(id) ?? 0) + 1;
  s.calmStreak.set(id, calm);
  if (current === 'THEO_DOI') return 'BINH_THUONG';
  if (current === 'RUI_RO') return calm >= 2 ? 'BINH_THUONG' : 'RUI_RO';
  return current;
}

function create(req: MockRequest, objectives: Record<string, Obj[]>): MockResponse {
  const id = req.pathParams['id'];
  const found = findKr(objectives, id);
  if (!found) return problem(404, 'Không tìm thấy kết quả then chốt');
  const { periodId, objective: o, kr } = found;
  if (!kr.viewerIsOwner) {
    return problem(403, 'Đồng chí không phải chủ sở hữu KR này nên không check-in được.', {
      code: 'OKR-403-NOT-OWNER',
    });
  }
  const period = periods.find((p) => p.id === periodId);
  if (period?.status !== 'MO') {
    return problem(409, `Kỳ ${period?.label ?? ''} đã khoá, không check-in được.`, {
      code: 'OKR-409-PERIOD-LOCKED',
    });
  }
  if (o.status !== 'DA_DUYET') {
    // BR-03-01
    return problem(409, 'Chỉ check-in được KR thuộc mục tiêu đã duyệt.', {
      code: 'OKR-409-OBJECTIVE-NOT-APPROVED',
    });
  }
  const b = (req.body ?? {}) as Partial<OkrCheckinCreate>;
  const errors: { field: string; message: string }[] = [];
  if (typeof b.value !== 'number' || !Number.isFinite(b.value)) {
    errors.push({ field: 'value', message: 'Vui lòng nhập giá trị hiện tại.' });
  }
  if (!b.confidence) errors.push({ field: 'confidence', message: 'Vui lòng chọn mức tự tin.' });
  if (errors.length) {
    return problem(422, 'Thiếu thông tin bắt buộc', { code: 'OKR-422-VALIDATION', errors });
  }
  const body = b as OkrCheckinCreate;
  if (
    typeof body.sourceValue === 'number' &&
    body.value !== body.sourceValue &&
    !body.overrideReason?.trim()
  ) {
    const msg = `Vui lòng ghi lý do khi thay đổi số liệu lấy từ ${kr.dataSource ?? 'nguồn'}.`;
    return problem(422, msg, {
      code: 'OKR-422-OVERRIDE-REASON-REQUIRED',
      errors: [{ field: 'overrideReason', message: msg }],
    });
  }
  const now = new Date().toISOString();
  const checkin: OkrCheckin = {
    id: `c-${crypto.randomUUID().slice(0, 8)}`,
    keyResultId: id,
    periodId,
    checkinAt: now,
    value: body.value,
    confidence: body.confidence,
    highlights: body.highlights || undefined,
    blockerNote: body.blockerNote || undefined,
    channel: body.channel ?? 'WEB',
    status: body.status,
    sourceValue: body.sourceValue ?? null,
    sourceFetchedAt: body.sourceFetchedAt,
    overrideReason: body.overrideReason || undefined,
  };
  const s = checkinState();
  if (body.status === 'NHAP') {
    s.drafts.set(id, checkin);
    return result(checkin, o, kr, 201);
  }
  // BR-03-05: chênh quá 50% khoảng chỉ tiêu so với lần trước thì phải xác nhận
  const prev = kr.currentValue ?? null;
  const span = Math.abs((kr.targetValue ?? 0) - (kr.baselineValue ?? 0));
  if (prev !== null && span > 0 && !body.confirmLargeChange) {
    const diff = Math.abs(body.value - prev) / span;
    if (diff > 0.5) {
      return problem(
        422,
        `Giá trị mới chênh ${pct(diff)}% so với lần trước. Đồng chí có chắc chắn không?`,
        { code: 'OKR-422-LARGE-CHANGE-UNCONFIRMED' },
      );
    }
  }
  s.drafts.delete(id);
  const list = s.history.get(id) ?? [];
  list.unshift({
    id: checkin.id,
    checkinAt: now,
    weekLabel: `Tuần ${periods.find((p) => p.id === periodId)?.weekNo ?? ''}`.trim(),
    value: body.value,
    confidence: body.confidence,
    blockerNote: body.blockerNote || null,
    highlights: body.highlights || null,
    channel: checkin.channel,
    status: 'DA_GUI',
  });
  s.history.set(id, list);
  kr.riskFlag = nextRiskFlag(id, kr.riskFlag, body.confidence === 'THAP');
  kr.currentValue = body.value;
  kr.confidence = body.confidence;
  kr.lastCheckin = { value: body.value, confidence: body.confidence, checkinAt: now };
  kr.latestBlocker = body.blockerNote || null;
  return result(checkin, o, kr, 201);
}

function result(checkin: OkrCheckin, o: Obj, kr: Kr, status: number): MockResponse {
  const body: OkrCheckinResult = {
    checkin,
    keyResult: {
      id: kr.id,
      code: kr.code,
      currentValue: kr.currentValue,
      progress: krProgress(kr),
      confidence: kr.confidence ?? undefined,
      riskFlag: kr.riskFlag,
    },
    objective: { id: o.id, code: o.code, progress: objectiveProgress(o.keyResults) },
  };
  return { status, body };
}

/** Tuyến check-in, dùng chung trạng thái mục tiêu của module okr. */
export function checkinRoutes(objectives: () => Record<string, Obj[]>): MockRoute[] {
  return [
    {
      method: 'GET',
      pattern: '/api/v1/okr/key-results/:id/checkins',
      handle: (req) => {
        const all = checkinState().history.get(req.pathParams['id']) ?? [];
        const limit = Math.min(Number(req.params.get('limit') ?? 10), 200);
        const start = Number(req.params.get('cursor') ?? 0);
        const items = all.slice(start, start + limit);
        const next = start + limit < all.length ? String(start + limit) : null;
        return ok({ items, nextCursor: next });
      },
    },
    {
      method: 'POST',
      pattern: '/api/v1/okr/key-results/:id/checkins',
      handle: (req) => create(req, objectives()),
    },
    {
      method: 'GET',
      pattern: '/api/v1/okr/key-results/:id/source-value',
      handle: (req) => sourceValue(objectives(), req.pathParams['id']),
    },
    {
      method: 'GET',
      pattern: '/api/v1/okr/key-results/:id',
      handle: (req) => detail(objectives(), req.pathParams['id']),
    },
  ];
}
