import { Job } from '../../lib/models/job';
import {
  Confidence,
  KeyResult,
  KrCheckin,
  Objective,
  OkrPeerLink,
  OkrPeriod,
  OkrTree,
} from '../../lib/models/okr';
import { krProgress, objectiveProgressPct } from '../../lib/okr-progress';
import { MockModule, MockRequest, ok, problem } from '../mock-router';
import fixture from './okr.json';

type KrFixture = Omit<KeyResult, 'progress'>;
type ObjectiveFixture = Omit<Objective, 'progressPct' | 'keyResults' | 'tier'> & {
  tier: number;
  keyResults: KrFixture[];
};

/** Đơn vị có cây lớn sinh tự động, để thử ảo hoá và hiệu năng của SCR-0201. */
export const OKR_LARGE_UNIT_ID = 'u-duk';

const periods = fixture.periods as OkrPeriod[];

function withProgress(o: ObjectiveFixture): Objective {
  const keyResults = o.keyResults.map((k) => ({ ...k, progress: krProgress(k) }));
  return {
    ...o,
    tier: o.tier as Objective['tier'],
    keyResults,
    progressPct: objectiveProgressPct(keyResults),
  };
}

/** Cây lớn: 3 mục tiêu tầng 1, mỗi mục tiêu 12 phòng, mỗi phòng 8 cá nhân (≈ 330 nút, ≈ 900 KR). */
function largeObjectives(): Objective[] {
  const conf: Confidence[] = ['CAO', 'TRUNG_BINH', 'THAP'];
  const out: Objective[] = [];
  const kr = (objId: string, code: string, i: number, n: number): KeyResult => {
    const target = 10 + ((i * 7) % 40);
    const current = (i * 13) % (target + 5);
    return {
      id: `${objId}-kr${n}`,
      objectiveId: objId,
      code,
      title: `Kết quả then chốt ${code}: hoàn thành ${target} sản phẩm đúng hạn`,
      unit: 'sản phẩm',
      baselineValue: 0,
      targetValue: target,
      currentValue: current,
      weight: 1 / 3,
      progress: krProgress({ baselineValue: 0, targetValue: target, currentValue: current }),
      dueDate: '2026-12-31',
      verifierName: 'Trưởng đơn vị',
      dataSource: null,
      valueNote: null,
      confidence: conf[i % 3],
      riskFlag: i % 11 === 0 ? 'RUI_RO' : 'BINH_THUONG',
      lastCheckinAt: '2026-10-16T09:00:00+07:00',
      ownerName: `Cán bộ ${i}`,
      viewerIsOwner: false,
      latestBlocker: null,
      linkedWork: [],
      linkedTaskCount: i % 5,
    };
  };
  const push = (
    id: string,
    code: string,
    title: string,
    tier: 1 | 2 | 3,
    unit: string,
    person: string | null,
    parent: string | null,
    seed: number,
  ) => {
    const keyResults = [1, 2, 3].map((n) => kr(id, `KR ${code.slice(1)}.${n}`, seed + n, n));
    out.push({
      id,
      code,
      title,
      tier,
      ownerType: person ? 'CA_NHAN' : 'TAP_THE',
      ownerUnitName: unit,
      ownerPersonName: person,
      parentObjectiveId: parent,
      status: 'DA_DUYET',
      progressPct: objectiveProgressPct(keyResults),
      approvedAt: '2026-10-08T09:00:00+07:00',
      versionNo: 1,
      overLimitReason: null,
      keyResults,
    });
  };
  for (let a = 1; a <= 3; a++) {
    push(`L${a}`, `O${a}`, `Mục tiêu ${a} của Đảng uỷ khối`, 1, 'Đảng uỷ khối', null, null, a);
    for (let b = 1; b <= 12; b++) {
      const unit = `Đảng uỷ cơ sở số ${(a - 1) * 12 + b}`;
      push(`L${a}-${b}`, `O${a}.${b}`, `Mục tiêu của ${unit}`, 2, unit, null, `L${a}`, a * 100 + b);
      for (let c = 1; c <= 8; c++) {
        push(
          `L${a}-${b}-${c}`,
          `O${a}.${b}.${c}`,
          `Mục tiêu cá nhân ${c} thuộc ${unit}`,
          3,
          unit,
          `Đảng viên ${a}.${b}.${c}`,
          `L${a}-${b}`,
          a * 1000 + b * 10 + c,
        );
      }
    }
  }
  return out;
}

function buildTree(req: MockRequest) {
  const periodId = req.params.get('period') ?? fixture.currentPeriodId;
  const period = periods.find((p) => p.id === periodId);
  if (!period) return problem(404, 'Không tìm thấy kỳ đánh giá', { detail: `Kỳ ${periodId}` });
  const unitId = req.params.get('unit') ?? fixture.unit.id;
  const large = unitId === OKR_LARGE_UNIT_ID;
  if (!large && unitId !== fixture.unit.id) {
    return problem(403, 'Đồng chí không có quyền xem mục tiêu của đơn vị này');
  }
  const byPeriod = fixture.objectives as Record<string, ObjectiveFixture[]>;
  const objectives = large
    ? period.id === fixture.currentPeriodId
      ? largeObjectives()
      : []
    : (byPeriod[period.id] ?? []).map(withProgress);
  const tree: OkrTree = {
    period,
    periods,
    unit: large ? { id: OKR_LARGE_UNIT_ID, name: 'Đảng uỷ khối' } : fixture.unit,
    viewerOwnUnit: !large,
    bases: fixture.bases as OkrTree['bases'],
    parent: large ? null : (fixture.parent as OkrTree['parent']),
    recommendedMax: fixture.recommendedMax,
    objectives,
    peerLinks: large ? [] : ((fixture.peerLinks as Record<string, OkrPeerLink[]>)[period.id] ?? []),
  };
  return ok(tree);
}

const exportJobs = new Map<string, Job>();

/**
 * Giả lập `/api/v1/okr`: KR để liên kết nhiệm vụ (WEB-028), cây mục tiêu, lịch sử check-in và
 * xuất mẫu (WEB-021, SCR-0201).
 */
export const OKR_MOCK: MockModule = {
  name: 'okr',
  reset: () => exportJobs.clear(),
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/okr/key-results',
      handle: () => ok({ items: fixture.keyResults, nextCursor: null }),
    },
    {
      method: 'GET',
      pattern: '/api/v1/okr/objectives',
      handle: (req) =>
        req.params.get('view') === 'tree'
          ? buildTree(req)
          : problem(400, 'Chế độ xem chưa hỗ trợ trong dữ liệu giả lập'),
    },
    {
      method: 'GET',
      pattern: '/api/v1/okr/key-results/:id/checkins',
      handle: (req) => {
        const all = (fixture.checkins as Record<string, KrCheckin[]>)[req.pathParams['id']] ?? [];
        const limit = Math.min(Number(req.params.get('limit') ?? 10), 200);
        const start = Number(req.params.get('cursor') ?? 0);
        const items = all.slice(start, start + limit);
        const next = start + limit < all.length ? String(start + limit) : null;
        return ok({ items, nextCursor: next });
      },
    },
    {
      method: 'POST',
      pattern: '/api/v1/okr/objectives/export',
      handle: () => {
        const id = crypto.randomUUID();
        exportJobs.set(id, {
          id,
          type: 'EXPORT',
          status: 'HOAN_TAT',
          progress: 100,
          message: 'Đã sinh tệp theo mẫu báo cáo OKR',
          resultLocation: `/api/v1/okr/exports/${id}/file`,
        });
        return { status: 202, body: null, headers: { Location: `/api/v1/okr/exports/${id}` } };
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/okr/exports/:id',
      handle: (req) => {
        const job = exportJobs.get(req.pathParams['id']);
        return job ? ok(job) : problem(404, 'Không tìm thấy tác vụ xuất mẫu');
      },
    },
  ],
};
