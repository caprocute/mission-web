import { PeriodRef } from '../../lib/models/task';
import {
  DocExtraction,
  FromDocumentRequest,
  SourceDoc,
  SourceDocStatus,
  SourceDocSummary,
  SuggestionField,
  TaskSuggestion,
  TaskSuggestionFields,
} from '../../lib/models/source-doc';
import { SimilarTask, TaskDraft } from '../../lib/models/task-assign';
import { TaskGroup } from '../../lib/models/task';
import {
  MockModule,
  MockRequest,
  MockResponse,
  ok,
  problem,
  resolveRelativeDates,
} from '../mock-router';
import catalogFixture from '../catalog/catalog.json';
import { ORG_ME, ORG_PERSONS } from '../org/org.mock';
import {
  SIMILARITY_THRESHOLD,
  addDraft,
  assignStored,
  cancelDraft,
  similarity,
} from '../tasks/assign.mock';
import taskFixture from '../tasks/tasks.json';
import fixture from './source-docs.json';
import { okrAssistDecision } from '../okr/okr-assist';

/**
 * Giả lập văn bản chỉ đạo đến và tạo nhiệm vụ từ văn bản (QT-05 bước 1–7, SCR-0502, WEB-029):
 * `/api/v1/tasks/source-docs`, `/api/v1/tasks/from-document`, `/api/v1/tasks/{oldId}/source-docs`,
 * `/api/v1/tasks/assign-batch` của MOD-02 và `/api/v1/ai/...` của SVC-14.
 * Máy chủ là nơi chặn BR-05-05 (chỉ giao nhiệm vụ đã xác nhận), BR-05-06 và BR-05-07.
 */

type StoredSuggestion = TaskSuggestion & { similarReason?: string };
type StoredDoc = SourceDoc & {
  extractedAt: string;
  suggestions: StoredSuggestion[];
  usedParagraphs: { paragraphNo: number; taskCode: string }[];
};

let docs: StoredDoc[] | null = null;

const todayIso = () => new Date().toISOString().slice(0, 10);
const period = (): PeriodRef => resolveRelativeDates(structuredClone(taskFixture.period));
const catalogItem = (id?: string) => catalogFixture.items.find((i) => i.id === id);

/** `{{@today+N}}` trong toàn văn → `dd/mm/yyyy`. */
const fillDates = (text: string) =>
  text.replace(/\{\{(@today[+-]\d+)\}\}/g, (_, rel: string) => {
    const [y, m, d] = resolveRelativeDates(rel).split('-');
    return `${d}/${m}/${y}`;
  });

function findSimilar(title: string): SimilarTask[] {
  const others = resolveRelativeDates(
    structuredClone(taskFixture.unitTasks) as Omit<SimilarTask, 'similarity'>[],
  );
  return others
    .map((t) => ({ ...t, similarity: similarity(title, t.title) }))
    .filter((t) => t.similarity >= SIMILARITY_THRESHOLD)
    .sort((a, b) => b.similarity - a.similarity);
}

/** Dữ liệu nhiệm vụ `NHAP` từ ô đã xác nhận; trường SCR-0502 không có thì lấy theo danh mục (suy luận). */
function toDraft(doc: StoredDoc, f: TaskSuggestionFields): Partial<TaskDraft> {
  const ci = catalogItem(f.catalogItemId);
  // Chủ trì là đơn vị: máy chủ giao cho người đứng đầu đơn vị (suy luận, TL-02 ghi "một đơn vị hoặc người").
  const head = ORG_PERSONS.find((p) => p.unitId === f.leadUnitId);
  return {
    source: 'VAN_BAN',
    sourceDoc: { number: doc.docNumber, title: doc.excerpt },
    title: f.title,
    catalogItemId: f.catalogItemId,
    keyResultId: f.keyResultId,
    taskGroup: (ci?.defaultGroup as TaskGroup | undefined) ?? 'THUONG_XUYEN',
    leadPersonId: head?.id,
    coAssignees: f.coUnits,
    dueDate: f.dueDate,
    acceptorId: ORG_ME,
    productName: f.productName,
    quantity: 1,
    unit: ci?.unit ?? 'sản phẩm',
    evidenceRequired: ci?.evidenceRequired ?? `Sản phẩm theo văn bản ${doc.docNumber}`,
    qualityCriteria: ci?.qualityCriteria ?? `Đáp ứng yêu cầu của văn bản ${doc.docNumber}`,
  };
}

function store(): StoredDoc[] {
  if (docs) return docs;
  const raw = resolveRelativeDates(structuredClone(fixture.docs)) as unknown as StoredDoc[];
  docs = raw.map((d) => {
    const doc: StoredDoc = {
      ...d,
      paragraphs: d.paragraphs.map((p) => ({ ...p, text: fillDates(p.text) })),
      usedParagraphs: d.usedParagraphs ?? [],
      pendingCount: 0,
      suggestionCount: 0,
      suggestions: [],
    };
    doc.suggestions = d.suggestions.map((s) => ({
      ...s,
      aiFields: structuredClone(s.fields),
      editedFields: [],
      similar: findSimilar(s.fields.title),
    }));
    // Gợi ý đã xác nhận sẵn trong fixture (Hình 8.4 nhiệm vụ 1) đã có nhiệm vụ NHAP.
    for (const s of doc.suggestions.filter((x) => x.decision === 'DA_XAC_NHAN')) {
      const t = addDraft(toDraft(doc, s.fields));
      Object.assign(s, { taskId: t.id, taskCode: t.code, taskStatus: t.status });
      doc.usedParagraphs.push({ paragraphNo: s.paragraphNo, taskCode: t.code! });
    }
    return doc;
  });
  return docs;
}

function summary(d: StoredDoc): SourceDocSummary {
  return {
    id: d.id,
    sourceSystem: d.sourceSystem,
    docNumber: d.docNumber,
    docDate: d.docDate,
    issuer: d.issuer,
    excerpt: d.excerpt,
    receivedAt: d.receivedAt,
    format: d.format,
    status: d.status,
    extraction: d.extraction,
    pendingCount: d.suggestions.filter((s) => s.decision === 'CHO_XAC_NHAN').length,
    suggestionCount: d.suggestions.length,
  };
}

function docView(d: StoredDoc): SourceDoc {
  const { suggestions: _s, usedParagraphs: _u, extractedAt: _e, ...rest } = d;
  return structuredClone({ ...rest, ...summary(d) });
}

const publicSuggestion = (s: StoredSuggestion): TaskSuggestion => {
  const { similarReason: _r, ...rest } = s;
  return structuredClone(rest);
};

function locate(suggestionId: string): { doc: StoredDoc; s: StoredSuggestion } | null {
  for (const doc of store()) {
    const s = doc.suggestions.find((x) => x.id === suggestionId);
    if (s) return { doc, s };
  }
  return null;
}

/** Văn bản xử lý xong khi không còn gợi ý chờ và mọi nhiệm vụ đã xác nhận đều đã giao. */
function settle(doc: StoredDoc) {
  const open = doc.suggestions.some(
    (s) =>
      s.decision === 'CHO_XAC_NHAN' || (s.decision === 'DA_XAC_NHAN' && s.taskStatus === 'NHAP'),
  );
  if (!open && doc.status === 'CHO_XU_LY' && doc.suggestions.length) doc.status = 'DA_XU_LY';
}

const FIELD_KEYS: SuggestionField[] = [
  'title',
  'leadUnitId',
  'coUnits',
  'dueDate',
  'productName',
  'catalogItemId',
  'keyResultId',
];

function validate(f: TaskSuggestionFields): { field: string; message: string }[] {
  const errors: { field: string; message: string }[] = [];
  const p = period();
  if (!f.title?.trim() || f.title.length > 500)
    errors.push({ field: 'title', message: 'Tên nhiệm vụ bắt buộc, tối đa 500 ký tự' });
  if (!f.leadUnitId) errors.push({ field: 'leadUnitId', message: 'Chọn đơn vị chủ trì' });
  if (f.coUnits?.some((c) => c.id === f.leadUnitId))
    errors.push({ field: 'coUnits', message: 'Đơn vị phối hợp không trùng đơn vị chủ trì' });
  if (!f.dueDate || f.dueDate < todayIso() || f.dueDate > p.endDate)
    errors.push({
      field: 'dueDate',
      message: `Hạn hoàn thành phải từ hôm nay đến hết kỳ ${p.label}`,
    });
  if (!f.productName?.trim() || f.productName.length > 255)
    errors.push({ field: 'productName', message: 'Sản phẩm bắt buộc, tối đa 255 ký tự' });
  return errors;
}

function fromDocument(req: MockRequest): MockResponse {
  const b = (req.body ?? {}) as Partial<FromDocumentRequest>;
  const hit = b.suggestionId ? locate(b.suggestionId) : null;
  if (!hit || hit.doc.id !== b.sourceDocId) return problem(404, 'Không tìm thấy nhiệm vụ trích');
  const { doc, s } = hit;
  if (doc.status !== 'CHO_XU_LY') return problem(409, 'Văn bản đã được xử lý hoặc bỏ qua');
  if (s.decision !== 'CHO_XAC_NHAN') return problem(409, 'Nhiệm vụ này đã được xử lý');
  // BR-05-07: khoá theo mã văn bản và số đoạn.
  const used = doc.usedParagraphs.find((u) => u.paragraphNo === s.paragraphNo);
  if (used) {
    return problem(
      409,
      `Đoạn ${s.paragraphNo} của văn bản đã được tạo nhiệm vụ ${used.taskCode}.`,
      {
        code: 'TASK-409-SOURCE-PARAGRAPH-USED',
      },
    );
  }
  const f = { ...s.fields, ...(b.fields ?? {}) } as TaskSuggestionFields;
  const errors = validate(f);
  const similar = findSimilar(f.title);
  if (similar.length && !b.similarReason?.trim()) {
    errors.push({
      field: 'similarReason',
      message: `Nhiệm vụ gần giống ${similar[0].code} (độ tương đồng ${similar[0].similarity
        .toFixed(2)
        .replace('.', ',')}). Vui lòng gộp hoặc ghi lý do tạo mới.`,
    });
  }
  if (errors.length) return problem(422, 'Chưa xác nhận được nhiệm vụ', { errors });
  const t = addDraft(toDraft(doc, f));
  const edited = FIELD_KEYS.filter(
    (k) => JSON.stringify(f[k] ?? null) !== JSON.stringify(s.aiFields[k] ?? null),
  );
  Object.assign(s, {
    fields: f,
    similar,
    decision: 'DA_XAC_NHAN',
    editedFields: edited,
    taskId: t.id,
    taskCode: t.code,
    taskStatus: t.status,
    similarReason: b.similarReason?.trim() || undefined,
  });
  doc.usedParagraphs.push({ paragraphNo: s.paragraphNo, taskCode: t.code! });
  return { status: 201, body: publicSuggestion(s), headers: { Location: `/api/v1/tasks/${t.id}` } };
}

function merge(req: MockRequest): MockResponse {
  const b = (req.body ?? {}) as { sourceDocId?: string; suggestionId?: string };
  const hit = b.suggestionId ? locate(b.suggestionId) : null;
  if (!hit || hit.doc.id !== b.sourceDocId) return problem(404, 'Không tìm thấy nhiệm vụ trích');
  const { doc, s } = hit;
  const old = s.similar.find((x) => x.id === req.pathParams['id']);
  if (!old) return problem(422, 'Chỉ gộp được vào nhiệm vụ gần giống của cùng đơn vị và kỳ');
  if (s.decision !== 'CHO_XAC_NHAN') return problem(409, 'Nhiệm vụ này đã được xử lý');
  Object.assign(s, {
    decision: 'DA_GOP',
    taskId: old.id,
    taskCode: old.code,
    taskStatus: old.status,
  });
  doc.usedParagraphs.push({ paragraphNo: s.paragraphNo, taskCode: old.code });
  settle(doc);
  return ok(publicSuggestion(s));
}

function reject(req: MockRequest): MockResponse {
  const hit = locate(req.pathParams['id']);
  // Cùng đường dẫn ghi quyết định với gợi ý của trợ lý soạn OKR (SCR-1701, WEB-049)
  if (!hit) return okrAssistDecision(req) ?? problem(404, 'Không tìm thấy nhiệm vụ trích');
  const b = (req.body ?? {}) as { decision?: string; reason?: string };
  if (b.decision !== 'BO_QUA') return problem(422, 'Quyết định không hợp lệ');
  if (!b.reason?.trim())
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'reason', message: 'Ghi lý do bỏ nhiệm vụ' }],
    });
  const { doc, s } = hit;
  const assigned = s.decision === 'DA_XAC_NHAN' && s.taskStatus !== 'NHAP';
  if (assigned || s.decision === 'DA_BO' || s.decision === 'DA_GOP')
    return problem(409, 'Nhiệm vụ đã giao hoặc đã xử lý, không bỏ được');
  if (s.taskId && s.decision === 'DA_XAC_NHAN') {
    cancelDraft(s.taskId);
    doc.usedParagraphs = doc.usedParagraphs.filter((u) => u.paragraphNo !== s.paragraphNo);
  }
  Object.assign(s, {
    decision: 'DA_BO',
    reason: b.reason.trim(),
    taskId: undefined,
    taskCode: undefined,
    taskStatus: undefined,
  });
  settle(doc);
  return ok(publicSuggestion(s));
}

function assignBatch(req: MockRequest): MockResponse {
  const ids = ((req.body ?? {}) as { taskIds?: string[] }).taskIds ?? [];
  if (!ids.length)
    return problem(422, 'Chưa có nhiệm vụ đã xác nhận để giao', {
      errors: [{ field: 'taskIds', message: 'Chọn ít nhất một nhiệm vụ' }],
    });
  const touched = new Set<StoredDoc>();
  const results = ids.map((id) => {
    let owner: { doc: StoredDoc; s: StoredSuggestion } | null = null;
    for (const doc of store())
      for (const s of doc.suggestions)
        if (s.taskId === id && s.decision === 'DA_XAC_NHAN') owner = { doc, s };
    // BR-05-05: chỉ giao nhiệm vụ trích đã được người giao xác nhận.
    if (!owner)
      return {
        taskId: id,
        code: '',
        assigned: false,
        status: 'NHAP',
        message: 'Nhiệm vụ chưa được xác nhận',
      };
    const r = assignStored(id, owner.s.similarReason);
    owner.s.taskStatus = r.draft?.status;
    touched.add(owner.doc);
    return {
      taskId: id,
      code: r.draft?.code ?? '',
      assigned: !r.errors.length,
      status: r.draft?.status ?? 'NHAP',
      message: r.errors.join(' ') || undefined,
    };
  });
  touched.forEach(settle);
  return ok({ results });
}

function dismiss(req: MockRequest): MockResponse {
  const doc = store().find((d) => d.id === req.pathParams['id']);
  if (!doc) return problem(404, 'Không tìm thấy văn bản');
  const reason = ((req.body ?? {}) as { reason?: string }).reason?.trim();
  if (!reason)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'reason', message: 'Ghi lý do bỏ qua văn bản' }],
    });
  if (doc.status !== 'CHO_XU_LY') return problem(409, 'Văn bản đã được xử lý hoặc bỏ qua');
  doc.status = 'DA_BO_QUA';
  doc.dismissReason = reason;
  return ok(docView(doc));
}

export const SOURCE_DOCS_MOCK: MockModule = {
  name: 'source-docs',
  reset: () => {
    docs = null;
  },
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/tasks/source-docs',
      handle: (r) => {
        const status = r.params.get('status') as SourceDocStatus | null;
        const items = store()
          .filter((d) => !status || d.status === status)
          .sort((a, b) => b.receivedAt.localeCompare(a.receivedAt))
          .map(summary);
        return ok({ items, nextCursor: null });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/tasks/source-docs/:id',
      handle: (r) => {
        const d = store().find((x) => x.id === r.pathParams['id']);
        return d ? ok(docView(d)) : problem(404, 'Không tìm thấy văn bản');
      },
    },
    { method: 'POST', pattern: '/api/v1/tasks/source-docs/:id/dismiss', handle: dismiss },
    { method: 'POST', pattern: '/api/v1/tasks/from-document', handle: fromDocument },
    { method: 'POST', pattern: '/api/v1/tasks/assign-batch', handle: assignBatch },
    { method: 'POST', pattern: '/api/v1/tasks/:id/source-docs', handle: merge },
    {
      method: 'GET',
      pattern: '/api/v1/ai/extractions',
      handle: (r) => {
        const d = store().find((x) => x.id === r.params.get('sourceDocId'));
        if (!d) return problem(404, 'Không tìm thấy văn bản');
        const body: DocExtraction = {
          sourceDocId: d.id,
          status: d.extraction,
          model: fixture.model,
          extractedAt: d.extractedAt,
          suggestions: d.suggestions.map(publicSuggestion),
        };
        return ok(body);
      },
    },
    { method: 'POST', pattern: '/api/v1/ai/suggestions/:id/decision', handle: reject },
  ],
};
