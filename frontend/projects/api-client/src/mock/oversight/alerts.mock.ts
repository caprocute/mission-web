import {
  ALERT_NOTE_LIMITS,
  AlertAction,
  AlertActionInput,
  AnomalyAlert,
  AnomalyAlertCounts,
  AnomalyAlertStatus,
  AnomalySeverity,
  AnomalyThresholdSet,
  AnomalyThresholdUpdate,
} from '../../lib/models/alert';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import { MOCK_ME } from '../mock-session';
import { MY_UNIT, alertFixtures, thresholdFixture } from './alerts.fixture';

/**
 * Giả lập cảnh báo bất thường của MOD-07 và ngưỡng phát hiện (QT-14, SCR-1401, WEB-044) theo
 * `oversight.yaml`, `config.yaml`. Kiểm: `If-Match` (428, 412), ghi chú 20–2.000 ký tự và trường bắt buộc
 * theo loại hành động (422), trạng thái nguồn của từng hành động (409), tách vai trò BR-14-08 (403
 * `OVERSIGHT-403-SEPARATION-OF-DUTIES`: cảnh báo về chính đơn vị của người dùng thử nghiệm). Không kiểm vai
 * trò `inspector` khi kết luận (giao diện ẩn theo ma trận, máy chủ thật kiểm bằng OPA).
 */

let store: AnomalyAlert[] = alertFixtures();
let thresholds: AnomalyThresholdSet = thresholdFixture();
let seq = 0;

const etag = (rowVersion: number) => ({ ETag: `"${rowVersion}"` });
const SEVERITY_ORDER: Record<AnomalySeverity, number> = { CAO: 0, VUA: 1, THAP: 2 };
const DAY = 86_400_000;

function ifMatch(x: { rowVersion: number }, req: MockRequest): MockResponse | null {
  const h = req.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu thông tin phiên bản dữ liệu; vui lòng tải lại trang.');
  if (h.replace(/"/g, '') !== String(x.rowVersion))
    return problem(412, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      code: 'PRECONDITION_FAILED',
    });
  return null;
}

const invalid = (field: string, message: string) =>
  problem(422, 'Dữ liệu không hợp lệ', {
    code: 'COMMON-422-VALIDATION',
    errors: [{ field, message }],
  });

const invalidState = (title: string) =>
  problem(409, title, { code: 'OVERSIGHT-409-INVALID-STATE' });

/** Thẻ phân bố 90 ngày: đếm cảnh báo mới và hành động xử lý trong cửa sổ. */
function counts(): AnomalyAlertCounts {
  const since = Date.now() - 90 * DAY;
  const recent = (iso: string) => Date.parse(iso) >= since;
  const acts = store.flatMap((a) => a.actions).filter((x) => recent(x.actedAt));
  return {
    windowDays: 90,
    newCount: store.filter((a) => recent(a.detectedAt)).length,
    validCount: acts.filter((x) => x.action === 'XAC_NHAN_HOP_LE').length,
    assignedCount: acts.filter((x) => x.action === 'GIAO_KIEM_TRA').length,
    violationCount: acts.filter((x) => x.action === 'KET_LUAN' && x.verdict === 'CO_VI_PHAM')
      .length,
  };
}

function list(req: MockRequest): MockResponse {
  const p = req.params;
  const multi = (k: string) => p.get(k)?.split(',').filter(Boolean) ?? [];
  const signal = multi('signal');
  const severity = multi('severity');
  const status = multi('status');
  const period = p.get('period');
  const limit = Math.min(Number(p.get('limit') ?? 20), 200);
  const start = Number(p.get('cursor') ?? 0);
  const all = store
    .filter((a) => !signal.length || signal.includes(a.signalCode))
    .filter((a) => !severity.length || severity.includes(a.severity))
    .filter((a) => !status.length || status.includes(a.status))
    .filter((a) => !period || a.period?.id === period)
    .sort(
      (a, b) =>
        SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity] ||
        b.detectedAt.localeCompare(a.detectedAt),
    );
  const page = all.slice(start, start + limit).map((a) => ({
    id: a.id,
    severity: a.severity,
    signalCode: a.signalCode,
    subject: a.subject,
    period: a.period,
    status: a.status,
    detectedAt: a.detectedAt,
  }));
  return ok({
    items: page,
    nextCursor: start + limit < all.length ? String(start + limit) : null,
    counts: counts(),
  });
}

/** Kỳ của cảnh báo (căn cứ `CANH_BAO` của yêu cầu mở lại kỳ phải thuộc kỳ đã chọn). */
export function alertPeriodOf(id: string): string | undefined {
  return store.find((a) => a.id === id)?.period?.id;
}

function find(req: MockRequest): AnomalyAlert | MockResponse {
  return (
    store.find((a) => a.id === req.pathParams['id']) ?? problem(404, 'Không tìm thấy cảnh báo')
  );
}
const isResponse = (x: AnomalyAlert | MockResponse): x is MockResponse => !('rowVersion' in x);
const out = (a: AnomalyAlert) => ok(structuredClone(a), etag(a.rowVersion));

/** Lần mở đầu của người xử lý chuyển `MOI` sang `DANG_XEM` (bước 4 QT-14). */
function get(req: MockRequest): MockResponse {
  const a = find(req);
  if (isResponse(a)) return a;
  if (a.status === 'MOI') {
    a.status = 'DANG_XEM';
    a.rowVersion++;
  }
  return out(a);
}

const FROM: Record<AlertActionInput['type'], AnomalyAlertStatus[]> = {
  XAC_NHAN_HOP_LE: ['DANG_XEM'],
  GIAO_KIEM_TRA: ['DANG_XEM'],
  KET_LUAN: ['DA_GIAO_KIEM_TRA'],
  GHI_CHU: ['MOI', 'DANG_XEM', 'DA_GIAO_KIEM_TRA'],
};

function act(req: MockRequest): MockResponse {
  const a = find(req);
  if (isResponse(a)) return a;
  const pre = ifMatch(a, req);
  if (pre) return pre;
  const b = (req.body ?? {}) as Partial<AlertActionInput>;
  if (!b.type || !(b.type in FROM)) return invalid('type', 'Chọn hành động xử lý.');
  const note = (b.note ?? '').trim();
  if (note.length < ALERT_NOTE_LIMITS.min || note.length > ALERT_NOTE_LIMITS.max)
    return invalid(
      'note',
      `Ghi chú xử lý cần từ ${ALERT_NOTE_LIMITS.min} đến ${ALERT_NOTE_LIMITS.max.toLocaleString('vi-VN')} ký tự.`,
    );
  if (b.type === 'GIAO_KIEM_TRA' && !b.assignedTo)
    return invalid('assignedTo', 'Chọn cán bộ cơ quan kiểm tra được giao.');
  if (b.type === 'KET_LUAN' && !b.verdict)
    return invalid('verdict', 'Chọn kết luận có hoặc không vi phạm.');
  if (!FROM[b.type].includes(a.status))
    return invalidState('Cảnh báo không còn ở bước cho phép thao tác này.');
  if (b.type !== 'GHI_CHU' && a.subject.type === 'DON_VI' && a.subject.id === MY_UNIT.id)
    return problem(403, 'Không được xử lý cảnh báo', {
      code: 'OVERSIGHT-403-SEPARATION-OF-DUTIES',
      detail:
        'Đồng chí không thể xử lý cảnh báo này vì đơn vị của đồng chí là đối tượng của cảnh báo.',
    });

  const now = new Date().toISOString();
  const me = { id: MOCK_ME.id, name: MOCK_ME.name };
  const action: AlertAction = {
    id: `aa-new-${++seq}`,
    action: b.type,
    actor: me,
    note,
    actedAt: now,
  };
  switch (b.type) {
    case 'XAC_NHAN_HOP_LE':
      // gỡ cờ chặn của hành động tự động (BR-14-04), cảnh báo HOP_LE rồi DONG
      a.actions = a.actions.map((x) =>
        x.action === 'TU_DONG' && x.active ? { ...x, active: false } : x,
      );
      a.status = 'DONG';
      break;
    case 'GIAO_KIEM_TRA':
      action.assignedTo = { id: b.assignedTo!, name: personName(b.assignedTo!) };
      a.status = 'DA_GIAO_KIEM_TRA';
      break;
    case 'KET_LUAN':
      action.verdict = b.verdict!;
      a.status = b.verdict!;
      break;
  }
  a.actions = [...a.actions, action];
  a.rowVersion++;
  return out(a);
}

const PERSON_NAMES: Record<string, string> = {
  'p-dung': 'Hoàng Văn Dũng',
  'p-chau': 'Lê Minh Châu',
  'p-an': 'Nguyễn Văn An',
  'p-binh': 'Trần Thị Bình',
  'p-ha': 'Phạm Thu Hà',
  'p-tuan': 'Đỗ Minh Tuấn',
  'p-lan': 'Ngô Thị Lan',
  'p-hung': 'Vũ Quang Hùng',
  [MOCK_ME.id]: MOCK_ME.name,
};
const personName = (id: string) => PERSON_NAMES[id] ?? 'Cán bộ cơ quan kiểm tra';

function getThresholds(): MockResponse {
  return ok(structuredClone(thresholds), etag(thresholds.rowVersion));
}

function putThresholds(req: MockRequest): MockResponse {
  const pre = ifMatch(thresholds, req);
  if (pre) return pre;
  const b = (req.body ?? {}) as Partial<AnomalyThresholdUpdate>;
  if ((b.reason ?? '').trim().length < 20)
    return invalid('reason', 'Lý do thay đổi cần ít nhất 20 ký tự.');
  if (!b.signals?.length) return invalid('signals', 'Thiếu ngưỡng của các dấu hiệu.');
  for (const s of b.signals)
    for (const [k, v] of Object.entries(s.params))
      if (!Number.isFinite(v) || v < 0)
        return invalid(`signals.${s.signalCode}.${k}`, 'Ngưỡng phải là số không âm.');
  thresholds = {
    version: thresholds.version + 1,
    reason: b.reason!.trim(),
    updatedAt: new Date().toISOString(),
    updatedBy: { id: MOCK_ME.id, name: MOCK_ME.name },
    signals: structuredClone(b.signals),
    rowVersion: thresholds.rowVersion + 1,
  };
  return getThresholds();
}

const B = '/api/v1/oversight/alerts';

export const ALERTS_MOCK: MockModule = {
  name: 'oversight-alerts',
  routes: [
    { method: 'GET', pattern: B, handle: list },
    { method: 'GET', pattern: `${B}/:id`, handle: get },
    { method: 'POST', pattern: `${B}/:id/actions`, handle: act },
    { method: 'GET', pattern: '/api/v1/config/anomaly-thresholds', handle: getThresholds },
    { method: 'PUT', pattern: '/api/v1/config/anomaly-thresholds', handle: putThresholds },
  ],
  reset: () => {
    store = alertFixtures();
    thresholds = thresholdFixture();
    seq = 0;
  },
};
