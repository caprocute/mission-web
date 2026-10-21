import type { AiSuggestion } from '../../lib/models/analytics';
import type {
  AiUserAction,
  OkrAssistRequest,
  OkrAssistResult,
  OkrAssistSuggestion,
} from '../../lib/models/ai-assist';
import type { Job } from '../../lib/models/job';
import type { ObjectiveDetail } from '../../lib/models/okr';
import { MockRequest, MockResponse, MockRoute, ok, problem } from '../mock-router';

/**
 * Giả lập trợ lý AI soạn OKR của SVC-14 (QT-17, SCR-1701, WEB-049) theo `platform.yaml`.
 *
 * Câu hỏi có "soạn", "mục tiêu" hoặc "OKR" nhận hai mục tiêu gợi ý (5 KR) bám kế hoạch công tác; mọi câu hỏi
 * kèm bản nháp được soát KR: KR mơ hồ không có chỉ tiêu (ví dụ KR 3.3 "Tăng cường công tác kiểm tra, giám sát"
 * của bản nháp O3) nhận gợi ý viết lại (MSG-17-01), KR về tỷ lệ đảng viên tham gia nhận lưu ý trùng chỉ tiêu với
 * Văn phòng Đảng uỷ khối. Câu hỏi về chấm điểm, xếp loại bị rào chắn chặn (MSG-17-02). URL trang có
 * `?mock-ai=ban` thì lần hỏi đầu trả 202 (MSG-17-03) và kết quả có ở lần đọc job đầu tiên.
 */

const MODEL = 'viettel-llm-vi-2026.3';
const LABEL = 'Nội dung do AI tạo' as const;
export const MSG_17_02 =
  'Trợ lý không thực hiện chấm điểm hoặc đề xuất xếp loại. Việc này thuộc thẩm quyền của cán bộ và cấp có thẩm quyền theo quy định.';
const VAGUE = /tăng cường|nâng cao|đẩy mạnh|tích cực|chú trọng/i;

interface Stored {
  s: OkrAssistSuggestion;
  decision: AiUserAction | null;
}

const suggestions = new Map<string, Stored>();
const results = new Map<string, OkrAssistResult>();
let seq = 0;
let queuedOnce = false;
const nextId = () => `0192f7b9-a213-74c5-d6e7-${String(++seq).padStart(12, '0')}`;
const pageQuery = () => new URLSearchParams(globalThis.location?.search ?? '');

function remember(s: OkrAssistSuggestion): OkrAssistSuggestion {
  suggestions.set(s.id, { s, decision: null });
  return s;
}

function objectives(): OkrAssistSuggestion[] {
  const plan = { kind: 'VAN_BAN' as const, title: 'Kế hoạch công tác năm 2027 của Đảng uỷ khối' };
  return [
    remember({
      id: nextId(),
      kind: 'MUC_TIEU',
      objective: {
        title: 'Nâng chất lượng sinh hoạt chi bộ khối cơ quan',
        basisRef: 'Kế hoạch công tác năm 2027, mục II.2',
        keyResults: [
          {
            title: '100% chi bộ sinh hoạt chuyên đề ít nhất 1 lần/quý',
            unit: '%',
            targetValue: 100,
            weight: 0.4,
          },
          {
            title: 'Kiểm tra 100% chi bộ trực thuộc trước 31/3/2027',
            unit: '%',
            targetValue: 100,
            weight: 0.3,
          },
          {
            title: '≥ 90% đảng viên tham gia đầy đủ sinh hoạt',
            unit: '%',
            targetValue: 90,
            weight: 0.3,
          },
        ],
      },
      reason: 'Bám nhiệm vụ trọng tâm về nâng chất lượng sinh hoạt chi bộ.',
      sources: [{ ...plan, location: 'mục II.2, tr. 4–5' }],
    }),
    remember({
      id: nextId(),
      kind: 'MUC_TIEU',
      objective: {
        title: 'Hoàn thành kiểm điểm, đánh giá cuối năm đúng quy định',
        basisRef: 'Kế hoạch công tác năm 2027, mục III.1',
        keyResults: [
          {
            title: '100% hồ sơ kiểm điểm nộp trước 15/01/2027',
            unit: '%',
            targetValue: 100,
            weight: 0.6,
          },
          {
            title: 'Tổ chức 1 hội nghị sơ kết công tác tổ chức xây dựng Đảng',
            unit: 'lần',
            targetValue: 1,
            weight: 0.4,
          },
        ],
      },
      reason: 'Bám mục III.1 về đánh giá, xếp loại chất lượng cuối năm.',
      sources: [{ ...plan, location: 'mục III.1, tr. 6' }],
    }),
  ];
}

function review(drafts: ObjectiveDetail[]): OkrAssistSuggestion[] {
  const out: OkrAssistSuggestion[] = [];
  for (const o of drafts) {
    for (const k of o.keyResults) {
      if (VAGUE.test(k.title) && k.targetValue === null) {
        out.push(
          remember({
            id: nextId(),
            kind: 'SOAT_KR',
            krCode: k.code,
            text: 'Kiểm tra 100% chi bộ trực thuộc trước 31/3/2027',
            reason:
              'Cụm từ chỉ mức độ không đo được và giống mô tả nhiệm vụ; KR cần chỉ tiêu, đơn vị đo và thời hạn.',
            sources: [
              { kind: 'VAN_BAN', title: 'Dự thảo Phụ lục 1', location: 'mục 3.2a' },
              { kind: 'DU_LIEU', title: 'Danh sách 18 chi bộ trực thuộc' },
            ],
          }),
        );
      } else if (/đảng viên tham gia/i.test(k.title)) {
        out.push(
          remember({
            id: nextId(),
            kind: 'LIEN_KET_NGANG',
            krCode: k.code,
            text: `${k.code} trùng chỉ tiêu với một KR của Văn phòng Đảng uỷ khối. Đề nghị liên kết ngang để tránh tính hai lần.`,
            sources: [{ kind: 'DU_LIEU', title: 'OKR quý của Văn phòng Đảng uỷ khối' }],
          }),
        );
      }
    }
  }
  return out;
}

function answer(
  body: OkrAssistRequest,
  lookup: (id: string) => ObjectiveDetail | undefined,
): OkrAssistResult {
  const base = {
    model: MODEL,
    createdAt: new Date().toISOString(),
    aiGenerated: true as const,
    label: LABEL,
  };
  if (/chấm điểm|xếp loại|mức xếp/i.test(body.question))
    return {
      ...base,
      suggestions: [],
      blocked: { ruleCode: 'OUT_OF_SCOPE', action: 'CHAN', msg: 'MSG-17-02', message: MSG_17_02 },
    };
  const drafts = (body.draftObjectiveIds ?? []).map(lookup).filter((d) => !!d);
  const goals = /soạn|mục tiêu|okr/i.test(body.question) ? objectives() : [];
  const checks = review(drafts);
  const krs = goals.reduce((a, g) => a + (g.objective?.keyResults?.length ?? 0), 0);
  const parts = [
    goals.length
      ? `Đề xuất ${goals.length} mục tiêu và ${krs} KR bám mục II.2 và III.1 của Kế hoạch công tác năm 2027.`
      : '',
    checks.length
      ? `Đã soát bản nháp: ${checks.length} điểm cần xem lại.`
      : drafts.length
        ? 'Đã soát bản nháp: các KR đều có chỉ tiêu đo được.'
        : '',
  ].filter(Boolean);
  return {
    ...base,
    answer:
      parts.join(' ') || 'Đồng chí có thể hỏi trợ lý soạn mục tiêu hoặc soát KR của bản nháp.',
    suggestions: [...goals, ...checks],
    blocked: null,
  };
}

const jobs = new Map<string, string>();

export function assistRoutes(lookup: (id: string) => ObjectiveDetail | undefined): MockRoute[] {
  return [
    {
      method: 'POST',
      pattern: '/api/v1/ai/assist/okr',
      handle: (req) => {
        const body = (req.body ?? {}) as OkrAssistRequest;
        const q = body.question?.trim() ?? '';
        if (!q || q.length > 2000 || !body.periodId)
          return problem(422, 'Dữ liệu không hợp lệ', {
            errors: [{ field: 'question', message: 'Câu hỏi từ 1 đến 2.000 ký tự.' }],
          });
        const result = answer({ ...body, question: q }, lookup);
        if (pageQuery().get('mock-ai') === 'ban' && !queuedOnce) {
          queuedOnce = true;
          const id = `ai-job-${++seq}`;
          results.set(id, result);
          jobs.set(id, id);
          const job: Job = { id, type: 'AI_ASSIST', status: 'CHO', message: 'Trợ lý đang bận' };
          return {
            status: 202,
            body: job,
            headers: { Location: `/api/v1/ai/assist/okr/jobs/${id}` },
          };
        }
        return ok(result);
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/ai/assist/okr/jobs/:id',
      handle: (req) => {
        const id = req.pathParams['id'];
        if (!jobs.has(id)) return problem(404, 'Không tìm thấy tác vụ');
        return ok({
          id,
          type: 'AI_ASSIST',
          status: 'HOAN_TAT',
          progress: 100,
          resultLocation: `/api/v1/ai/assist/okr/results/${id}`,
        } satisfies Job);
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/ai/assist/okr/results/:id',
      handle: (req) => {
        const r = results.get(req.pathParams['id']);
        return r ? ok(structuredClone(r)) : problem(404, 'Không tìm thấy kết quả');
      },
    },
  ];
}

/**
 * Quyết định với gợi ý của trợ lý OKR (`POST /ai/suggestions/{id}/decision`); trả `undefined` khi mã không
 * thuộc trợ lý OKR để tuyến chung chuyển sang gợi ý trích nhiệm vụ.
 */
export function okrAssistDecision(req: MockRequest): MockResponse | undefined {
  const hit = suggestions.get(req.pathParams['id']);
  if (!hit) return undefined;
  const b = (req.body ?? {}) as { decision?: AiUserAction; targetRef?: string };
  if (b.decision !== 'CHAP_NHAN' && b.decision !== 'SUA' && b.decision !== 'BO_QUA')
    return problem(422, 'Quyết định không hợp lệ');
  if (hit.decision)
    return problem(409, 'Gợi ý này đã được xử lý', { code: 'AI-409-ALREADY-DECIDED' });
  hit.decision = b.decision;
  const body: AiSuggestion = {
    id: hit.s.id,
    useCase: 'GOI_Y_OKR',
    kind: hit.s.kind,
    model: MODEL,
    createdAt: new Date().toISOString(),
    aiGenerated: true,
    label: LABEL,
    outputText: hit.s.text ?? hit.s.objective?.title ?? null,
    sources: hit.s.sources,
    decision: b.decision,
  };
  return ok(body);
}

export function resetOkrAssist(): void {
  suggestions.clear();
  results.clear();
  jobs.clear();
  seq = 0;
  queuedOnce = false;
}
