/*
 * @vt/api-client/domains: client và mô hình dữ liệu của các module nghiệp vụ chỉ màn hình trong remote dùng
 * (danh mục, kế hoạch, minh chứng, thẩm định, hồ sơ, giám sát, cấu hình, quản trị, phân tích). WEB-052 tách
 * khỏi entry chính `@vt/api-client` để trang chủ không phải tải; shell nạp trước entry này khi mở tuyến
 * của remote (`fe-shell/src/app/remotes.ts`). Mã trong các tệp dưới đây chỉ import phần dùng chung (cấu
 * hình chạy, phân trang, lỗi RFC 9457, theo dõi tác vụ) qua `@vt/api-client`, không qua đường dẫn tương
 * đối, để mọi entry dùng chung một bản token DI và dịch vụ.
 */
export * from './lib/models/catalog';
export * from './lib/catalog-api';
export * from './lib/models/plan';
export * from './lib/plan-api';
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
