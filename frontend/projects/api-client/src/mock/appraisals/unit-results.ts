import {
  RATING_LEVEL_LABEL,
  RatingLevel,
  UnitAppraisalResultRow,
} from '../../lib/models/appraisal';
import { CURRENT_PERIOD, UNITS, people } from '../analytics/analytics.fixture';
import { MockRequest, MockResponse, MockRoute, ok, problem } from '../mock-router';

/**
 * Giả lập `GET /appraisals/unit-results` (AP-09; bước đơn vị của SCR-1603, HT-39). Cán bộ, điểm và mức lấy
 * từ dữ liệu mẫu QT-16 của bảng điều hành (`analytics.fixture.ts`), nên mã ảnh chụp điểm của mỗi dòng trùng
 * mã mắt xích `DIEM` của chuỗi truy vết giả lập. Kỳ hiện hành (Quý IV/2026) chưa quyết định: chỉ có mức hệ
 * thống; các kỳ trước đã khoá và có mức quyết định. Đơn vị lạ hoặc kỳ lạ trả danh sách rỗng.
 */

const CODE_OF = Object.fromEntries(
  Object.entries(RATING_LEVEL_LABEL).map(([code, label]) => [label, code as RatingLevel]),
) as Record<string, RatingLevel>;

const personId = (scoreId: string) => `per-${scoreId.replace(/^ss-\d{4}q\d-/, '')}`;

export function unitResults(unitId: string, periodId: string): UnitAppraisalResultRow[] {
  const unit = UNITS.find((u) => u.id === unitId);
  if (!unit) return [];
  const current = periodId === CURRENT_PERIOD;
  return people(periodId)
    .filter((p) => p.unitId === unitId)
    .map((p) => {
      const code = CODE_OF[p.rating] ?? null;
      return {
        profileId: `hs-${p.scoreId.replace(/^ss-/, '')}`,
        person: { id: personId(p.scoreId), name: p.name },
        orgUnit: { id: unit.id, name: unit.name },
        flow: unit.flow as UnitAppraisalResultRow['flow'],
        status: current ? 'CHO_QUYET_DINH' : 'DA_KHOA',
        scoreSnapshotId: p.scoreId,
        totalScore: p.score,
        autoRatingCode: code,
        ratingCode: current ? null : code,
      };
    });
}

function list(req: MockRequest): MockResponse {
  const unit = req.params.get('unit');
  const period = req.params.get('period');
  if (!unit || !period) {
    return problem(422, 'Thiếu đơn vị hoặc kỳ đánh giá', {
      code: 'COMMON-422-VALIDATION',
      errors: [
        ...(!unit ? [{ field: 'unit', message: 'Chọn đơn vị.' }] : []),
        ...(!period ? [{ field: 'period', message: 'Chọn kỳ đánh giá.' }] : []),
      ],
    });
  }
  const flow = req.params.get('flow');
  const items = unitResults(unit, period).filter((r) => !flow || r.flow === flow);
  const limit = Math.min(Number(req.params.get('limit') ?? 50), 200);
  const start = Number(req.params.get('cursor') ?? 0);
  return ok({
    items: structuredClone(items.slice(start, start + limit)),
    nextCursor: start + limit < items.length ? String(start + limit) : null,
  });
}

export function unitResultRoutes(): MockRoute[] {
  return [{ method: 'GET', pattern: '/api/v1/appraisals/unit-results', handle: list }];
}
