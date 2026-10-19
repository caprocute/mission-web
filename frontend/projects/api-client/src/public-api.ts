/*
 * @vt/api-client: cấu hình chạy, interceptor, mô hình dữ liệu và client API của mission-core.
 */
export * from './lib/runtime-config';
export * from './lib/problem-details';
export * from './lib/paging';
export * from './lib/interceptors';
export * from './lib/models/task';
export * from './lib/task-api';
export * from './lib/models/catalog';
export * from './lib/catalog-api';
export { mockApiInterceptor, MOCK_API_OPTIONS } from './mock/mock-api.interceptor';
export type { MockApiOptions } from './mock/mock-api.interceptor';
