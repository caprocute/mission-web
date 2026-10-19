/**
 * WEB-064: kiểm lúc biên dịch rằng model viết tay của `@vt/api-client` tương thích hai chiều với
 * kiểu sinh từ `contracts/openapi/` (mọi giá trị của bên này gán được cho bên kia). Khi hợp đồng đổi
 * mà model chưa theo, `npm run test:all` báo lỗi biên dịch ở dòng tương ứng.
 *
 * Kiểu đã là bí danh của kiểu sinh (`TaskSummary`, `TaskDetail`, `ProgressUpdate`, `TaskStatus`,
 * `AssigneeRole`, `Assignment`, `AssignmentKind`, `MyOrgProfile`, `WorkContext`, `Job`, `JobStatus`,
 * `ProblemDetails`, `FieldError`) không cần kiểm ở đây. Các kiểu viết tay còn lệch hợp đồng và chưa
 * kiểm (`OrgUnit`, `OkrBasis`, `ProgressBatchResult`, `CellLoad`, các kiểu OKR và tenant chưa có cùng
 * tên trong hợp đồng) được ghi ở báo cáo WEB-064 và wiki/06. Kế hoạch (`plan.ts`) theo hợp đồng từ
 * WEB-065; `PlanWorkspace`, `PlanReview` là kiểu ghép phía client, không có trong hợp đồng.
 */
import type { components as DossiersContract } from '../generated/dossiers';
import type { components as OkrContract } from '../generated/okr';
 * `ProblemDetails`, `FieldError`, các kiểu liệt kê, lịch sử và check-in của `okr.ts`) không cần kiểm ở
 * đây. Các kiểu viết tay còn lệch hợp đồng và chưa kiểm (`OrgUnit`, `PlanItem`, `PlanVersion`,
 * `OkrBasis`, `ProgressBatchResult`, `CellLoad`, các dạng xem OKR như cây mục tiêu, hàng chờ duyệt,
 * ngữ cảnh soạn, và kiểu tenant chưa có cùng tên trong hợp đồng) được ghi ở báo cáo WEB-064 và wiki/06.
 */
import type { components as OrgContract } from '../generated/org';
import type { components as PlatformContract } from '../generated/platform';
import type { components as TasksContract, operations as TasksOps } from '../generated/tasks';
import type * as Catalog from './catalog';
import type * as Org from './org';
import type * as OrgSync from './org-sync';
import type * as Plan from './plan';
import type * as SourceDoc from './source-doc';
import type * as TaskAssign from './task-assign';
import type * as Task from './task';

/** `true` khi A và B gán được cho nhau. */
type Compatible<A, B> = [A] extends [B] ? ([B] extends [A] ? true : false) : false;
type Expect<T extends true> = T;

export type ContractCheck = [
  // catalog.ts ↔ tasks.yaml
  Expect<Compatible<Catalog.WorkCatalogStatus, TasksContract['schemas']['WorkCatalogStatus']>>,
  Expect<Compatible<Catalog.CatalogItemStatus, TasksContract['schemas']['CatalogItemStatus']>>,
  Expect<Compatible<Catalog.WorkCatalog, TasksContract['schemas']['WorkCatalog']>>,
  Expect<Compatible<Catalog.Position, TasksContract['schemas']['Position']>>,
  Expect<Compatible<Catalog.CatalogItem, TasksContract['schemas']['CatalogItem']>>,
  Expect<Compatible<Catalog.CatalogItemInput, TasksContract['schemas']['CatalogItemInput']>>,
  Expect<
    Compatible<Catalog.CatalogItemSaveResult, TasksContract['schemas']['CatalogItemSaveResult']>
  >,
  Expect<Compatible<Catalog.CatalogItemsPage, TasksContract['schemas']['CatalogItemsPage']>>,
  Expect<Compatible<Catalog.CatalogImportJob, TasksContract['schemas']['CatalogImportJob']>>,
  // org.ts ↔ org.yaml
  Expect<Compatible<Org.OrgPerson, OrgContract['schemas']['OrgPerson']>>,
  // org-sync.ts ↔ org.yaml (trường thêm chờ hợp đồng đều tuỳ chọn)
  Expect<Compatible<OrgSync.SyncSource, OrgContract['schemas']['SyncSource']>>,
  Expect<Compatible<OrgSync.OrgSyncRun, OrgContract['schemas']['OrgSyncRun']>>,
  Expect<Compatible<OrgSync.OrgSyncSchedule, OrgContract['schemas']['OrgSyncSchedule']>>,
  Expect<Compatible<OrgSync.OrgDiscrepancy, OrgContract['schemas']['OrgDiscrepancy']>>,
  Expect<
    Compatible<OrgSync.DiscrepancyResolution, OrgContract['schemas']['DiscrepancyResolution']>
  >,
  // plan.ts ↔ tasks.yaml
  Expect<Compatible<Plan.PlanItemRef, TasksContract['schemas']['PlanItemRef']>>,
  Expect<Compatible<Plan.PlanVersionStatus, TasksContract['schemas']['PlanVersionStatus']>>,
  Expect<Compatible<Plan.Plan, TasksContract['schemas']['Plan']>>,
  Expect<Compatible<Plan.PlanVersionSummary, TasksContract['schemas']['PlanVersionSummary']>>,
  Expect<Compatible<Plan.PlanItemInput, TasksContract['schemas']['PlanItemInput']>>,
  Expect<Compatible<Plan.PlanItem, TasksContract['schemas']['PlanItem']>>,
  Expect<Compatible<Plan.PlanCheck, TasksContract['schemas']['PlanCheck']>>,
  Expect<Compatible<Plan.PlanVersion, TasksContract['schemas']['PlanVersion']>>,
  Expect<Compatible<Plan.PlanVersionInput, TasksContract['schemas']['PlanVersionInput']>>,
  Expect<Compatible<Plan.PlanRemovedItem, TasksContract['schemas']['PlanRemovedItem']>>,
  Expect<Compatible<Plan.PlanDiffLine, TasksContract['schemas']['PlanDiffLine']>>,
  Expect<Compatible<Plan.PlanDiff, TasksContract['schemas']['PlanDiff']>>,
  Expect<
    Compatible<
      Plan.SignMethod,
      TasksOps['approvePlanVersion']['requestBody']['content']['application/json']['signMethod']
    >
  >,
  // plan.ts ↔ dossiers.yaml (yêu cầu ký của esign-service)
  Expect<Compatible<Plan.EsignRequestStatus, DossiersContract['schemas']['EsignRequestStatus']>>,
  Expect<
    Compatible<
      Plan.PlanSignRequest,
      Pick<
        DossiersContract['schemas']['EsignRequest'],
        'id' | 'status' | 'expiresAt' | 'completedAt'
      >
    >
  >,
  // source-doc.ts ↔ tasks.yaml
  Expect<Compatible<SourceDoc.SourceSystem, TasksContract['schemas']['SourceSystem']>>,
  Expect<Compatible<SourceDoc.SourceDocStatus, TasksContract['schemas']['SourceDocStatus']>>,
  Expect<Compatible<SourceDoc.SourceDocSummary, TasksContract['schemas']['SourceDocSummary']>>,
  Expect<Compatible<SourceDoc.SourceDoc, TasksContract['schemas']['SourceDoc']>>,
  Expect<Compatible<SourceDoc.SuggestionDecision, TasksContract['schemas']['SuggestionDecision']>>,
  Expect<
    Compatible<SourceDoc.TaskSuggestionFields, TasksContract['schemas']['TaskSuggestionFields']>
  >,
  Expect<Compatible<SourceDoc.SuggestionField, TasksContract['schemas']['SuggestionField']>>,
  Expect<Compatible<SourceDoc.TaskSuggestion, TasksContract['schemas']['TaskSuggestion']>>,
  Expect<
    Compatible<SourceDoc.FromDocumentRequest, TasksContract['schemas']['FromDocumentRequest']>
  >,
  Expect<Compatible<SourceDoc.AssignBatchResult, TasksContract['schemas']['AssignBatchResult']>>,
  // source-doc.ts ↔ platform.yaml
  Expect<Compatible<SourceDoc.ExtractionStatus, PlatformContract['schemas']['ExtractionStatus']>>,
  Expect<Compatible<SourceDoc.DocExtraction, PlatformContract['schemas']['DocExtraction']>>,
  // task-assign.ts ↔ tasks.yaml
  Expect<Compatible<TaskAssign.TaskPriority, TasksContract['schemas']['TaskPriority']>>,
  Expect<Compatible<TaskAssign.TaskMilestone, TasksContract['schemas']['TaskMilestone']>>,
  Expect<Compatible<TaskAssign.AssigneeRef, TasksContract['schemas']['AssigneeRef']>>,
  Expect<Compatible<TaskAssign.TaskDraft, TasksContract['schemas']['TaskDraft']>>,
  Expect<Compatible<TaskAssign.TaskDraftTemplate, TasksContract['schemas']['TaskDraftTemplate']>>,
  Expect<Compatible<TaskAssign.AssignCheckItem, TasksContract['schemas']['AssignCheckItem']>>,
  Expect<Compatible<TaskAssign.SimilarTask, TasksContract['schemas']['SimilarTask']>>,
  Expect<Compatible<TaskAssign.PersonWorkload, TasksContract['schemas']['PersonWorkload']>>,
  Expect<Compatible<TaskAssign.AssignCheckResult, TasksContract['schemas']['AssignCheckResult']>>,
  Expect<Compatible<TaskAssign.AssignRequest, TasksContract['schemas']['AssignRequest']>>,
  Expect<Compatible<TaskAssign.AssignResult, TasksContract['schemas']['AssignResult']>>,
  Expect<Compatible<TaskAssign.BulkAssignRequest, TasksContract['schemas']['BulkAssignRequest']>>,
  // task.ts ↔ tasks.yaml
  Expect<Compatible<Task.TaskGroup, TasksContract['schemas']['TaskGroup']>>,
  Expect<Compatible<Task.TaskSource, TasksContract['schemas']['TaskSource']>>,
  Expect<Compatible<Task.ProgressChannel, TasksContract['schemas']['ProgressChannel']>>,
  Expect<Compatible<Task.ProgressLogEntry, TasksContract['schemas']['ProgressLogEntry']>>,
  Expect<Compatible<Task.BlockerStatus, TasksContract['schemas']['BlockerStatus']>>,
  Expect<Compatible<Task.BlockerCauseCategory, TasksContract['schemas']['BlockerCauseCategory']>>,
  Expect<Compatible<Task.ImpactLevel, TasksContract['schemas']['ImpactLevel']>>,
  Expect<Compatible<Task.CauseKind, TasksContract['schemas']['CauseKind']>>,
  Expect<Compatible<Task.TaskBlocker, TasksContract['schemas']['TaskBlocker']>>,
  Expect<Compatible<Task.BlockerReport, TasksContract['schemas']['BlockerReport']>>,
  Expect<Compatible<Task.ChangeRequestType, TasksContract['schemas']['ChangeRequestType']>>,
  Expect<Compatible<Task.ChangeRequestStatus, TasksContract['schemas']['ChangeRequestStatus']>>,
  Expect<Compatible<Task.ChangeRequest, TasksContract['schemas']['ChangeRequest']>>,
  Expect<Compatible<Task.ChangeRequestDraft, TasksContract['schemas']['ChangeRequestDraft']>>,
  Expect<Compatible<Task.ChangeRequestPreview, TasksContract['schemas']['ChangeRequestPreview']>>,
  Expect<Compatible<Task.StateLogEntry, TasksContract['schemas']['StateLogEntry']>>,
  Expect<Compatible<Task.DelayRisk, TasksContract['schemas']['DelayRisk']>>,
  Expect<Compatible<Task.MyTasksQuickFilter, TasksContract['schemas']['MyTasksQuickFilter']>>,
  Expect<Compatible<Task.PeriodRef, TasksContract['schemas']['PeriodRef']>>,
  Expect<Compatible<Task.MyTasksPage, TasksContract['schemas']['MyTasksPage']>>,
  Expect<Compatible<Task.ProgressBatchItem, TasksContract['schemas']['ProgressBatchItem']>>,
  Expect<Compatible<Task.TaskRegistration, TasksContract['schemas']['TaskRegistration']>>,
];

describe('model viết tay và hợp đồng OpenAPI', () => {
  it('tương thích kiểu (kiểm lúc biên dịch, xem ContractCheck)', () => {
    const checked: ContractCheck['length'] = 72;
    const checked: ContractCheck['length'] = 58;
    const checked: ContractCheck['length'] = 64;
    expect(checked).toBeGreaterThan(0);
  });
});
