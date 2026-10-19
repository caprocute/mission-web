/*
 * @vt/api-client: cấu hình chạy, interceptor, mô hình dữ liệu và client API của mission-core.
 * Kiểu dữ liệu sinh từ bộ hợp đồng `contracts/openapi/` (`npm run api:gen`; WEB-007, WEB-064).
 */
export * from './lib/runtime-config';
export * from './lib/problem-details';
export * from './lib/problem-presenter';
export * from './lib/paging';
export * from './lib/interceptors';
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
export * from './lib/models/source-doc';
export * from './lib/source-doc-api';
export * from './lib/ai-api';
export * from './lib/models/evidence';
export * from './lib/evidence-api';
export * from './lib/file-api';
export * from './lib/models/tenant';
export * from './lib/models/tenant-onboarding';
export * from './lib/tenant-api';
export * from './lib/okr-progress';
export * from './lib/models/org-tree';
export * from './lib/org-tree-api';
export { mockApiInterceptor, MOCK_API_OPTIONS } from './mock/mock-api.interceptor';
export {
  failMockUploadPartOnce,
  mockStepUp,
  resetMockApi,
  setMockEsignOutage,
} from './mock/mock-session';
export type { MockApiOptions } from './mock/mock-api.interceptor';
