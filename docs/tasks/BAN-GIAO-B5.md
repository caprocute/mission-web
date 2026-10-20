# Bàn giao luồng B5 (định danh, tenant, tổ chức) – trạng thái ngày 20/10/2026

Luồng B5 dừng theo chỉ đạo của người phụ trách: các task backend chuyển sang công cụ AI khác tiếp nhận. Tệp này ghi trạng thái thật của từng task trên nhánh `claude/b5-dinh-danh-to-chuc-1o4gth` để bên tiếp nhận làm tiếp mà không phải đọc lại lịch sử hội thoại. Nhánh dựng trên `claude/ke-hoach-tong-the-ds4nmi` (kế hoạch tổng thể, chưa gộp vào main) và **chưa gộp vào main**. Wiki trang 05a, 07, 14 **chưa được cập nhật**; các ghi chú cần đưa vào wiki nằm ở mục 3 của tệp này.

Phần nền móng (NEN-005 Maven cha, NEN-007 Avro, NEN-010 JWT, NEN-011 outbox, NEN-014 OPA, NEN-015 mã hoá, NEN-017 thư viện Go, NEN-019 mẫu service Java, NEN-030 lint Flyway, NEN-031 vai trò CSDL) chưa có khi B5 làm. Mỗi dịch vụ vì vậy tự dựng phần tối thiểu sau một interface (port) để sau thay bằng thư viện nền; không tạo `backend/pom.xml` gốc.

## 1. Trạng thái từng task

| Task | Trạng thái | Commit chính | Ghi chú |
|---|---|---|---|
| DV-02-01 Mẫu realm production | **Xong** | `20e52c2` | 16/16 kiểm thử xanh trên Keycloak 26.4.0 thật |
| DV-03-01 Khung tenant-registry | **Xong** | `85b5865` | go test, govulncheck, helm lint, kubeconform xanh |
| DV-03-02 Phân giải tenant | **Xong** | `15babde` | p95 trúng đệm 107 µs |
| DV-03-03 Vòng đời tenant | **Xong** | `8d9abd8` | độ phủ gói nghiệp vụ 86,5% |
| DV-04-01 Khung org-service, schema `org` | **Xong** | `db93a79` | migration V1..V4, ArchUnit, RLS, kiểm thử hai trục |
| DV-04-02 Đơn vị theo asOf/recordedAsOf | **Dở** | `0e853f8` | xem mục 2 |
| DV-04-03 Thay đổi tổ chức, EVT-14..16 | **Chưa làm phần API** | – | xem mục 2 |

### DV-02-01 (xong)

Tệp: `deploy/charts/iam/**` (mẫu `files/realm/realm-cell.yaml` cho keycloak-config-cli 6.5.1-26, Job hook nạp realm, ExternalSecret, NetworkPolicy), `deploy/environments/{staging,production}/iam/**`, `backend/services/iam/{pom.xml,src/test/**}`.
Lệnh kiểm: `helm lint deploy/charts/iam`; `helm template t deploy/charts/iam | kubeconform -strict` (5/5 hợp lệ, CRD ExternalSecret lấy từ CRDs-catalog datreeio); `cd backend/services/iam && mvn -q verify` (RealmTemplateStaticTest 5/5, RealmTemplateKeycloakIT 11/11).
Đã kiểm: PKCE S256 bắt buộc, thiếu/sai `code_verifier` bị từ chối, implicit và direct grant tắt, token ES256 `exp-iat` = 300 s, refresh xoay vòng dùng lại bị thu hồi cả chuỗi, khoá tạm ở lần sai thứ 5, khoá cố định ở lần thứ 20, Argon2 và băm lại mật khẩu PBKDF2 cũ, role khớp `docs/phan-quyen/ma-tran.json`.
Còn lại: `pom.xml` độc lập phải đổi `<parent>` sang pom cha khi NEN-005 xong (tệp này nằm ngoài danh sách được phép sửa, bắt buộc để chạy kiểm thử); `.gitignore` chưa loại `target/`; chưa cài thật lên kind; danh sách mật khẩu đã lộ chờ task INF dựng ảnh Keycloak; khoá ký do Keycloak tự sinh, chưa nạp từ OpenBao/HSM; chờ người phụ trách cung cấp client `mission-config-cli` ở realm master, tên Service Keycloak, đường dẫn KV OpenBao, tên ClusterSecretStore.

### DV-03-01..03 (xong)

Tệp: `services-go/tenant-registry/**` (go.mod `go 1.25.0` + `toolchain go1.26.9`), `deploy/charts/tenant-registry/**`. Migration `db/migration/registry/V1..V3`.
Lệnh kiểm: `cd services-go/tenant-registry && go vet ./... && go test ./... && govulncheck ./...` (xanh, chạy lại sau khi gộp nhánh); `helm lint` và `helm template | kubeconform -strict` (7/7, bật ExternalSecret 9/9); `docker build` ra ảnh distroless UID 65532.
Đã có: DDL `registry` đúng TL-03 với `ex_tenant_cell__no_overlap`, vai trò `svc_registry`/`ro_registry` quyền tối thiểu; `resolveTenantCell` (422/404/403/421/503 + `Retry-After: 30`, cookie HMAC `mission_route` 5 phút, đệm 30 s vô hiệu bằng LISTEN/NOTIFY), `listCells`; máy trạng thái 8 trạng thái ghi `tenant_status_log` + outbox cùng giao dịch, các thao tác `/config/tenants*`, `createRegistryTenant`, `getRegistryTenant`, `getJob`, xuất 202, JSON Merge Patch + If-Match (409, thiếu thì 428), Idempotency-Key 48 giờ, BR-01-01, BR-01-02 (đúng 85% được nhận), MSG-01-03, MSG-01-04; kiểm thử hợp đồng với `platform.yaml`, `config.yaml`, `jobs.yaml`.
Điểm chờ: NEN-017 (thay `internal/platform/*`), NEN-007/011 (outbox đang ghi JSON, topic `registry.tenant.changed.v1`), NEN-010 (JWT), NEN-014 (OPA, đang kiểm vai trò trong mã), NEN-015 (C2 đang dùng khoá AES tĩnh `C2_KEY`), NEN-030, NEN-031, pg_partman, SVC-08 lưu tệp xuất, cổng định tuyến `/jobs/{id}` của SVC-03, BR-01-03 (MOD-05), saga DV-03-04, giao thức cell báo số tài khoản (chưa có trong TL).

### DV-04-01 (xong) và DV-04-02 (dở)

Tệp: `backend/services/org-service/**` (pom độc lập, cha `spring-boot-starter-parent` 3.5; giao diện API sinh bằng openapi-generator `interfaceOnly` từ `contracts/openapi/modules/org.yaml`), `deploy/charts/org-service/**`. Migration `src/main/resources/db/migration/org/V1..V4`.
DV-04-01 có: bốn nhóm migration theo TL-03, cột sinh `valid_period`/`recorded_period`, EX chống chồng lấn, `ex_assignment__one_primary`, chỉ mục một phần, `COMMENT ON COLUMN` C0–C3, RLS `rls_<bảng>__tenant`, `svc_org` chỉ UPDATE cột đóng phiên bản và không DELETE, `scim_sync_log` phân vùng gốc PostgreSQL (thay pg_partman, điểm chờ), hàm `assignment_asof`, `unit_subtree_asof`, thành phần ghi phiên bản `BitemporalWriter`, port `PolicyPort`, `FieldEncryptor` (mặc định từ chối, AES-GCM chỉ cho kiểm thử).
DV-04-02 đang ở bước: đã viết `OrgUnitQueryService`, `OrgUnitCommandService`, `OrgController` (getOrgUnit, updateOrgUnit, getOrgTree, getOrgSubtree, searchOrgUnits, listOrgUnits, listOrgUnitHistory, listSubjectGroups), ánh xạ web, băm yêu cầu cho Idempotency-Key, kiểm thử `OrgUnitApiIT`, `ContractValidator`, `TreePerformanceIT`. Mã biên dịch được (`mvn -o -DskipTests test-compile` đạt). Lần chạy `mvn -o verify` cuối cùng bị cắt sau 10 phút trước khi xong; các bộ đã chạy đều xanh: OrgUnitApiIT 10/10, BitemporalConstraintsIT 7/7, MigrationConventionsTest 6/6, ContractPolicyAdapterTest 4/4, SmallHelpersTest 4/4, FieldEncryptionIT 2/2. **Chưa xác nhận**: ArchitectureTest, ServiceSmokeIT, TimelineTest, OrgChangeRulesTest, TreePerformanceIT (nghi chậm hoặc treo, cần chạy riêng), độ phủ JaCoCo ≥ 80%, số đo p95 cây 10.000 nút, chặn vòng 422 và EVT-14 qua outbox cần soát lại xem đã đủ kiểm thử chưa, helm lint/kubeconform cho chart org-service chưa chạy lại.

### DV-04-03 (chưa làm phần API)

Đã có lớp miền dùng lại được: `OrgChangeType`, `OrgChangeRules`, `ChangeCommand`, `ChangeResult`, `ChangeOrderStore`/`JdbcChangeOrderStore`, `AssignmentStore`, `PeriodLockPort`/`ConfiguredPeriodLockAdapter`, `OrgEvents`, `JdbcOutboxWriter`. Chưa có: thao tác `changeOrgUnit` trong controller, áp thay đổi trong một giao dịch, chuyển phân công (`moveAssignmentsTo`), cảnh báo MSG-01-10, EVT-15/EVT-16, kiểm thử Kafka + Apicurio (phụ thuộc NEN-007, NEN-011, NEN-022).

## 2. Cách chạy lại kiểm thử

Docker daemon phải chạy (Testcontainers dùng `postgres:17`, `keycloak/keycloak:26.4.0`, `adorsys/keycloak-config-cli:6.5.1-26`, `flyway/flyway:11`; quay.io bị chặn trong phiên đám mây nên dùng ảnh Docker Hub). Lệnh: `cd backend/services/iam && mvn -q verify`; `cd services-go/tenant-registry && go test ./...`; `cd backend/services/org-service && mvn verify` (nên chạy `TreePerformanceIT` riêng bằng `-Dit.test=TreePerformanceIT`).

## 3. Ghi chú cần đưa vào wiki trang 14 (và 07, 05a)

1. **Tên realm** lệch: TL-01 `okrkpi`, TL-04 `platform`, dev `mission`. Mẫu để biến Helm `realm.name`, mặc định `okrkpi`; cần chốt một tên rồi sửa discovery APISIX và `issuer` của web.
2. **Thời hạn token** lệch ba nơi. Đã chọn: access 5 phút (TL-04), refresh trượt theo nhàn rỗi 30 phút, tối đa 8 giờ, SSO tối đa 10 giờ, refresh dùng một lần. Cần chốt.
3. **Khoá tài khoản:** Keycloak chỉ có một khoảng xoá bộ đếm. Đã đặt 24 giờ, khoá tạm 15 phút ở lần sai thứ 5, khoá cố định ở lần thứ 20, nên chặt hơn BR-00-05 ("5 lần trong 15 phút"). Cần xác nhận hoặc làm authenticator riêng.
4. Thông điệp Keycloak không có tham số `{n}` và `{giờ}` của MSG-00-01, MSG-00-02; theme WEB-020 phải bổ sung.
5. Realm có Organization thì Keycloak 26 đăng nhập hai bước (tên đăng nhập rồi mật khẩu); WEB-020 và DV-02-03 phải thiết kế theo.
6. Redirect URI không dùng ký tự đại diện tên miền con: nếu mỗi tenant có tên miền riêng, tenant-registry phải đăng ký URI khi khởi tạo tenant, hoặc dùng một tên miền web chung cho mỗi cell. Câu hỏi mở.
7. **Mức Go:** dùng `go 1.25` + `toolchain go1.26.9` thay cho baseline "Go 1.23", vì GO-2026-5004 (pgx), GO-2026-5970, GO-2026-6629 (x/text) chỉ vá ở bản yêu cầu Go ≥ 1.25. Đề nghị NEN-017 và mọi service Go dùng cùng mức.
8. Trạng thái tenant: TL-03 có 8 (thêm `DANG_CHUYEN_CELL`), TL-02a có 7. Đã theo TL-03; huỷ hồ sơ khởi tạo hiện thực là `KHOI_TAO → DA_CHAM_DUT` (suy luận).
9. `registry.tenant_cell.tenancy_model` chữ thường và thiếu `AIRGAP`, nên `createRegistryTenant` với AIRGAP trả 422.
10. `/config/tenants*` có `x-mission.owner: MOD-08` nhưng hiện thực ở SVC-03; đề nghị sửa owner trong hợp đồng và để cổng toàn cục định tuyến tiền tố này tới SVC-03. Tiền tố `/registry` không có trong baseline mục 13.
11. `resolveTenantCell` trả 422 theo thẻ task, hợp đồng chỉ khai 400.
12. Ngưỡng bridge 1.000.000 (TL-02a) so với 150.000 (TL-01): tham số `TENANT_BRIDGE_MAX`, mặc định theo TL-02a. Biên 85%: đúng 85% được nhận. "Khối Đảng Trung ương" không có cột trong TL-03 nên dùng danh sách mã `TENANT_SILO_CODES`; khối lực lượng vũ trang xác định theo `sector`.
13. Hai bảng bổ sung `registry.tenant_onboarding`, `registry.job` cần đưa vào TL-03.
14. Lược đồ `ConfigTenantPatch` không cho `null` và đối tượng lồng một phần, trái RFC 7396; máy chủ nhận theo RFC 7396, đề nghị sửa hợp đồng. Ví dụ trong hợp đồng lệch baseline (CELL-HN là BRIDGE, `dukcqtw` DANG + BRIDGE trái BR-01-01, mã hồ sơ `KT-2026-0042` khác `ONB-yyyy-nnnn`).
15. `org.scim_sync_log` phân vùng theo `received_at` nên không đặt được khoá ngoại vật lý từ `sync_discrepancy`; giữ tham chiếu logic có chỉ mục (suy luận). TL-01 gọi `org.position_assignment`, đã dùng tên TL-03 `org.assignment`.
16. Tiền thân khi sáp nhập: TL-02a ghi `PREDECESSOR` trong `org_unit_relation` nhưng CHECK của TL-03 không có giá trị này; thẻ DV-04-03 chọn lưu trong `org_change_order.payload`.

Cho wiki 07: thêm đoạn tóm tắt cấu hình mẫu realm production (ý 2, 3 ở trên, Argon2id 12–128 ký tự, cấm 5 mật khẩu gần nhất, client web/di động chỉ Code + PKCE S256, `apisix-gateway` chỉ kiểm token, nạp bằng keycloak-config-cli qua Job Helm). Cho wiki 05a: SCR-0101, SCR-0102 bước 1–3 đã có API thật ở SVC-03; bước wizard suy từ trạng thái tenant; cell tự chọn theo tải thấp nhất; tệp xuất dùng nhãn trạng thái tiếng Việt.
