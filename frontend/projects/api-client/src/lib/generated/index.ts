/**
 * Sinh tự động bởi openapi-typescript từ contracts/openapi/* (WEB-064).
 * Không sửa tay; sửa hợp đồng rồi chạy `npm run api:gen` trong frontend/.
 */

// Bí danh theo module để dùng ngoài thư viện; trong thư viện import thẳng từ ./<module>.
export type { components as CommonComponents } from './common';
export type {
  paths as AppraisalsPaths,
  components as AppraisalsComponents,
  operations as AppraisalsOperations,
} from './appraisals';
export type {
  paths as ConfigPaths,
  components as ConfigComponents,
  operations as ConfigOperations,
} from './config';
export type {
  paths as DossiersPaths,
  components as DossiersComponents,
  operations as DossiersOperations,
} from './dossiers';
export type {
  paths as JobsPaths,
  components as JobsComponents,
  operations as JobsOperations,
} from './jobs';
export type {
  paths as OkrPaths,
  components as OkrComponents,
  operations as OkrOperations,
} from './okr';
export type {
  paths as OrgPaths,
  components as OrgComponents,
  operations as OrgOperations,
} from './org';
export type {
  paths as OversightPaths,
  components as OversightComponents,
  operations as OversightOperations,
} from './oversight';
export type {
  paths as PlatformPaths,
  components as PlatformComponents,
  operations as PlatformOperations,
} from './platform';
export type {
  paths as PoliciesPaths,
  components as PoliciesComponents,
  operations as PoliciesOperations,
} from './policies';
export type {
  paths as ProductsPaths,
  components as ProductsComponents,
  operations as ProductsOperations,
} from './products';
export type {
  paths as TasksPaths,
  components as TasksComponents,
  operations as TasksOperations,
} from './tasks';

/** Mọi đường dẫn khai trong bộ hợp đồng (không kèm tiền tố `/api/v1`). */
export type ApiPaths = import('./appraisals').paths &
  import('./config').paths &
  import('./dossiers').paths &
  import('./jobs').paths &
  import('./okr').paths &
  import('./org').paths &
  import('./oversight').paths &
  import('./platform').paths &
  import('./policies').paths &
  import('./products').paths &
  import('./tasks').paths;
