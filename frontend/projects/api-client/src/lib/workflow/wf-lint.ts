/**
 * Bộ luật kiểm tra mẫu luồng BPMN 2.0 khi soạn (SCR-1505, BS-01 "Kiểm tra khi soạn", "Giới hạn tải";
 * TL-01 "Quy tắc quản trị chống mạng nhện" 1–4 và 6). Chạy trên trình duyệt sau mỗi thay đổi để phản hồi
 * nhanh; máy chủ và CI repo mẫu luồng chạy lại cùng bộ luật và là nơi quyết định. Tệp không phụ thuộc
 * Angular để đóng gói dùng chung.
 */

export type WfLintRule =
  | 'khong-gan-tenant'
  | 'mot-tang-call-activity'
  | 'khong-phat-signal'
  | 'chi-external-worker'
  | 'phan-tu-ngoai-bang-mau'
  | 'toi-da-user-task-moi-nhanh'
  | 'da-the-hien-co-gioi-han';

export interface WfLintIssue {
  rule: WfLintRule;
  /** `id` của phần tử BPMN để tô đỏ trên sơ đồ; rỗng khi lỗi thuộc cả tệp. */
  elementId: string;
  message: string;
}

export interface WfLintOptions {
  /** Mẫu được gọi bởi call activity có call activity của riêng nó không (tra thư viện mẫu). */
  calledHasCallActivity?: (calledElement: string) => boolean;
}

/** BS-01: tối đa 12 user task trên một nhánh (đề xuất, đo lại bằng thử tải TL-04). */
export const MAX_USER_TASKS_PER_BRANCH = 12;
/** BS-01: tối đa 50 phần tử đa thể hiện mỗi lần. */
export const MAX_MULTI_INSTANCE = 50;
/** TL-01 quy tắc 6: đa thể hiện song song trên cùng execution cha không quá 20 phần tử. */
export const MAX_PARALLEL_MULTI_INSTANCE = 20;

/** Phần tử luồng nằm ngoài bảng màu được phép của BS-01. */
const OUTSIDE_PALETTE: Record<string, string> = {
  scriptTask: 'tác vụ chạy kịch bản',
  sendTask: 'tác vụ gửi tin',
  receiveTask: 'tác vụ chờ tin',
  manualTask: 'tác vụ thủ công',
  businessRuleTask: 'tác vụ luật nghiệp vụ',
  inclusiveGateway: 'cổng rẽ nhánh bao hàm',
  eventBasedGateway: 'cổng rẽ nhánh theo sự kiện',
  complexGateway: 'cổng rẽ nhánh phức hợp',
  transaction: 'giao dịch',
  adHocSubProcess: 'tiến trình con tự do',
};

const local = (e: Element) => e.localName;
const all = (root: Element | Document): Element[] => Array.from(root.getElementsByTagName('*'));
const nameOf = (e: Element) => e.getAttribute('name')?.trim() || e.getAttribute('id') || '';
const attrLocal = (e: Element, name: string) =>
  Array.from(e.attributes).find((a) => a.localName === name)?.value ?? null;

/** Phân tích và kiểm tra; XML không hợp lệ trả một lỗi chung. */
export function lintBpmn(xml: string, opts: WfLintOptions = {}): WfLintIssue[] {
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  if (
    doc.getElementsByTagName('parsererror').length ||
    local(doc.documentElement) !== 'definitions'
  ) {
    return [
      {
        rule: 'phan-tu-ngoai-bang-mau',
        elementId: '',
        message: 'Tệp sơ đồ không đọc được. Vui lòng kiểm tra lại nội dung.',
      },
    ];
  }
  const els = all(doc);
  const issues: WfLintIssue[] = [];
  const push = (rule: WfLintRule, e: Element | null, message: string) =>
    issues.push({ rule, elementId: e?.getAttribute('id') ?? '', message });

  for (const e of els) {
    const tag = local(e);
    // Quy tắc 1: một định nghĩa dùng chung, không gắn tenant.
    if (attrLocal(e, 'tenantId') !== null)
      push(
        'khong-gan-tenant',
        e,
        'Mẫu dùng chung cho mọi đơn vị, không được gắn riêng một đơn vị.',
      );

    // Quy tắc 2: tối đa một tầng call activity.
    if (tag === 'callActivity') {
      const nested = closest(e, 'subProcess');
      const called = e.getAttribute('calledElement') ?? '';
      if (nested || (called && opts.calledHasCallActivity?.(called)))
        push(
          'mot-tang-call-activity',
          e,
          `Bước "${nameOf(e)}" gọi một quy trình con lồng tiếp; chỉ được gọi quy trình con một tầng.`,
        );
    }

    // Quy tắc 3: không phát signal rộng.
    if (tag === 'signalEventDefinition') {
      const owner = e.parentElement;
      if (owner && /ThrowEvent$|^endEvent$/.test(local(owner)))
        push(
          'khong-phat-signal',
          owner,
          `Bước "${nameOf(owner)}" phát tín hiệu tới mọi quy trình; tác động hàng loạt phải đi qua sự kiện của lõi.`,
        );
    }

    // Quy tắc 4: tác vụ máy chỉ là external worker.
    if (tag === 'serviceTask' && attrLocal(e, 'type') !== 'external-worker')
      push(
        'chi-external-worker',
        e,
        `Bước "${nameOf(e)}" gọi dịch vụ trực tiếp; tác vụ của máy phải là tác vụ nhận việc từ hàng đợi.`,
      );

    if (OUTSIDE_PALETTE[tag])
      push(
        'phan-tu-ngoai-bang-mau',
        e,
        `Bước "${nameOf(e)}" dùng ${OUTSIDE_PALETTE[tag]}, không có trong bảng phần tử được phép.`,
      );

    if (tag === 'multiInstanceLoopCharacteristics') {
      const owner = e.parentElement!;
      const card = Array.from(e.children).find((c) => local(c) === 'loopCardinality');
      const n = card ? Number(card.textContent?.trim()) : NaN;
      const parallel = e.getAttribute('isSequential') !== 'true';
      if (!Number.isInteger(n) || n < 1)
        push(
          'da-the-hien-co-gioi-han',
          owner,
          `Bước "${nameOf(owner)}" lặp không có giới hạn số lần; cần khai báo số lần cố định.`,
        );
      else if (n > MAX_MULTI_INSTANCE)
        push(
          'da-the-hien-co-gioi-han',
          owner,
          `Bước "${nameOf(owner)}" lặp ${n} lần, vượt giới hạn ${MAX_MULTI_INSTANCE} lần.`,
        );
      else if (parallel && n > MAX_PARALLEL_MULTI_INSTANCE)
        push(
          'da-the-hien-co-gioi-han',
          owner,
          `Bước "${nameOf(owner)}" chạy song song ${n} lần, vượt giới hạn ${MAX_PARALLEL_MULTI_INSTANCE} lần chạy song song.`,
        );
    }
  }

  for (const proc of els.filter((e) => local(e) === 'process' || local(e) === 'subProcess')) {
    const worst = maxUserTasksOnBranch(proc);
    if (worst.count > MAX_USER_TASKS_PER_BRANCH)
      push(
        'toi-da-user-task-moi-nhanh',
        worst.last,
        `Một nhánh có ${worst.count} bước người xử lý, vượt giới hạn ${MAX_USER_TASKS_PER_BRANCH} bước.`,
      );
  }
  return issues;
}

function closest(e: Element, tag: string): Element | null {
  for (let p = e.parentElement; p; p = p.parentElement) if (local(p) === tag) return p;
  return null;
}

/** Số user task lớn nhất trên một đường đi từ sự kiện bắt đầu; bỏ qua cạnh quay vòng. */
function maxUserTasksOnBranch(scope: Element): { count: number; last: Element | null } {
  const nodes = new Map<string, Element>();
  const out = new Map<string, string[]>();
  for (const c of Array.from(scope.children)) {
    const id = c.getAttribute('id');
    if (!id) continue;
    if (local(c) === 'sequenceFlow') {
      const s = c.getAttribute('sourceRef') ?? '';
      out.set(s, [...(out.get(s) ?? []), c.getAttribute('targetRef') ?? '']);
    } else nodes.set(id, c);
  }
  let best = { count: 0, last: null as Element | null };
  const onPath = new Set<string>();
  const walk = (id: string, count: number, last: Element | null) => {
    const n = nodes.get(id);
    if (!n || onPath.has(id)) return;
    if (local(n) === 'userTask') {
      count++;
      last = n;
    }
    if (count > best.count) best = { count, last };
    if (count > MAX_USER_TASKS_PER_BRANCH * 4) return;
    onPath.add(id);
    for (const t of out.get(id) ?? []) walk(t, count, last);
    onPath.delete(id);
  };
  for (const [id, n] of nodes) if (local(n) === 'startEvent') walk(id, 0, null);
  return best;
}
