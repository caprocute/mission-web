/*
 * @vt/api-client: cấu hình chạy, interceptor, mô hình dữ liệu và client API của mission-core.
 * Kiểu dữ liệu sinh từ OpenAPI (`tools/openapi`, `npm run api:gen`; WEB-007).
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
export type {
  paths as MissionCorePaths,
  components as MissionCoreComponents,
} from './lib/generated/mission-core';
export { mockApiInterceptor, MOCK_API_OPTIONS } from './mock/mock-api.interceptor';
export type { MockApiOptions } from './mock/mock-api.interceptor';
