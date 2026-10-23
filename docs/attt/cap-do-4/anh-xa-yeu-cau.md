> Nguồn: TL-04 mục 9.7 (bảng nhóm yêu cầu TCVN 11930 và bảng tám yêu cầu cấp độ 4), bảng tổng hợp SEC mục 9.2, mục 7.6 (PERF-16, PERF-17), mục 8 (DR-14), bảng OPS (OPS-18, OPS-22); `TIEN-DO-HE-THONG.md` cho trạng thái task. Ngày đánh giá: 23/10/2026.

# Ánh xạ yêu cầu TCVN 11930:2017 cấp độ 4

Tài liệu này gồm ba bảng. Bảng 1 giữ nguyên chín nhóm yêu cầu của TCVN 11930 theo TL-04 mục 9.7, kèm trạng thái tổng hợp của từng nhóm. Bảng 2 trình bày tám yêu cầu bổ sung đặc trưng của cấp độ 4, mỗi yêu cầu trỏ tới một trang bằng chứng riêng. Bảng 3 đi vào chi tiết từng mã SEC, DR, OPS, PERF được nhắc tới ở hai bảng trên. Mỗi dòng của bảng 3 ghi biện pháp, bằng chứng truy được trong repo và trạng thái theo quy ước trong [README](README.md).

Bằng chứng được ghi bằng đường dẫn tương đối tính từ gốc repo và đặt trong dấu backtick. Script `kiem-lien-ket.py` dùng chính các đường dẫn này để kiểm rằng chúng còn tồn tại. Một dòng chưa có bằng chứng trong repo thì ghi "chưa có" và có mục tương ứng trong [danh sách thiếu hụt](thieu-hut.md).

## Bảng 1. Chín nhóm yêu cầu TCVN 11930

Trạng thái tổng hợp của một nhóm là trạng thái thấp nhất trong các mã thuộc nhóm đó ở bảng 3. Như vậy, nhóm chỉ được ghi "Đáp ứng" khi mọi mã của nhóm đều đáp ứng.

| Nhóm yêu cầu TCVN 11930 | Tiêu chí bản đánh giá | Mã SEC, DR, OPS đáp ứng | Trạng thái tổng hợp | Điểm còn thiếu chính |
|---|---|---|---|---|
| Xác thực | D2, D3, D4, D5 | SEC-02, SEC-03, SEC-04, SEC-08, SEC-13 đến SEC-17, SEC-54 | Một phần | Báo cáo tỷ lệ MFA trên môi trường thật; phát hiện đăng nhập bất thường mới ở mức quy tắc, chưa nối SIEM |
| Kiểm soát truy cập | D6 | SEC-18, SEC-29, SEC-42, SEC-58 | Một phần | PAM có ghi phiên chưa triển khai (INF-27) |
| Nhật ký hệ thống | D9, G5 | SEC-07, SEC-22, SEC-30, SEC-43, SEC-50, SEC-53; luồng kiểm toán riêng (mục 10.3) | Một phần | Nhật ký kiểm toán API Kubernetes, Falco và bộ chuyển tiếp `sys.siem.v1` tới SIEM chưa có (INF-19); chưa có hợp đồng SOC |
| Bảo mật đường truyền | D8 | SEC-20, SEC-21, SEC-57, SEC-59 | Đáp ứng (giả lập) | Kiểm tra bắt gói trên cụm thật |
| Chống chối bỏ | I4, I8 | Sổ cái, ký số, dấu thời gian (ADR-09); neo ngoài (mục 8.8); PERF-16, PERF-17 | Một phần | Đo PERF-16, PERF-17 hoãn theo YC-PT-19; dịch vụ dấu thời gian thật chờ tích hợp |
| Ứng dụng và mã nguồn | D1, D7, D10 | SEC-01, SEC-05, SEC-06, SEC-09, SEC-19, SEC-23, SEC-37 đến SEC-41 | Một phần | Kiểm thử xâm nhập độc lập SEC-41 chưa thực hiện; ASVS đạt 197/241 mục |
| Toàn vẹn dữ liệu | I1 đến I5 | Sổ cái chuỗi băm, kiểm tra hằng ngày (OPS-22), DR-14; Object Lock | Một phần | Diễn tập DR-14 (QA-15) chưa thực hiện |
| Bảo mật dữ liệu | D8, D12, E5 | SEC-11, SEC-25, SEC-31, SEC-52, SEC-58 | Một phần | Mã hoá Secret trong etcd bằng KMS v2 phụ thuộc cấu hình cụm thật; HSM thật chờ mua sắm |
| Sao lưu dự phòng | C5 | SEC-44, chương 8, OPS-18 | Một phần | INF-17, INF-18 đang làm; diễn tập khôi phục QA-14, QA-15 chưa thực hiện |

## Bảng 2. Tám yêu cầu bổ sung đặc trưng của cấp độ 4

| Yêu cầu cấp độ 4 | Đáp ứng theo TL-04 | Bằng chứng TL-04 yêu cầu | Trang bằng chứng | Trạng thái |
|---|---|---|---|---|
| MFA bắt buộc | SEC-13: mọi người dùng, không có nhánh bỏ qua; người vận hành dùng WebAuthn | Cấu hình Keycloak trong Git; báo cáo tỷ lệ người dùng có MFA = 100% | [01-mfa.md](bang-chung/01-mfa.md) | Một phần |
| Khoá theo khách hàng trong HSM | SEC-11: KEK theo tenant, khoá gốc trong HSM; khách hàng giữ thẻ M trong N | Danh sách khoá Transit; biên bản giao thẻ | [02-khoa-hsm.md](bang-chung/02-khoa-hsm.md) | Đáp ứng (giả lập) |
| mTLS | SEC-21, SEC-57 | Kiểm tra bắt gói; cấu hình PeerAuthentication | [03-mtls.md](bang-chung/03-mtls.md) | Đáp ứng (giả lập) |
| PAM có ghi phiên | SEC-42: mọi truy cập đặc quyền tới Kubernetes, CSDL, máy chủ, HSM qua PAM; ghi hình; lưu 1 năm | Báo cáo phiên PAM hằng tháng | [04-pam.md](bang-chung/04-pam.md) | Thiếu |
| SOC 24/7 | SEC-43: SOC giám sát qua SIEM, nhận luồng `sec.*`; phản hồi sự cố Critical ≤ 15 phút | Hợp đồng dịch vụ; báo cáo SOC hằng tháng | [05-soc.md](bang-chung/05-soc.md) | Một phần |
| Sao lưu 3-2-1 bất biến | SEC-44: 3-2-1-1-0, Object Lock compliance | Chương 8; biên bản diễn tập | [06-sao-luu.md](bang-chung/06-sao-luu.md) | Một phần |
| SBOM | SEC-23: CycloneDX cho mọi ảnh, lưu trong Dependency-Track | Danh sách SBOM theo phiên bản đang chạy | [07-sbom.md](bang-chung/07-sbom.md) | Đáp ứng (giả lập) |
| Build có chữ ký | SEC-37, SEC-38: cosign, provenance SLSA mức 3, Kyverno từ chối ảnh không ký | Nhật ký từ chối của Kyverno; kiểm tra ngẫu nhiên chữ ký | [08-build-ky.md](bang-chung/08-build-ky.md) | Đáp ứng (giả lập) |

## Bảng 3. Chi tiết từng mã

Bảng sắp theo nhóm yêu cầu của bảng 1. Một mã thuộc nhiều nhóm thì chỉ xuất hiện ở nhóm đầu tiên. Cột "Task" ghi task đã hoặc đang làm biện pháp đó, theo `TIEN-DO-HE-THONG.md`.

### Xác thực

| Mã | Biện pháp | Bằng chứng trong repo | Task | Trạng thái |
|---|---|---|---|---|
| SEC-02 | Không có tài khoản ẩn hay nhánh cấp token không mật khẩu; xác thực tập trung ở Keycloak | `deploy/charts/iam/files/realm/realm-cell.yaml`; `backend/services/iam/realm-template/src/test/java/vn/viettel/okrkpi/iam/realm/RealmTemplateStaticTest.java`; `infra/security/kiem-thu-phu-dinh/test/01-xac-thuc.test.mjs` | DV-02-01, QA-13 | Đáp ứng |
| SEC-03 | Danh sách tuyến công khai khớp chính xác đường dẫn và phương thức; APISIX là điểm kiểm JWT duy nhất | `deploy/charts/api-gateway/templates/public-routes.yaml`; `infra/security/kiem-thu-phu-dinh/test/01-xac-thuc.test.mjs` | DV-01-03, QA-13 | Đáp ứng |
| SEC-04 | Không lưu mật khẩu phía trình duyệt; luồng OIDC Authorization Code với PKCE | `frontend/projects/auth/src/lib/session.service.ts`; `frontend/projects/auth/src/lib/token-storage.ts`; `infra/security/kiem-thu-phu-dinh/test/06-luong-oidc.test.mjs` | WEB-006, QA-13 | Đáp ứng |
| SEC-08 | Token không nằm trong URL; cookie có Secure và SameSite | `frontend/projects/auth/src/lib/token-storage.ts`; `frontend/projects/auth/src/lib/token-storage.spec.ts` | WEB-006 | Đáp ứng |
| SEC-13 | MFA bắt buộc cho mọi người dùng (SmartOTP, WebAuthn, VNeID mức 2) | Xem [01-mfa.md](bang-chung/01-mfa.md) | DV-02-03 | Một phần |
| SEC-14 | Chống dò mật khẩu: khoá tạm, giới hạn tốc độ | `deploy/charts/iam/files/realm/realm-cell.yaml` (`bruteForceProtected`); `deploy/charts/api-gateway/templates/_rate-limit.tpl`; `backend/services/iam/realm-template/src/test/java/vn/viettel/okrkpi/iam/realm/RealmTemplateKeycloakIT.java` | DV-02-01, INF-08 | Đáp ứng |
| SEC-15 | Băm mật khẩu Argon2id có salt | `deploy/charts/iam/files/realm/realm-cell.yaml` (`passwordPolicy`); `backend/services/iam/realm-template/src/test/java/vn/viettel/okrkpi/iam/realm/RealmTemplateKeycloakIT.java` | DV-02-01 | Đáp ứng |
| SEC-16 | Access token 5 phút, refresh 8 giờ, thu hồi ngay | `deploy/charts/iam/values.yaml`; `deploy/environments/production/iam/values-cell-01.yaml` | DV-02-01 | Đáp ứng |
| SEC-17 | PKCE, state, nonce cho mọi client OIDC | `deploy/charts/iam/files/realm/realm-cell.yaml`; `backend/services/iam/realm-template/src/test/java/vn/viettel/okrkpi/iam/realm/RealmTemplateStaticTest.java`; `frontend/projects/auth/src/lib/session.service.ts` | DV-02-01, DV-02-04, WEB-006 | Đáp ứng |
| SEC-54 | Phát hiện đăng nhập bất thường | `backend/services/iam/keycloak-events/src/main/java/vn/viettel/okrkpi/iam/events/MissionEventListenerProvider.java`; `backend/opa/okrkpi/generated/data.json` | DV-02-05 | Một phần: sự kiện đăng nhập đã phát ra Kafka, quy tắc phát hiện ở SIEM chưa có (SEC-43) |

### Kiểm soát truy cập

| Mã | Biện pháp | Bằng chứng trong repo | Task | Trạng thái |
|---|---|---|---|---|
| SEC-18 | Phân quyền tập trung bằng OPA, ABAC theo phạm vi tổ chức; giao diện ẩn chức năng theo ma trận | `backend/opa/okrkpi/authz.rego`; `backend/opa/okrkpi/authz_test.rego`; `docs/phan-quyen/ma-tran.json`; `frontend/projects/auth/src/lib/permission.service.ts` | NEN-014, NEN-040, YC-PT-21 | Đáp ứng |
| SEC-29 | RBAC tối thiểu, không dùng cluster-admin hằng ngày; thao tác vận hành qua công cụ có kiểm soát | `deploy/argocd/values.yaml`; `services-go/vtctl/internal/runner/runner.go` | INF-04, INF-26 | Một phần: chưa có RBAC theo người cho cụm thật |
| SEC-42 | PAM ghi phiên mọi truy cập đặc quyền | chưa có (xem [04-pam.md](bang-chung/04-pam.md)) | INF-27 | Thiếu |
| SEC-58 | DBA không đọc được dữ liệu C2; quy trình phá kính | `backend/libs/vt-platform-starter/src/main/java/vn/viettel/okrkpi/shared/crypto/FieldEncryptor.java`; `deploy/openbao/cau-hinh.sh`; `backend/libs/retention/src/main/java/vn/viettel/okrkpi/platform/retention/RestoreTool.java`; `ops/runbooks/luu-tru-xoa-khoi-phuc.md` | NEN-015, INF-13 | Đáp ứng (giả lập) |

### Nhật ký hệ thống

| Mã | Biện pháp | Bằng chứng trong repo | Task | Trạng thái |
|---|---|---|---|---|
| SEC-07 | Không ghi token, refresh token, thân yêu cầu vào nhật ký | `backend/libs/vt-platform-starter/src/main/java/vn/viettel/okrkpi/shared/observability/Redactor.java`; `.semgrep/vt-rules.yml`; `frontend/projects/auth/src/lib/token-storage.ts` | NEN-016, INF-05 | Đáp ứng |
| SEC-22 | Nhật ký sự kiện an ninh đẩy về SIEM | `backend/services/iam/keycloak-events/src/main/java/vn/viettel/okrkpi/iam/events/KafkaSink.java`; `backend/libs/vt-platform-starter/src/main/java/vn/viettel/okrkpi/shared/audit/DefaultAuditLogger.java`; `deploy/charts/vt-platform/ha-tang/kafka/files/danh-muc.yaml` | DV-02-05, NEN-016, INF-10 | Một phần: sự kiện đã vào Kafka, bộ chuyển tiếp `siem-forwarder` chưa có |
| SEC-30 | Nhật ký kiểm toán API Kubernetes | chưa có | INF-19 | Thiếu |
| SEC-43 | SOC 24/7 và SIEM | Xem [05-soc.md](bang-chung/05-soc.md) | INF-19, INF-24 | Một phần |
| SEC-50 | Phát hiện hành vi bất thường lúc chạy (Falco, Tetragon) | `deploy/charts/observability-cell/rules/canh-bao-toi-thieu.yaml` (đã có cảnh báo dựa trên sự kiện Falco) | INF-15, INF-19 | Một phần: chưa triển khai Falco trong cụm |
| SEC-53 | Che thông tin cá nhân trong nhật ký ở ứng dụng và ở Fluent Bit | `backend/libs/vt-platform-starter/src/main/java/vn/viettel/okrkpi/shared/observability/RedactingRewritePolicy.java`; `deploy/charts/observability-cell/files/redact.lua` | NEN-016, INF-14 | Đáp ứng |
| Luồng kiểm toán (mục 10.3) | `sys.audit.v1` lưu vào kho bất biến Object Lock COMPLIANCE 10 năm, lô Parquet ký số, kiểm tra hằng ngày | `services-go/audit-archiver/cmd/audit-archiver/main.go`; `deploy/charts/audit-archiver/values.yaml`; `deploy/charts/audit-archiver/templates/kiem-tra-hang-ngay.yaml`; `deploy/environments/kind/kiem-thu-audit-archiver.sh`; `ops/runbooks/audit-archiver.md` | INF-24 | Đáp ứng (giả lập) |

### Bảo mật đường truyền

| Mã | Biện pháp | Bằng chứng trong repo | Task | Trạng thái |
|---|---|---|---|---|
| SEC-20 | Chỉ dùng thuật toán được phê duyệt; cấm trust-all, DES, MD5 | `.semgrep/vt-rules.yml`; `.semgrep/vt-rules-go.yml`; `backend/services/integration-gateway/src/main/java/vn/viettel/okrkpi/integ/infrastructure/crypto/JcaPackageCrypto.java` | INF-05, DV-13-02 | Đáp ứng |
| SEC-21 | mTLS STRICT giữa các service | Xem [03-mtls.md](bang-chung/03-mtls.md) | INF-09 | Đáp ứng (giả lập) |
| SEC-57 | TLS và ACL cho Kafka, Valkey, PostgreSQL, OpenSearch | `deploy/charts/vt-platform/ha-tang/data-pg/templates/clusters.yaml`; `deploy/charts/vt-platform/ha-tang/kafka/templates/users.yaml`; `deploy/charts/vt-platform/ha-tang/valkey/values.yaml`; `deploy/environments/kind/kiem-thu-su-kien.sh` | INF-10, INF-11 | Đáp ứng (giả lập) |
| SEC-59 | cert-manager với PKI của OpenBao | `deploy/openbao/chinh-sach/cert-manager.hcl`; `ops/runbooks/openbao-pki.md` | INF-13 | Đáp ứng (giả lập) |

### Chống chối bỏ

| Mã | Biện pháp | Bằng chứng trong repo | Task | Trạng thái |
|---|---|---|---|---|
| ADR-09 | Sổ cái chuỗi băm, ký số các bước đánh giá, dấu thời gian | `services-go/ledger/README.md`; `services-go/ledger/internal/server/ledger_api.go`; `backend/services/esign-service/pom.xml` | DV-09-02, DV-10-02 | Đáp ứng (giả lập): dịch vụ chữ ký số từ xa và dấu thời gian thật qua bản giả lập (YC-PT-13) |
| Neo ngoài (mục 8.8) | Niêm phong sổ cái định kỳ và neo ra ngoài | `services-go/ledger/internal/bundle/bundle.go`; `deploy/charts/vt-platform/ha-tang/kafka/files/avro/ledger/SoCaiDaNiemPhong.avsc` | DV-09-03 | Một phần: đích neo ngoài thật chưa xác định |
| PERF-16 | Outbox tới sổ cái 1.800 sự kiện/giây, niêm phong mỗi giờ và mỗi 5 phút | chưa có (kịch bản ở QA-10) | QA-10 | Hoãn (YC-PT-19) |
| PERF-17 | Ký lô 100.000 mục | chưa có (kịch bản ở QA-10) | QA-10 | Hoãn (YC-PT-19) |

### Ứng dụng và mã nguồn

| Mã | Biện pháp | Bằng chứng trong repo | Task | Trạng thái |
|---|---|---|---|---|
| SEC-01 | Không có bí mật trong mã; bí mật lấy từ OpenBao | `.gitleaks.toml`; `deploy/openbao/chinh-sach/external-secrets.hcl`; `infra/security/kiem-thu-phu-dinh/test/01-xac-thuc.test.mjs` | INF-05, INF-13 | Đáp ứng |
| SEC-05 | Truy vấn tham số hoá, chặn nối chuỗi SQL | `.semgrep/vt-rules.yml`; `infra/security/kiem-thu-phu-dinh/test/02-da-khach-thue.test.mjs` | INF-05, QA-13 | Đáp ứng |
| SEC-06 | CORS theo danh sách origin, chống CSRF | `deploy/charts/api-gateway/values.yaml`; `services-go/ledger/db/migration/ledger/V2__ledger_partitions_rls_and_roles.sql` | INF-08 | Đáp ứng |
| SEC-09 | gitleaks ở pre-commit, pipeline và quét định kỳ | `.gitleaks.toml`; `deploy/web/ci/gitleaks.toml`; `ci/vt-pipeline/vars/vtBuoc.groovy` | INF-05 | Đáp ứng |
| SEC-19 | Chống XSS: mã hoá đầu ra, CSP nghiêm ngặt, Trusted Types | `deploy/web/csp-mac-dinh.conf`; `deploy/web/nginx.conf`; `infra/security/kiem-thu-phu-dinh/test/05-tieu-de-va-loi.test.mjs` | WEB-014, QA-13 | Đáp ứng |
| SEC-23 | Quét thành phần và SBOM CycloneDX | Xem [07-sbom.md](bang-chung/07-sbom.md) | INF-05, INF-06 | Đáp ứng (giả lập) |
| SEC-37 | Chuỗi cung ứng SLSA mức 3 | Xem [08-build-ky.md](bang-chung/08-build-ky.md) | INF-05 | Đáp ứng (giả lập) |
| SEC-38 | Ký ảnh và chứng thực bằng cosign | Xem [08-build-ky.md](bang-chung/08-build-ky.md) | INF-05, INF-07 | Đáp ứng (giả lập) |
| SEC-39 | SAST Semgrep chặn merge | `.semgrep/vt-rules.yml`; `deploy/web/ci/semgrep.yml`; `ci/vt-pipeline/vars/vtJavaPipeline.groovy` | INF-05 | Đáp ứng |
| SEC-40 | DAST OWASP ZAP | `ci/vt-pipeline/vars/vtZapScan.groovy`; `infra/security/zap/okr-api.yaml`; `infra/security/zap/web-baseline.yaml`; `docs/attt/asvs-4.0.3-tu-danh-gia.md` | QA-13 | Một phần: chạy cục bộ, chưa chạy hằng đêm trên staging |
| SEC-41 | Kiểm thử xâm nhập độc lập | chưa có; đầu vào là `docs/attt/asvs-4.0.3-tu-danh-gia.md` | Ngoài phạm vi dự án | Thiếu |

### Toàn vẹn dữ liệu

| Mã | Biện pháp | Bằng chứng trong repo | Task | Trạng thái |
|---|---|---|---|---|
| Sổ cái chuỗi băm | Mỗi mục sổ cái băm nối mục trước; API kiểm chuỗi | `services-go/ledger/internal/server/verify_api.go`; `ops/runbooks/doi-soat-so-cai.md` | DV-09-05 | Đáp ứng |
| OPS-22 | Kiểm tra chuỗi băm hằng ngày | `deploy/charts/ledger-service/templates/reconcile-alerts.yaml`; `deploy/charts/backup-cell/files/kiem-so-cai.sql`; `deploy/charts/audit-archiver/templates/kiem-tra-hang-ngay.yaml` | DV-09-05, INF-17, INF-24 | Đáp ứng (giả lập) |
| DR-14 | Chuỗi băm sổ cái bị đứt: phát hiện ≤ 24 giờ, điều tra ≤ 72 giờ | `deploy/charts/ledger-service/templates/reconcile-alerts.yaml`; `ops/runbooks/doi-soat-so-cai.md` | QA-15 | Một phần: chưa có biên bản diễn tập |
| Object Lock | Minh chứng và nhật ký kiểm toán trong bucket Object Lock COMPLIANCE | `deploy/environments/kind/kiem-thu-kho-doi-tuong.sh`; `deploy/charts/audit-archiver/values.yaml`; `deploy/charts/rgw-cell/values-kho-sao-luu.yaml` | INF-12, INF-24 | Đáp ứng (giả lập) |

### Bảo mật dữ liệu

| Mã | Biện pháp | Bằng chứng trong repo | Task | Trạng thái |
|---|---|---|---|---|
| SEC-11 | Mã hoá phong bì, DEK theo tenant, KEK trong OpenBao Transit, khoá gốc trong HSM | Xem [02-khoa-hsm.md](bang-chung/02-khoa-hsm.md) | NEN-015, INF-13 | Đáp ứng (giả lập) |
| SEC-25 | Mã hoá đầu cuối dữ liệu C3 | `backend/services/org-service/src/main/java/vn/viettel/okrkpi/org/application/e2ee/E2eeLedger.java`; `backend/services/org-service/src/main/java/vn/viettel/okrkpi/org/application/e2ee/RecoveryKeyService.java`; `contracts/openapi/modules/org.yaml` | CORE-07-06, CORE-02-24 | Đáp ứng |
| SEC-31 | Mã hoá Secret trong etcd bằng KMS v2 | `ops/runbooks/sao-luu-kubernetes.md` | INF-08 | Một phần: cấu hình máy chủ API Kubernetes thuộc cụm thật |
| SEC-52 | Đánh giá tác động dữ liệu cá nhân; che dữ liệu ngoài sản xuất | Xem [du-lieu-ca-nhan.md](du-lieu-ca-nhan.md) | DL, INF-21 | Một phần |

### Sao lưu dự phòng

| Mã | Biện pháp | Bằng chứng trong repo | Task | Trạng thái |
|---|---|---|---|---|
| SEC-44 | Sao lưu 3-2-1-1-0 bất biến | Xem [06-sao-luu.md](bang-chung/06-sao-luu.md) | INF-17, INF-18 | Một phần |
| OPS-18 | Diễn tập khôi phục theo ngày, quý, năm (`restore-check`) | `deploy/charts/backup-cell/templates/restore-check.yaml`; `deploy/charts/backup-cell/files/restore-check.sh`; `ops/runbooks/sao-luu-postgresql.md` | INF-17, QA-14 | Một phần: kiểm khôi phục hằng ngày đã có, diễn tập quý và năm chưa có biên bản |

### Các mã cấp độ 4 khác trong bảng SEC

Bảng SEC của TL-04 còn một số mã gắn nhãn "Cấp độ 4" hoặc C04 nhưng không được liệt kê trong chín nhóm. Hồ sơ vẫn đưa các mã này vào để cơ quan thẩm định có cái nhìn đầy đủ.

| Mã | Biện pháp | Bằng chứng trong repo | Task | Trạng thái |
|---|---|---|---|---|
| SEC-10 | OpenBao và External Secrets, secret động cho CSDL | `deploy/openbao/chinh-sach/external-secrets.hcl`; `deploy/charts/iam/templates/externalsecret.yaml`; `ops/runbooks/openbao-van-hanh.md` | INF-13 | Đáp ứng (giả lập) |
| SEC-12 | Xoay vòng khoá và bí mật định kỳ | `backend/libs/key-rotation/src/main/java/vn/viettel/okrkpi/platform/keyrotation/KekRewrapJob.java`; `deploy/openbao/chinh-sach/xoay-khoa.hcl`; `ops/runbooks/xoay-vong-khoa-du-lieu.md` | NEN-015, INF-13 | Đáp ứng (giả lập) |
| SEC-24 | Header bảo mật HTTP | `deploy/web/security-headers.conf`; `deploy/charts/api-gateway/tests/kiem-thu-kind.py` | INF-08 | Đáp ứng |
| SEC-26 | Pod Security Standards mức restricted | `deploy/charts/cell-base/templates/namespaces.yaml`; `deploy/charts/cell-base/tests/chay-kiem-thu.sh` | INF-08 | Đáp ứng (giả lập) |
| SEC-27 | Kiểm tra CIS bằng kube-bench | `infra/airgap/goi/tools/kube-bench-job.yaml`; `infra/airgap/goi/tools/kiem-tra-sau-cai.sh` | INF-23 | Một phần: mới chạy trong gói cài cách ly, chưa định kỳ |
| SEC-28 | Chính sách Kyverno bắt buộc | `deploy/policies/vt-pod-hardening.yaml`; `deploy/policies/vt-require-signed-images.yaml`; `deploy/environments/kind/kiem-thu-chinh-sach.sh` | INF-07 | Đáp ứng (giả lập) |
| SEC-32 | NetworkPolicy mặc định từ chối | `deploy/charts/cell-base/values.yaml`; `deploy/environments/kind/kiem-thu-mang-cell.sh` | INF-08 | Đáp ứng (giả lập) |
| SEC-33 | Phân vùng mạng theo vùng bảo mật | `deploy/charts/cell-base/values.yaml`; [thuyet-minh.md](thuyet-minh.md) mục 4 | INF-08 | Đáp ứng (giả lập) |
| SEC-34 | WAF Coraza với OWASP CRS | `deploy/environments/production/api-gateway/values.yaml`; `deploy/charts/api-gateway/files/vt-waf-body.lua` | INF-08 | Đáp ứng (giả lập) |
| SEC-35 | Chống DDoS nhiều lớp | `deploy/charts/api-gateway/templates/_rate-limit.tpl` (lớp 7) | INF-08 | Một phần: lớp 3 và lớp 4 là dịch vụ của nhà cung cấp |
| SEC-36 | Kiểm soát lưu lượng ra ngoài | `deploy/charts/cell-egress/templates/policies.yaml`; `deploy/environments/kind/kiem-thu-mang-cell.sh` | INF-09 | Đáp ứng (giả lập) |
| SEC-45 | Gói cài đặt ngoại tuyến có chữ ký | `infra/airgap/dong-goi.sh`; `infra/airgap/goi/tools/kiem-goi.sh` | INF-23 | Một phần: INF-23 đang làm |
| SEC-46 | Mật mã do Ban Cơ yếu Chính phủ cấp cho triển khai quốc phòng | chưa có | DV-10-05, mua sắm | Thiếu: chỉ áp dụng khi triển khai cho khối quốc phòng |
| SEC-47 | Quét mã độc tệp tải lên bằng ClamAV | `backend/services/evidence-service/src/main/java/vn/viettel/okrkpi/evidence/application/MalwareScanner.java`; `backend/services/evidence-service/src/main/java/vn/viettel/okrkpi/evidence/application/ScanService.java` | DV-08-04 | Đáp ứng |
| SEC-48 | Giới hạn tải lên, kiểm tra nội dung thật | `backend/services/evidence-service/src/main/java/vn/viettel/okrkpi/evidence/domain/ContentInspector.java`; `deploy/charts/evidence-service/values.yaml` | DV-08-02 | Đáp ứng |
| SEC-49 | Ảnh distroless, không root, hệ tệp chỉ đọc | `deploy/policies/vt-pod-hardening.yaml`; `deploy/charts/vt-service/templates/_defaults.tpl` | INF-01, INF-07 | Đáp ứng (giả lập) |
| SEC-51 | Vá hệ điều hành node tự động (kured) | chưa có | INF-19 | Thiếu |
| SEC-55 | Chỉ dùng ảnh theo digest | `deploy/environments/production/digests.yaml`; `deploy/policies/vt-require-signed-images.yaml` | INF-04, INF-07 | Đáp ứng (giả lập) |
| SEC-56 | Bảo vệ etcd: mạng riêng, mTLS, mã hoá đĩa | `deploy/charts/backup-cell/templates/etcd.yaml` (sao lưu etcd) | INF-08 | Thiếu: cấu hình etcd thuộc cụm thật, chưa có bằng chứng |

## Liên quan

- [Thuyết minh](thuyet-minh.md), [Danh sách thiếu hụt](thieu-hut.md)
- `docs/attt/asvs-4.0.3-tu-danh-gia.md`
