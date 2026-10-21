# `vt-api-contract` — Hợp đồng OpenAPI 3.1 phía máy chủ (ADR-32 · NEN-006)

Thư viện sinh giao diện Spring MVC (`*Api`) và DTO (`model`) từ 11 hợp đồng OpenAPI 3.1 trong `contracts/openapi/modules/*.yaml` cùng `contracts/openapi/common/common.yaml`.

## 1. Luồng gộp hợp đồng và sinh mã

Để bảo đảm các kiểu dùng chung trong `contracts/openapi/common/common.yaml` (`Job`, `JobStatus`, `ProblemDetails`, `FieldError`, `Uuid`, `Date`, `DateTime`…) giữ đúng tên lớp khi sinh mã và tương thích với bộ phân tích YAML của OpenAPI Generator:

1. Kịch bản `backend/tools/contracts-bundle.sh` gọi `npm --prefix contracts run bundle` (Redocly CLI) để giải tham chiếu `$ref: '../common/common.yaml#...'` thành 11 tệp độc lập trong `contracts/dist/*.yaml`.
2. Ở pha `initialize`, `maven-antrun-plugin` tự động gọi `backend/tools/contracts-bundle.sh --if-missing` nếu `contracts/dist/*.yaml` chưa tồn tại, bảo đảm **một lệnh Maven duy nhất** (`./mvnw verify`) luôn chạy ổn định.
3. Ở pha `generate-sources`, `openapi-generator-maven-plugin` (`generatorName=spring`, `interfaceOnly=true`, `useSpringBoot3=true`, `useBeanValidation=true`, `useTags=false`, `documentationProvider=springdoc`) sinh mã vào `target/generated-sources/openapi` (không commit vào Git).

## 2. Ánh xạ kiểu dữ liệu chuẩn

| Kiểu OpenAPI (`common.yaml`) | Kiểu Java đã sinh |
|---|---|
| `Uuid` (`string`, `format: uuid`) | `java.util.UUID` |
| `DateTime` (`string`, `format: date-time`) | `java.time.OffsetDateTime` |
| `Date` (`string`, `format: date`) | `java.time.LocalDate` |
| `ProblemDetails` (`application/problem+json`, RFC 9457) | `vn.viettel.okrkpi.contract.<module>.model.ProblemDetails` |
| `Job` / `JobStatus` (`202 Accepted`) | `vn.viettel.okrkpi.contract.<module>.model.Job` / `JobStatus` (`CHO`, `DANG_CHAY`, `HOAN_TAT`, `THAT_BAI`, `DA_HUY`) |

## 3. Bảng ánh xạ `x-mission.owner` → gói hợp đồng và giao diện

Tổng số thao tác (operation) trên 11 tệp hợp đồng: **372 thao tác** trên **24 giao diện `*Api`**.

| Mô-đun (`contracts/openapi/modules/`) | Số thao tác | `x-mission.owner` | Gói API (`vn.viettel.okrkpi.contract.<module>.api`) | Giao diện `*Api` đã sinh |
|---|---:|---|---|---|
| `appraisals.yaml` | 44 | `SVC-05` (44) | `vn.viettel.okrkpi.contract.appraisals.api` | `AppraisalsApi` |
| `config.yaml` | 34 | `SVC-05` (34) | `vn.viettel.okrkpi.contract.config.api` | `ConfigApi`, `PeriodsApi`, `UiApi` |
| `dossiers.yaml` | 28 | `SVC-05` (21), `SVC-07` (7) | `vn.viettel.okrkpi.contract.dossiers.api` | `DossiersApi` (`SVC-05`), `EsignApi` (`SVC-07`) |
| `jobs.yaml` | 2 | `SVC-05` (2) | `vn.viettel.okrkpi.contract.jobs.api` | `JobsApi` (`getJob`, `cancelJob`) |
| `okr.yaml` | 40 | `SVC-05` (40) | `vn.viettel.okrkpi.contract.okr.api` | `OkrApi` |
| `org.yaml` | 31 | `SVC-05` (29), `SVC-06` (2) | `vn.viettel.okrkpi.contract.org.api` | `OrgApi` |
| `oversight.yaml` | 29 | `SVC-11` (26), `SVC-13` (3) | `vn.viettel.okrkpi.contract.oversight.api` | `OversightApi` (`SVC-11`), `LedgerApi` (`SVC-13`) |
| `platform.yaml` | 52 | `SVC-05` (20), `SVC-10` (13), `SVC-08` (10), `SVC-09` (8), `SVC-12` (1) | `vn.viettel.okrkpi.contract.platform.api` | `AiApi` (`SVC-10`), `AnalyticsApi` (`SVC-12`), `NotificationsApi` (`SVC-09`), `RegistryApi` (`SVC-05`), `SearchApi` (`SVC-05`), `WorkflowApi` (`SVC-08`) |
| `policies.yaml` | 17 | `SVC-05` (15), `SVC-10` (2) | `vn.viettel.okrkpi.contract.policies.api` | `PoliciesApi` |
| `products.yaml` | 43 | `SVC-05` (43) | `vn.viettel.okrkpi.contract.products.api` | `EvidencesApi`, `FilesApi`, `ProductsApi` |
| `tasks.yaml` | 52 | `SVC-05` (52) | `vn.viettel.okrkpi.contract.tasks.api` | `CatalogApi`, `PlansApi`, `TasksApi` |

## 4. Classifier JAR theo dịch vụ (`x-mission.owner`)

Ngoài JAR mặc định chứa đủ 11 mô-đun, `vt-api-contract` đóng gói sẵn các classifier JAR để từng dịch vụ chỉ kéo phần hợp đồng của mình:

- `mission-core` (`SVC-05`): `appraisals`, `config`, `dossiers`, `jobs`, `okr`, `org`, `platform`, `policies`, `products`, `tasks`
- `esign` (`SVC-07 esign-service`): `dossiers` (`EsignApi`)
- `ledger` (`SVC-13 ledger-service`): `oversight` (`LedgerApi`)
- `ai` (`SVC-10 ai-service`): `platform` (`AiApi`), `policies` (`PoliciesApi`)
