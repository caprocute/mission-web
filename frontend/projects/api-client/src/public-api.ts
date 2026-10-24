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
export * from './lib/job-tracker';
export * from './lib/ui/conflict-notice.component';
export * from './lib/ui/field-error.component';
export * from './lib/ui/job-progress.component';
/** Kiểu sinh theo module: `TasksPaths`, `TasksComponents`, `OrgComponents`, `CommonComponents`, `ApiPaths`… */
export type * from './lib/generated/index';
export * from './lib/models/task-assign';
export * from './lib/models/org';
export * from './lib/org-api';
export * from './lib/models/okr';
export * from './lib/okr-api';
export * from './lib/models/ui-form';
export * from './lib/ui-form-api';
export * from './lib/models/ui-pack';
export * from './lib/ui-pack-api';
export * from './lib/models/preference';
export * from './lib/preference-api';
export * from './lib/okr-progress';
export * from './lib/models/notification';
export * from './lib/sse';
export * from './lib/notification-api';
export { mockApiInterceptor, MOCK_API_OPTIONS } from './mock/mock-api.interceptor';
export { setMockHomeBase } from './mock/mock-home-base';
export {
  failMockIntegrityOnce,
  failMockUploadPartOnce,
  mockStepUp,
  resetMockApi,
  setMockEsignOutage,
} from './mock/mock-session';
export type { MockApiOptions } from './mock/mock-api.interceptor';
