import {
  KeyResultDraft,
  OkrComposeContext,
  OkrPolicy,
  OkrRule,
  OkrRuleSeverity,
} from '@vt/api-client';
import { evaluateRules } from '@vt/form-engine';

/**
 * Soát OKR tức thì trên trình duyệt (QT-02 bước 4) bằng quy tắc CEL của Policy Pack, chạy qua
 * `evaluateRules` của `@vt/form-engine`. Máy chủ chạy lại toàn bộ khi trình (AGENTS.md quy tắc 6).
 *
 * cel-js trên trình duyệt chưa có hàm chuỗi (`contains`, `matches`, `lowerAscii`), nên việc dò từ
 * mơ hồ (BR-02-05), động từ nhiệm vụ (BR-02-06) và danh sách trường thiếu (BR-02-04) được tính
 * trước thành biến của ngữ cảnh; biểu thức CEL chỉ so sánh các biến đó (wiki/10, bẫy cel-js).
 */

/** Dữ liệu đang soạn trên SCR-0202, trọng số theo phần trăm như ô nhập. */
export interface ComposeKr extends Omit<KeyResultDraft, 'weight'> {
  /** Khoá cục bộ cho `@for track`, kể cả KR chưa có id. */
  key: string;
  weightPct: number | null;
}

export interface ComposeForm {
  periodId: string;
  basisIds: string[];
  parentObjectiveId: string | null;
  title: string;
  overLimitReason: string | null;
  keyResults: ComposeKr[];
}

export interface RuleFinding {
  ruleId: string;
  br: string;
  severity: OkrRuleSeverity;
  message: string;
  /** Vị trí KR khi quy tắc chạy trên từng KR. */
  krIndex?: number;
}

export interface ChecklistItem {
  state: 'pass' | 'error' | 'warn';
  label: string;
}

export interface OkrCheckResult {
  errors: RuleFinding[];
  warnings: RuleFinding[];
  byKr: RuleFinding[][];
  checklist: ChecklistItem[];
}

type Vars = Record<string, string | number | boolean>;

const lower = (s: string) => s.toLocaleLowerCase('vi').trim();

/** Từ mơ hồ đầu tiên có trong nội dung KR (BR-02-05), rỗng nếu không có. */
export function findVagueWord(title: string, policy: OkrPolicy): string {
  const t = lower(title);
  return policy.vagueWords.find((w) => t.includes(lower(w))) ?? '';
}

/** Động từ nhiệm vụ mở đầu nội dung KR (BR-02-06), rỗng nếu không có. */
export function findTaskVerb(title: string, policy: OkrPolicy): string {
  const t = lower(title);
  return policy.taskVerbs.find((v) => t.startsWith(lower(v) + ' ')) ?? '';
}

/** Danh sách trường bắt buộc còn thiếu của KR (BR-02-04), dạng "chỉ tiêu, hạn". */
export function missingFields(k: ComposeKr): string {
  const miss: string[] = [];
  if (!k.unit.trim()) miss.push('đơn vị đo');
  if (k.baselineValue === null) miss.push('giá trị đầu');
  if (k.targetValue === null) miss.push('chỉ tiêu');
  if (!k.dueDate) miss.push('hạn');
  if (!k.verifierId) miss.push('người xác nhận');
  return miss.join(', ');
}

export function totalWeightPct(form: ComposeForm): number {
  return Math.round(form.keyResults.reduce((s, k) => s + (k.weightPct ?? 0), 0) * 100) / 100;
}

/** Biến `o.*` cho quy tắc phạm vi mục tiêu. */
export function objectiveVars(form: ComposeForm, ctx: OkrComposeContext): Vars {
  const parent = ctx.parentOptions.find((p) => p.id === form.parentObjectiveId);
  return {
    titleLength: form.title.trim().length,
    basisCount: form.basisIds.length,
    hasParent: !!parent,
    isTopLevel: ctx.isTopLevel,
    parentApproved: parent?.status === 'DA_DUYET',
    parentCode: parent?.code ?? '',
    krCount: form.keyResults.length,
    totalWeight: totalWeightPct(form),
    totalObjectives: ctx.existingCount + 1,
    recommendedMax: ctx.policy.recommendedMax,
    overLimitReasonLength: (form.overLimitReason ?? '').trim().length,
  };
}

/** Biến `kr.*` cho quy tắc phạm vi KR. */
export function krVars(k: ComposeKr, code: string, policy: OkrPolicy): Vars {
  return {
    code,
    titleLength: k.title.trim().length,
    unit: k.unit.trim(),
    hasBaseline: k.baselineValue !== null,
    hasTarget: k.targetValue !== null,
    targetDiffers:
      k.baselineValue !== null && k.targetValue !== null && k.baselineValue !== k.targetValue,
    hasDue: !!k.dueDate,
    hasVerifier: !!k.verifierId,
    vagueWord: findVagueWord(k.title, policy),
    taskVerb: findTaskVerb(k.title, policy),
    missing: missingFields(k),
  };
}

/** Điền chỗ trống `{tên}` của thông báo bằng biến cùng tên. */
export function fillMessage(template: string, vars: Vars): string {
  return template.replace(/\{(\w+)\}/g, (m, name: string) =>
    name in vars ? String(vars[name]) : m,
  );
}

function run(rules: OkrRule[], scopeName: 'o' | 'kr', vars: Vars, krIndex?: number): RuleFinding[] {
  const byId = new Map(rules.map((r) => [r.id, r]));
  return evaluateRules(rules, { [scopeName]: vars }).map((v) => {
    const r = byId.get(v.ruleId)!;
    return {
      ruleId: r.id,
      br: r.br,
      severity: r.severity,
      message: fillMessage(r.message, vars),
      ...(krIndex === undefined ? {} : { krIndex }),
    };
  });
}

/**
 * Chạy toàn bộ quy tắc của Policy Pack trên bản đang soạn. `krCode(i)` cho mã hiển thị của KR
 * thứ i (`KR {o}.{n}`). Danh sách kiểm tra gồm nhãn "Đạt" của quy tắc có `passLabel` không bị
 * vi phạm, rồi các lỗi chặn và cảnh báo.
 */
export function checkOkr(
  form: ComposeForm,
  ctx: OkrComposeContext,
  krCode: (i: number) => string,
): OkrCheckResult {
  const rules = ctx.policy.rules;
  const oRules = rules.filter((r) => r.scope === 'objective');
  const kRules = rules.filter((r) => r.scope === 'kr');
  const findings = run(oRules, 'o', objectiveVars(form, ctx));
  const byKr = form.keyResults.map((k, i) =>
    run(kRules, 'kr', krVars(k, krCode(i), ctx.policy), i),
  );
  const all = [...findings, ...byKr.flat()];
  const failed = new Set(all.map((f) => f.ruleId));
  const checklist: ChecklistItem[] = [
    ...rules
      .filter((r) => r.passLabel && !r.serverOnly && !failed.has(r.id))
      .map((r) => ({ state: 'pass' as const, label: r.passLabel! })),
    ...all
      .filter((f) => f.severity === 'CHAN')
      .map((f) => ({ state: 'error' as const, label: f.message })),
    ...all
      .filter((f) => f.severity === 'CANH_BAO')
      .map((f) => ({ state: 'warn' as const, label: f.message })),
  ];
  return {
    errors: all.filter((f) => f.severity === 'CHAN'),
    warnings: all.filter((f) => f.severity === 'CANH_BAO'),
    byKr,
    checklist,
  };
}

/** Chia đều 100% cho n KR (mặc định trường 12), phần lẻ dồn vào KR đầu. */
export function evenWeights(n: number): number[] {
  if (n <= 0) return [];
  const base = Math.floor(100 / n);
  return Array.from({ length: n }, (_, i) => (i === 0 ? 100 - base * (n - 1) : base));
}
