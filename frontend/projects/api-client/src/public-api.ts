/*
 * @vt/api-client: cấu hình chạy, interceptor, mô hình dữ liệu và client API của mission-core.
 * Kiểu dữ liệu sinh từ bộ hợp đồng `contracts/openapi/` (`npm run api:gen`; WEB-007, WEB-064).
 */
export * from './lib/runtime-config';
export * from './lib/problem-details';
export * from './lib/problem-presenter';
export * from './lib/paging';
export * from './lib/interceptors';
export * from './lib/load-shedding';
export * from './lib/cell-router';
export * from './lib/feature-flags';
export * from './lib/api-path';
export * from './lib/models/task';
export * from './lib/models/job';
export * from './lib/task-api';
export * from './lib/models/catalog';
export * from './lib/catalog-api';
export * from './lib/job-tracker';
export * from './lib/ui/conflict-notice.component';
export * from './lib/ui/field-error.component';
export * from './lib/ui/job-progress.component';
/** Kiểu sinh theo module: `TasksPaths`, `TasksComponents`, `OrgComponents`, `CommonComponents`, `ApiPaths`… */
export type * from './lib/generated/index';
export * from './lib/models/task-assign';
export * from './lib/models/org';
export * from './lib/org-api';
export * from './lib/models/plan';
export * from './lib/plan-api';
export * from './lib/models/okr';
export * from './lib/okr-api';
export * from './lib/models/ui-form';
export * from './lib/ui-form-api';
export * from './lib/models/ui-pack';
export * from './lib/ui-pack-api';
export * from './lib/models/preference';
export * from './lib/preference-api';
export * from './lib/list-personalization';
export * from './lib/models/workflow-template';
export * from './lib/workflow-template-api';
export * from './lib/workflow/wf-lint';
export * from './lib/models/workflow-instance';
export * from './lib/workflow-instance-api';
export * from './lib/models/source-doc';
export * from './lib/source-doc-api';
export * from './lib/ai-api';
export * from './lib/models/ai-assist';
export * from './lib/models/evidence';
export * from './lib/evidence-api';
export * from './lib/file-api';
export * from './lib/models/tenant';
export * from './lib/models/tenant-onboarding';
export * from './lib/tenant-api';
export * from './lib/okr-progress';
export * from './lib/models/org-tree';
export * from './lib/org-tree-api';
export * from './lib/models/period';
export * from './lib/period-api';
export * from './lib/models/role-binding';
export * from './lib/role-admin-api';
export * from './lib/models/appraisal';
export * from './lib/appraisal-api';
export * from './lib/dossier-api';
export * from './lib/models/dossier';
export * from './lib/models/org-sync';
export * from './lib/org-sync-api';
export * from './lib/models/reopen';
export * from './lib/reopen-api';
export * from './lib/models/ledger';
export * from './lib/ledger-api';
export * from './lib/models/complaint';
export * from './lib/complaint-api';
export * from './lib/models/alert';
export * from './lib/alert-api';
export * from './lib/models/policy';
export * from './lib/policy-api';
export * from './lib/models/analytics';
export * from './lib/analytics-api';
export { mockApiInterceptor, MOCK_API_OPTIONS } from './mock/mock-api.interceptor';
export {
  failMockIntegrityOnce,
  failMockUploadPartOnce,
  mockStepUp,
  resetMockApi,
  setMockEsignOutage,
} from './mock/mock-session';
export type { MockApiOptions } from './mock/mock-api.interceptor';
