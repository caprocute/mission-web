> Tệp sinh tự động bằng `python3 scripts/sinh-danh-muc.py` từ `docs/tasks/danh-muc-he-thong.tsv`; sửa TSV rồi chạy lại, không sửa tay tệp này.

# Danh mục task toàn hệ thống Mission

Danh mục có 336 task ngoài các task web WEB-001..065 đã có trong `docs/tasks/README.md`. Tổng khối lượng quy đổi là 408 đơn vị (S = 0,5; M = 1; L = 2), đường găng dài 23 task với 31.5 đơn vị. Cột "Tầng" là số bước phụ thuộc dài nhất tính từ gốc: các task cùng tầng và khác luồng có thể chạy song song. Thẻ task chi tiết nằm cạnh tệp này, theo mẫu `_MAU-TASK-HE-THONG.md`. Trạng thái thật của từng task xem trong git và `docs/tasks/README.md` do thread điều phối cập nhật.

## Đường găng

NEN-001 → [NEN-005](NEN-005-khung-maven-backend.md) → [NEN-030](NEN-030-quy-uoc-flyway-lint.md) → [NEN-031](NEN-031-vai-tro-csdl-theo-schema.md) → [NEN-033](NEN-033-rls-phan-vung-pgbouncer.md) → [NEN-010](NEN-010-starter-ngu-canh-tenant.md) → [NEN-019](NEN-019-mau-service-java.md) → [CORE-00-01](CORE-00-01-khung-mission-core.md) → [CORE-00-02](CORE-00-02-tam-module-modulith.md) → [CORE-05-01](CORE-05-01-migration-schema-policy.md) → [CORE-05-02](CORE-05-02-phien-ban-goi-chinh-sach.md) → [CORE-05-03](CORE-05-03-policy-query-api.md) → [CORE-08-13](CORE-08-13-form-schema-api.md) → [CORE-08-14](CORE-08-14-kiem-tra-json-schema-cel.md) → [CORE-04-04](CORE-04-04-phieu-tu-danh-gia.md) → [CORE-04-05](CORE-04-05-tham-dinh-de-xuat.md) → [CORE-04-07](CORE-04-07-quyet-dinh-hang-loat.md) → [CORE-04-08](CORE-04-08-ky-so-khoa-ho-so.md) → [CORE-04-10](CORE-04-10-xep-loai-nam.md) → [QA-06](QA-06-hanh-trinh-danh-gia.md) → [QA-07](QA-07-hanh-trinh-giam-sat.md) → [QA-16](QA-16-uat-tenant-thi-diem.md) → [VH-08](VH-08-thi-diem-chuyen-tenant.md)

## Luồng song song

| Luồng | Phạm vi | Số task | Khối lượng |
|---|---|---|---|
| DP | Điều phối (thread chính) | 1 | 0.5 |
| B1 | Nền móng backend và CSDL | 30 | 32 |
| B2 | Cấu hình, Policy Pack, OKR | 47 | 51 |
| B3 | Kế hoạch, giao việc, minh chứng | 41 | 46 |
| B4 | Đánh giá, hồ sơ số, giám sát, tính điểm | 41 | 48.5 |
| B5 | Định danh, tổ chức, tenant, tích hợp | 38 | 46 |
| B6 | Quy trình, sổ cái, ký số, tài liệu | 27 | 39 |
| B7 | Thông báo, tìm kiếm, phân tích, AI | 25 | 29 |
| B8 | Hạ tầng, CI/CD, quan sát, dự phòng | 28 | 44 |
| B9 | Kiểm thử tích hợp, hiệu năng, ATTT, DR | 18 | 26.5 |
| B10 | Di động | 10 | 10.5 |
| B11 | Dữ liệu nghiệp vụ, vận hành | 20 | 24.5 |
| W | Phân hệ web (các luồng web hiện có) | 10 | 10.5 |

## NEN · Nền móng hệ thống: repo, quy định, wiki, khung mã, thư viện nền, nền CSDL

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| NEN-001 | Chuyển repo thành repo chung toàn hệ thống: cấu trúc thư mục, AGENTS.md mở rộng, quy định theo phân hệ, prompt agent | S | – | ADR-01, BS-02 | DP | 1 | Đã làm trong nhánh kế hoạch tổng thể |
| [NEN-002](NEN-002-wiki-backend-phan-1.md) | Wiki backend phần 1: trang 16 kiến trúc mission-core và microservice, trang 17 thiết kế CSDL và đa khách thuê | M | NEN-001 | TL-01 ch.5, ch.12; TL-03 ch.1-5 | B1 | 2 |  |
| [NEN-003](NEN-003-wiki-backend-phan-2.md) | Wiki backend phần 2: trang 18 sự kiện và đồng bộ dữ liệu, trang 19 hạ tầng production và triển khai, trang 20 bảo mật phía máy chủ | M | NEN-001 | TL-01 ch.6, ch.16; TL-03 ch.10-11; TL-04 ch.3, ch.9 | B1 | 2 |  |
| [NEN-004](NEN-004-diem-lech-backend.md) | Ghi các điểm lệch và câu hỏi mở phía backend vào wiki trang 14 và lập đề xuất chốt | S | NEN-001 | YC-TK-10 | B1 | 2 |  |
| [NEN-005](NEN-005-khung-maven-backend.md) | Khung Maven đa module `backend/`: POM cha, BOM phiên bản theo TL-01 PL B, Java 21, Spotless, JaCoCo, PIT, Jib, cấu hình Sonar | M | NEN-001 | TL-01 PL B, ch.18; ADR-16 | B1 | 2 |  |
| [NEN-006](NEN-006-sinh-ma-tu-openapi.md) | Sinh giao diện máy chủ từ hợp đồng OpenAPI (openapi-generator Spring interfaceOnly) và kiểm `openapi-diff` springdoc với `contracts/` | M | NEN-005 | ADR-32, YC-PT-05, YC-PT-09 | B1 | 3 |  |
| [NEN-007](NEN-007-luoc-do-avro-su-kien.md) | Lược đồ Avro cho EVT-01..EVT-26 và topic kỹ thuật, sinh lớp Java, kiểm tương thích bằng plugin Maven Apicurio | M | NEN-005 | baseline §7; TL-03 ch.12 | B1 | 3 |  |
| [NEN-008](NEN-008-asyncapi-topic-cong-bo.md) | AsyncAPI 3.0 cho các topic công bố, lint trong CI | S | NEN-007 | TL-01 ch.6 AsyncAPI | B1 | 4 |  |
| [NEN-009](NEN-009-starter-kernel-loi.md) | vt-platform-starter: nhân `kernel` (TenantId, PeriodId, Clock, UUIDv7, Result) và lỗi RFC 9457 theo bảng mã lỗi chuẩn | M | NEN-005 | TL-01 ch.14 RFC 9457; HD-01 | B1 | 3 |  |
| [NEN-010](NEN-010-starter-ngu-canh-tenant.md) | vt-platform-starter: ngữ cảnh tenant từ JWT, TenantFilter, đặt `app.tenant_id` cục bộ giao dịch qua PgBouncer | M | NEN-009, NEN-033 | ADR-07, ADR-22 | B1 | 6 |  |
| [NEN-011](NEN-011-starter-outbox-writer.md) | vt-platform-starter: OutboxWriter, EventEnvelope, tuần tự hoá Avro qua Apicurio | M | NEN-007, NEN-032 | ADR-03 | B1 | 6 |  |
| [NEN-012](NEN-012-starter-consumer-inbox.md) | vt-platform-starter: consumer Kafka chuẩn với inbox idempotent, thử lại có backoff, DLQ | M | NEN-011 | ADR-03; TL-03 inbox | B1 | 7 |  |
| [NEN-013](NEN-013-starter-idempotency-etag.md) | vt-platform-starter: kho Idempotency-Key 48 giờ, ETag/If-Match, phân trang con trỏ, job 202 | M | NEN-009, NEN-032 | baseline §13; HD-02 | B1 | 6 |  |
| [NEN-014](NEN-014-starter-opa-step-up.md) | vt-platform-starter: OpaClient, `@RequiresPermission`, đánh giá từng phần thành điều kiện SQL, kiểm step-up `acr`/`auth_time` | L | NEN-009, NEN-040 | ADR-21; TL-01 ch.16 | B1 | 4 |  |
| [NEN-015](NEN-015-starter-ma-hoa-truong.md) | vt-platform-starter: CryptoProvider, FieldEncryptor AES-256-GCM phong bì, DekCache 15 phút, chỉ mục mù HMAC, client OpenBao Transit | L | NEN-009, NEN-032 | ADR-06, ADR-17, ADR-30 | B1 | 6 |  |
| [NEN-016](NEN-016-starter-chiu-loi-quan-sat.md) | vt-platform-starter: Resilience4j chuẩn, timeout mặc định, OpenTelemetry, log ECS JSON có che dữ liệu, tách nhật ký kiểm toán | M | NEN-009 | ADR-12, ADR-24 | B1 | 4 |  |
| [NEN-017](NEN-017-thu-vien-go-dung-chung.md) | Thư viện Go dùng chung: tenant, outbox/inbox với pgx và franz-go, lỗi RFC 9457, OpenTelemetry | M | NEN-007, NEN-032, NEN-009 | TL-01 SVC-03/09/12 | B1 | 6 |  |
| [NEN-018](NEN-018-thu-vien-python-dung-chung.md) | Thư viện Python dùng chung cho ai-gateway và job phân tích: tenant, OTel, lỗi, Kafka consumer | S | NEN-007, NEN-009 | TL-01 SVC-14, SVC-15 | B1 | 4 |  |
| [NEN-019](NEN-019-mau-service-java.md) | Mẫu service Java (lục giác, actuator, probe, Helm values) và lệnh `scripts/new-service.sh` | M | NEN-010, NEN-011, NEN-012, NEN-016 | TL-04 ch.5 | B1 | 8 |  |
| [NEN-020](NEN-020-moi-truong-dev-backend.md) | Môi trường dev backend: bổ sung profile Kafka, Apicurio, Debezium, OPA, OpenBao, Valkey vào `infra/dev`, chuẩn hoá tài khoản CSDL `svc_/mig_/ro_/cdc_`, cấu hình Testcontainers dùng chung | M | NEN-031, NEN-007, NEN-040, NEN-033 | YC-HT-01; TL-03 ch.2 | B1 | 6 | Đụng `infra/dev`, phối hợp luồng điều phối |
| [NEN-030](NEN-030-quy-uoc-flyway-lint.md) | Quy ước Flyway theo schema, tài khoản `mig_<schema>`, lint tên, kiểu và chú thích phân loại cột trong CI | M | NEN-005 | TL-03 ch.1, ch.15 | B1 | 3 |  |
| [NEN-031](NEN-031-vai-tro-csdl-theo-schema.md) | Vai trò CSDL và quyền mặc định theo schema (svc, ro, mig, cdc) cùng các ngoại lệ | M | NEN-030 | TL-03 ch.2 | B1 | 4 |  |
| [NEN-032](NEN-032-bang-dung-chung-mau.md) | Bảng dùng chung mẫu: `outbox_event` phân vùng, `inbox_processed`, `inbox_aggregate_version`, `idempotency_key`, schema `modulith`, `keyring.dek` | M | NEN-030, NEN-031 | TL-03 ch.5 bảng dùng chung | B1 | 5 |  |
| [NEN-033](NEN-033-rls-phan-vung-pgbouncer.md) | Mẫu RLS chuẩn, hàm `fn_create_period_partitions`, cấu hình PgBouncer chế độ giao dịch | L | NEN-031 | ADR-07, ADR-22; TL-03 ch.4 | B1 | 5 |  |
| [NEN-034](NEN-034-ro-ri-tenant-csdl.md) | Kiểm thử rò rỉ tenant lớp CSDL: RLS giữa hai tenant, PgBouncer dùng lại kết nối, phân vùng | M | NEN-033, NEN-010 | TL-03 kiểm thử rò rỉ | B1 | 7 |  |
| [NEN-035](NEN-035-ro-ri-tenant-api-cache.md) | Kiểm thử rò rỉ tenant lớp API, bộ đệm Valkey, chỉ mục tìm kiếm và kho phân tích | M | NEN-034, CORE-00-02 | TL-03 kiểm thử rò rỉ | B9 | 11 |  |
| [NEN-036](NEN-036-dinh-tuyen-csdl-tenant.md) | Định tuyến tới đúng CSDL theo mô hình silo, bridge, pool (`registry.tenant_cell`, mã 421) | M | NEN-033, DV-03-02, NEN-012 | ADR-07; TL-03 định tuyến | B1 | 9 |  |
| [NEN-040](NEN-040-goi-chinh-sach-opa.md) | Gói chính sách OPA (Rego) sinh từ `docs/phan-quyen/ma-tran.json`: RBAC, ABAC theo cây tổ chức, luồng, mức dữ liệu, tách vai trò, bốn mắt; kiểm thử `opa test` | L | NEN-001 | ADR-21; wiki/15, 15a | B1 | 2 |  |
| [NEN-041](NEN-041-thu-vien-cel-dung-chung.md) | Thư viện CEL dùng chung `vt-cel` (cel-java): môi trường, hàm mở rộng `sum`, `ngay_lam_viec`, `min_muc`, bộ ca kiểm thử chung với cel-js | L | NEN-005 | ADR-19 | B2 | 3 |  |
| [NEN-021](NEN-021-luoc-do-topic-ky-thuat.md) | Lược đồ Avro cho sự kiện kỹ thuật `evidence.object.*` và các topic kỹ thuật còn thiếu | S | NEN-007 | TL-03 ch.11; TL-01 SVC-08 | B1 | 4 | Bổ sung sau khi viết thẻ |
| [NEN-022](NEN-022-phat-lai-outbox-connector.md) | Công cụ phát lại outbox và cấu hình connector Debezium outbox theo schema | M | NEN-011 | ADR-03; TL-03 ch.11 | B1 | 7 | Bổ sung sau khi viết thẻ |
| [NEN-042](NEN-042-sua-hop-dong-nghiep-vu.md) | Sửa hợp đồng OpenAPI phần nghiệp vụ: thêm thao tác còn thiếu (huỷ OKR, phiên bản điều chỉnh mục tiêu, uỷ quyền check-in, kích hoạt danh mục, chấm việc ngoài danh mục, ngoại lệ điều chỉnh, xác nhận dòng kế hoạch, duyệt nhiệm vụ đăng ký, chuyển điểm nghẽn, danh bạ khoá E2EE và chia sẻ CEK, ghi trường hợp đặc thù, gợi ý khiếu nại trùng) và sửa mô tả `acceptComplaint` | M | NEN-001 | ADR-32; YC-PT-09 | B1 | 2 | Bổ sung sau khi viết thẻ; người phụ trách hợp đồng duyệt |
| [NEN-043](NEN-043-sua-hop-dong-nen-tang.md) | Sửa hợp đồng OpenAPI phần nền tảng: cờ tính năng cho mọi người dùng, cấu hình tenant, thiết bị nhận thông báo đẩy, tuỳ chọn kênh, mã khôi phục MFA, `POST /org/assignments`, guest token Superset, kiểm quyền theo lô cho tìm kiếm, ký nội bộ cho sổ cái, OCR cho tài khoản dịch vụ, đề nghị chuyển cell | M | NEN-001, NEN-042 | ADR-32; YC-PT-09 | B1 | 3 | Bổ sung sau khi viết thẻ; người phụ trách hợp đồng duyệt |

## CORE-00 · mission-core: phần dùng chung

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [CORE-00-01](CORE-00-01-khung-mission-core.md) | Khung ứng dụng mission-core: Spring Boot 3.5, luồng ảo, tám DataSource riêng theo module, actuator, cấu hình mẫu | L | NEN-019, NEN-031, NEN-006, NEN-013, NEN-030 | SVC-05; TL-01 ch.5 | B2 | 9 |  |
| [CORE-00-02](CORE-00-02-tam-module-modulith.md) | Tám module lục giác, khai báo Spring Modulith, ma trận phụ thuộc, ArchUnit, sinh tài liệu module | M | CORE-00-01 | ADR-01, ADR-23 | B2 | 10 |  |
| [CORE-00-03](CORE-00-03-org-replica-consumer.md) | Schema `org_replica` và consumer EVT-14, EVT-15, EVT-16 | M | CORE-00-02, NEN-012, DV-04-03 | baseline §8.3 | B2 | 12 |  |
| [CORE-00-04](CORE-00-04-api-trang-chu-hom-nay.md) | API tổng hợp trang chủ "Việc của tôi hôm nay" (SCR-0002) | M | CORE-02-18, CORE-01-08, CORE-08-09, CORE-08-02, NEN-041 | QT-00 → SCR-0002 | B3 | 18 |  |
| [CORE-00-05](CORE-00-05-nhat-ky-truy-cap-c2.md) | Nhật ký truy cập dữ liệu C2 và ghi sổ cái lần giải mã | S | NEN-015, DV-09-02, NEN-011, NEN-016, CORE-00-02 | ADR-06 | B4 | 11 |  |

## CORE-08 · MOD-08 config: kỳ, lịch, cờ, UI Pack, biểu mẫu (G1 phía máy chủ)

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [CORE-08-01](CORE-08-01-migration-schema-config.md) | Migration schema `config` (period, holiday_calendar, tenant_setting, feature_flag, ui_pack, ui_pack_version, form_schema, component_registry, role_binding, period_lock_order) | M | CORE-00-02, NEN-033 | MOD-08; TL-03 schema config | B2 | 11 |  |
| [CORE-08-02](CORE-08-02-ky-danh-gia-period-api.md) | Kỳ đánh giá: domain, máy trạng thái kỳ, tạo kỳ có lịch và ghim Policy Pack, `PeriodApi` | M | CORE-08-01, CORE-05-03 | QT-08; baseline §9.1 | B2 | 14 |  |
| [CORE-08-03](CORE-08-03-chan-ghi-ky-khoa.md) | Chặn ghi khi kỳ `DANG_KHOA`/`DA_KHOA` ở mọi module, đệm trạng thái kỳ, lỗi 409 `period-locked` | M | CORE-08-02 | BR-08-06 | B2 | 15 |  |
| [CORE-08-04](CORE-08-04-lich-ngay-lam-viec.md) | Lịch ngày làm việc, ngày nghỉ, làm bù và thư viện tính ngày làm việc dùng chung | M | CORE-08-01 | BR-06-01 | B2 | 12 |  |
| [CORE-08-05](CORE-08-05-lenh-khoa-ky.md) | Lệnh khoá kỳ: lập, duyệt có MFA và tách vai trò, khởi chạy saga | M | CORE-08-02, DV-06-05 | BR-08-01, BR-08-02 | B2 | 15 |  |
| [CORE-08-06](CORE-08-06-mo-lai-hau-kiem.md) | Mở lại hậu kiểm: trạng thái `MO_LAI_HAU_KIEM`, phạm vi mở, consumer EVT-22, timer hết hạn khoá lại | L | CORE-08-03 | QT-13; EVT-22 | B2 | 16 |  |
| [CORE-08-07](CORE-08-07-cau-hinh-tenant-co-tinh-nang.md) | Cấu hình tenant và cờ tính năng (`tenant_setting`, `feature_flag`) kèm API | S | CORE-08-01 | MOD-08 | B2 | 12 |  |
| [CORE-08-08](CORE-08-08-ui-pack-phien-ban.md) | UI Pack: mô hình có phiên bản, máy trạng thái, người duyệt khác người soạn | M | CORE-08-01 | QT-18; ADR-05 | B2 | 12 |  |
| [CORE-08-09](CORE-08-09-component-registry-sdui.md) | Component registry có phiên bản và kiểm schema bố cục SDUI | M | CORE-08-08, NEN-041 | ADR-05 | B2 | 13 |  |
| [CORE-08-10](CORE-08-10-kiem-tra-ui-pack.md) | Kiểm tra tự động UI Pack: tương phản AA và 7:1, kích thước ≤ 30 KB, làm sạch SVG | M | CORE-08-08, WEB-004 | NFR-07, NFR-11 | B2 | 13 |  |
| [CORE-08-11](CORE-08-11-phat-hanh-ui-pack.md) | Biên dịch token sang CSS và preset PrimeNG, ký UI Pack, đẩy CDN với ETag, `GET /ui/packs/current`, EVT-23 | L | CORE-08-09, CORE-08-10, DV-10-01, WEB-004 | EVT-23; ADR-05 | B2 | 14 |  |
| [CORE-08-12](CORE-08-12-quay-lui-ui-pack.md) | Quay lui UI Pack có kiểm tương thích phiên bản Policy Pack | S | CORE-08-11, CORE-05-03 | ADR-25 | B2 | 15 |  |
| [CORE-08-13](CORE-08-13-form-schema-api.md) | API `form_schema`: lưu, phục vụ theo phiên bản, kiểm trường tính điểm theo Policy Pack | M | CORE-08-08, CORE-05-03 | QT-18 → SCR-1802 | B2 | 14 |  |
| [CORE-08-14](CORE-08-14-kiem-tra-json-schema-cel.md) | Bộ kiểm tra JSON Schema có `x-cel`, `x-classification` dùng chung cho API nghiệp vụ, đối chiếu cel-js | L | CORE-08-13, NEN-041 | ADR-05, ADR-19 | B2 | 15 |  |
| [CORE-08-15](CORE-08-15-manifest-plugin-tenant.md) | Manifest plugin theo tenant và kiểm chữ ký bản dựng plugin | S | CORE-08-11 | ADR-05 lớp 4 | B2 | 15 |  |
| [CORE-08-16](CORE-08-16-phan-cong-vai-tro-uy-quyen.md) | Lưu phân công vai trò và uỷ quyền (`config.role_binding`) theo đề xuất WEB-058 | M | CORE-08-01, NEN-040, NEN-043 | WEB-058 | B2 | 12 | Chờ người dùng duyệt đề xuất WEB-058 |
| [CORE-08-17](CORE-08-17-luu-dinh-nghia-dashboard.md) | Lưu định nghĩa dashboard `config.dashboard_def` và cổng `api` cho analytics-api | S | CORE-08-01 | SCR-1602; MOD-08 | B2 | 12 | Bổ sung sau khi viết thẻ |

## CORE-05 · MOD-05 policy: Policy Pack

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [CORE-05-01](CORE-05-01-migration-schema-policy.md) | Migration schema `policy` và trigger đóng băng phiên bản đã phát hành | M | CORE-00-02, NEN-033 | MOD-05; TL-03 schema policy | B2 | 11 |  |
| [CORE-05-02](CORE-05-02-phien-ban-goi-chinh-sach.md) | Mô hình phiên bản gói, nhân bản, máy trạng thái, kế thừa theo cờ `overridable` | M | CORE-05-01 | QT-15; baseline §9.1 | B2 | 12 |  |
| [CORE-05-03](CORE-05-03-policy-query-api.md) | `PolicyQueryApi` và API phân giải nội bộ có bộ đệm, cung cấp `task_lifecycle` và tham số theo tenant/khối/kỳ | M | CORE-05-02 | ADR-19 | B2 | 13 |  |
| [CORE-05-04](CORE-05-04-quy-tac-cel-tham-so.md) | API quy tắc CEL và tham số, kiểm kiểu theo khai báo ngữ cảnh | M | CORE-05-02, NEN-041 | SCR-1501 | B2 | 13 |  |
| [CORE-05-05](CORE-05-05-bang-quyet-dinh-dmn.md) | Bảng quyết định DMN 1.4: lưu, kiểm FEEL, mẫu câu giải thích, xuất XML | L | CORE-05-02 | SCR-1502; ADR-19 | B2 | 13 |  |
| [CORE-05-06](CORE-05-06-ca-kiem-thu-vang.md) | Ca kiểm thử vàng và ký duyệt ca | M | CORE-05-02, DV-10-01 | SCR-1503 | B2 | 13 |  |
| [CORE-05-07](CORE-05-07-chay-kiem-thu-chinh-sach.md) | Chạy kiểm thử 202: cel-java, đối chiếu cel-js, kiểm thử thuộc tính jqwik | L | CORE-05-04, CORE-05-05, CORE-05-06 | SCR-1503 | B2 | 14 |  |
| [CORE-05-08](CORE-05-08-so-sanh-xem-truoc.md) | So sánh phiên bản và đánh giá xem trước | S | CORE-05-04 | QT-15 | B2 | 14 |  |
| [CORE-05-09](CORE-05-09-trinh-phe-duyet-ky-so.md) | Trình và phê duyệt: người duyệt khác người soạn, ký số, ghi sổ cái | M | CORE-05-07, DV-10-01 | QT-15 | B2 | 15 |  |
| [CORE-05-10](CORE-05-10-saga-phat-hanh-chinh-sach.md) | Phát hành: saga đa cell có bù trừ, `policy_binding`, EVT-13 | L | CORE-05-09, DV-06-05, CORE-08-12 | EVT-13 | B2 | 16 |  |
| [CORE-05-11](CORE-05-11-cong-bo-workflow-bindings.md) | Lớp công bố `/policies/.../workflow-bindings` thuộc MOD-05, gọi API `wf_binding` của workflow-service | S | CORE-05-02, DV-06-08 | SCR-1504 | B2 | 13 | Bổ sung sau khi viết thẻ |

## CORE-01 · MOD-01 okr

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [CORE-01-01](CORE-01-01-migration-schema-okr.md) | Migration schema `okr` (basis, objective, key_result, okr_link, kr_checkin) và phiên bản mục tiêu | M | CORE-00-02, NEN-033 | MOD-01; TL-03 schema okr | B2 | 11 |  |
| [CORE-01-02](CORE-01-02-okrapi-doc-cay-tien-do.md) | `OkrApi` công bố và API đọc cây, bảng, bản đồ liên kết, tính tiến độ KR và mục tiêu | M | CORE-01-01 | SCR-0201 | B2 | 12 |  |
| [CORE-01-03](CORE-01-03-soan-muc-tieu-kr.md) | API soạn mục tiêu và KR: If-Match, sinh mã, sao chép kỳ trước | M | CORE-01-02, CORE-08-03 | SCR-0202 | B2 | 16 |  |
| [CORE-01-04](CORE-01-04-kiem-tra-quy-tac-okr.md) | Kiểm tra quy tắc BR-02-01..07 ở máy chủ, danh sách từ mơ hồ, trọng số 100% | M | CORE-01-03, NEN-041 | BR-02-01..07 | B2 | 17 |  |
| [CORE-01-05](CORE-01-05-trinh-duyet-okr-bpmn.md) | Trình duyệt OKR qua BPMN `duyet-okr`, chọn người duyệt và người thay từ `wf_binding` | L | CORE-01-03, DV-06-03, CORE-01-04 | SCR-0203 | B2 | 18 |  |
| [CORE-01-06](CORE-01-06-duyet-tra-lai-okr.md) | Duyệt, trả lại, rút, huỷ, duyệt hàng loạt 202, EVT-01 | M | CORE-01-05, NEN-042 | EVT-01; BR-02-08, 09 | B2 | 19 |  |
| [CORE-01-07](CORE-01-07-dieu-chinh-muc-tieu.md) | Điều chỉnh mục tiêu giữa kỳ bằng phiên bản mới | S | CORE-01-06, NEN-042 | BR-02-10 | B2 | 20 |  |
| [CORE-01-08](CORE-01-08-check-in-kr.md) | Check-in KR: bảng chỉ thêm, nháp và gửi idempotent, tính lại giá trị và tiến độ | M | CORE-01-02 | QT-03 → SCR-0301; BR-03-01..05 | B2 | 13 |  |
| [CORE-01-09](CORE-01-09-co-rui-ro-kr.md) | Cờ rủi ro KR và EVT-02 | S | CORE-01-08 | EVT-02; BR-03-06 | B2 | 14 |  |
| [CORE-01-10](CORE-01-10-so-lieu-nguon-kr.md) | Lấy số liệu nguồn của KR qua cổng tích hợp, ghi đè có lý do | M | CORE-01-08, DV-13-08 | BR-03-04 | B2 | 14 |  |
| [CORE-01-11](CORE-01-11-nhac-check-in-han-okr.md) | Nhắc check-in, nhắc trước hạn và quá hạn OKR | S | CORE-01-08, DV-12-01 | BR-03-08 | B2 | 14 |  |
| [CORE-01-12](CORE-01-12-uy-quyen-check-in.md) | Uỷ quyền check-in thay và chuyển giá trị khi phiên bản mục tiêu mới được duyệt | S | CORE-01-08, CORE-01-07, NEN-042 | QT-03 | B2 | 21 |  |
| [CORE-01-13](CORE-01-13-tro-ly-ai-okr.md) | Trợ lý AI cho OKR qua ai-gateway: soát KR, ghi `ai.suggestion_log`, cờ `content_source` | M | CORE-01-04, DV-14-04 | QT-17 → SCR-1701; BR-02-11 | B2 | 18 |  |

## CORE-02 · MOD-02 task: danh mục, kế hoạch, nhiệm vụ

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [CORE-02-01](CORE-02-01-migration-danh-muc-ke-hoach.md) | Migration schema `task` phần danh mục và kế hoạch (work_catalog, catalog_item, plan, plan_version, plan_item) | M | CORE-00-02, NEN-033 | MOD-02; TL-03 schema task | B3 | 11 |  |
| [CORE-02-02](CORE-02-02-migration-nhiem-vu.md) | Migration schema `task` phần nhiệm vụ (task, task_assignee, task_progress, task_blocker, task_change_request, task_source_doc, task_state_log) có phân vùng | M | CORE-02-01 | MOD-02; TL-03 schema task | B3 | 12 |  |
| [CORE-02-03](CORE-02-03-danh-muc-cong-viec.md) | Danh mục công việc có phiên bản, kế thừa cấp trên, API mục danh mục | M | CORE-02-01, CORE-08-03 | QT-04 → SCR-0401; BR-04-13, 14 | B3 | 16 |  |
| [CORE-02-04](CORE-02-04-nhap-danh-muc-excel.md) | Nhập danh mục từ Excel (202, tệp lỗi) | S | CORE-02-03 | SCR-0401 | B3 | 17 |  |
| [CORE-02-05](CORE-02-05-ke-hoach-quy.md) | Kế hoạch quý: máy trạng thái `plan_version`, API soạn, sao chép quý trước, gợi ý từ OKR | L | CORE-02-03, CORE-01-02 | SCR-0402 | B3 | 17 |  |
| [CORE-02-06](CORE-02-06-kiem-tra-ke-hoach.md) | Kiểm tra kế hoạch BR-04-02..07 và tỷ trọng nhóm theo Policy Pack | M | CORE-02-05, CORE-05-03 | BR-04-02..07 | B3 | 18 |  |
| [CORE-02-07](CORE-02-07-han-duyet-ke-hoach.md) | Tính hạn duyệt kế hoạch theo lịch ngày làm việc, nhắc trước hạn và quá hạn | S | CORE-02-05, CORE-08-04 | BR-04-01 | B3 | 18 |  |
| [CORE-02-08](CORE-02-08-gui-duyet-ke-hoach.md) | Gửi duyệt kế hoạch: khoá nội dung, băm SHA-256, BPMN `duyet-ke-hoach`, trả lại | M | CORE-02-06, DV-06-03 | SCR-0403 | B3 | 19 |  |
| [CORE-02-09](CORE-02-09-duyet-ke-hoach-ky-so.md) | Duyệt kế hoạch có ký số (EVT-18 rồi EVT-03), hàng đợi thử lại | L | CORE-02-08, DV-10-02 | EVT-03, EVT-18; BR-04-08, 09 | B3 | 20 |  |
| [CORE-02-10](CORE-02-10-dieu-chinh-ke-hoach.md) | Điều chỉnh kế hoạch: phiên bản n+1, so sánh, lý do từng thay đổi, giới hạn số lần | L | CORE-02-09, CORE-02-12 | BR-04-10..12 | B3 | 21 |  |
| [CORE-02-11](CORE-02-11-sinh-nhiem-vu-tu-ke-hoach.md) | Consumer EVT-03 sinh, cập nhật, huỷ nhiệm vụ; xác nhận dòng của đơn vị chủ trì khác | M | CORE-02-09, CORE-02-13 | EVT-03, EVT-06 | B3 | 21 |  |
| [CORE-02-12](CORE-02-12-taskapi-cong-bo.md) | `TaskApi` công bố và listener nội bộ cho MOD-04 | S | CORE-02-13 | ADR-23 | B3 | 16 |  |
| [CORE-02-13](CORE-02-13-may-trang-thai-nhiem-vu.md) | Máy trạng thái nhiệm vụ khai báo, bảng chuyển trạng thái từ Policy Pack, guard CEL, `task_state_log`, EVT-06 | L | CORE-02-02, CORE-05-03, NEN-041, DL-04 | ADR-04 tầng 1; EVT-06 | B3 | 15 |  |
| [CORE-02-14](CORE-02-14-giao-viec.md) | Giao việc: nháp, sửa, xoá, giao đơn lẻ và đồng loạt 202, kiểm BR-05-01..05, BR-05-09, EVT-04 | L | CORE-02-13, CORE-00-03 | QT-05 → SCR-0501; EVT-04 | B3 | 16 |  |
| [CORE-02-15](CORE-02-15-nhiem-vu-gan-giong.md) | Phát hiện nhiệm vụ gần giống và gộp có lý do | M | CORE-02-14, DV-16-01 | BR-05-06 | B3 | 17 |  |
| [CORE-02-16](CORE-02-16-nhiem-vu-tu-van-ban.md) | Nhiệm vụ từ văn bản chỉ đạo: consumer EVT-24, trích bằng AI, xác nhận và khoá đoạn | L | CORE-02-14, DV-13-03, DV-14-04 | SCR-0502; EVT-24; BR-05-05, 07 | B3 | 17 |  |
| [CORE-02-17](CORE-02-17-dang-ky-nhiem-vu.md) | Đăng ký nhiệm vụ, duyệt hoặc từ chối, đưa vào điều chỉnh kế hoạch | M | CORE-02-14, NEN-042 | BR-05-08 | B3 | 17 |  |
| [CORE-02-18](CORE-02-18-viec-cua-toi.md) | Việc của tôi: danh sách con trỏ, lọc nhanh, nhận việc, xuất 202, cảnh báo khối lượng | M | CORE-02-14 | SCR-0503; BR-05-11 | B3 | 17 |  |
| [CORE-02-19](CORE-02-19-cap-nhat-tien-do.md) | Cập nhật tiến độ chỉ thêm, EVT-05, xung đột 409, cập nhật hàng loạt | M | CORE-02-13 | QT-06 → SCR-0601; BR-06-02, 03 | B3 | 16 |  |
| [CORE-02-20](CORE-02-20-diem-nghen.md) | Điểm nghẽn: tạo, giao xử lý, chuyển cấp trên, đóng | S | CORE-02-19, NEN-042 | QT-06 | B3 | 17 |  |
| [CORE-02-21](CORE-02-21-de-nghi-gia-han.md) | Đề nghị gia hạn, điều chỉnh: máy trạng thái năm trạng thái, gửi, rút, xem trước hệ số C | M | CORE-02-19, CORE-08-04 | SCR-0602 | B3 | 17 |  |
| [CORE-02-22](CORE-02-22-duyet-de-nghi.md) | Duyệt đề nghị, tạm dừng, tiếp tục, huỷ; vượt ngưỡng chuyển BPMN `duyet-de-nghi`, người duyệt thay | L | CORE-02-21, DV-06-03 | BR-06-04..11 | B3 | 18 |  |
| [CORE-02-23](CORE-02-23-co-qua-han.md) | Tác vụ 00:05 đặt cờ `qua_han`, nhắc, tính lại khi lịch nghỉ đổi | S | CORE-02-19, CORE-08-04 | QT-06 | B3 | 17 |  |
| [CORE-02-24](CORE-02-24-noi-dung-mat-c3-nhiem-vu.md) | Nội dung mật C3 của nhiệm vụ: `task.task_secret_content`, `task.e2ee_key_wrap`, chia sẻ và thu hồi khoá | M | CORE-02-02, CORE-07-06, NEN-042 | ADR-27; C3 | B3 | 22 | Bổ sung sau khi viết thẻ |
| [CORE-02-25](CORE-02-25-nhac-viec-giao-lai.md) | Nhắc việc, giao lại (`remindTask`, `reassignTask`) và đổi người chủ trì khi điều động | M | CORE-02-14, CORE-00-03 | QT-16; QT-05 | B3 | 17 | Bổ sung sau khi viết thẻ |
| [CORE-02-26](CORE-02-26-cong-ghi-delay-risk.md) | Cột `delay_risk` và cổng ghi nội bộ cho dự báo nguy cơ trễ | S | CORE-02-02 | QT-06 | B3 | 13 | Bổ sung sau khi viết thẻ |

## CORE-03 · MOD-03 product: sản phẩm, minh chứng, nghiệm thu

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [CORE-03-01](CORE-03-01-schema-product.md) | Migration schema `product` và trigger đóng băng phiên bản minh chứng | M | CORE-00-02, NEN-033 | MOD-03; TL-03 schema product | B3 | 11 |  |
| [CORE-03-02](CORE-03-02-may-trang-thai-minh-chung.md) | Máy trạng thái phiên bản minh chứng và liên động với máy trạng thái nhiệm vụ | M | CORE-03-01, CORE-02-13 | BR-07-10; baseline §9.1 | B3 | 16 |  |
| [CORE-03-03](CORE-03-03-nop-phien-ban-minh-chung.md) | Khai báo sản phẩm, lưu nháp và nộp phiên bản, consumer `evidence.object.stored`, EVT-07 | L | CORE-03-02, DV-08-02 | QT-07 → SCR-0701; EVT-07; BR-07-03 | B3 | 17 |  |
| [CORE-03-04](CORE-03-04-minh-chung-van-ban-voffice.md) | Minh chứng là văn bản đã ban hành trên vOffice, kiểm chữ ký số gốc | M | CORE-03-03, DV-13-03 | BR-07-13 | B3 | 18 |  |
| [CORE-03-05](CORE-03-05-ocr-goi-y-nhiem-vu.md) | OCR và trích thông tin minh chứng, gợi ý nhiệm vụ khớp | M | CORE-03-03, DV-08-05 | BR-07-09 | B3 | 18 |  |
| [CORE-03-06](CORE-03-06-mo-nghiem-thu-toan-ven.md) | Mở nghiệm thu và kiểm toàn vẹn tệp | S | CORE-03-03 | SCR-0703 | B3 | 18 |  |
| [CORE-03-07](CORE-03-07-nghiem-thu-nam-muc.md) | Nghiệm thu năm mức: AAL2, tách vai trò, nhận xét C2, EVT-08 | L | CORE-03-06, NEN-014, NEN-015 | SCR-0703; EVT-08; BR-07-06..08, 11 | B3 | 19 |  |
| [CORE-03-08](CORE-03-08-nhac-qua-han-nghiem-thu.md) | Nhắc quá hạn nghiệm thu và nghiệm thu thay theo uỷ quyền | S | CORE-03-07, CORE-08-04, CORE-08-16 | BR-07-12 | B3 | 20 |  |

## CORE-04 · MOD-04 appraisal: đánh giá, xếp loại

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [CORE-04-01](CORE-04-01-schema-appraisal.md) | Migration schema `appraisal` và trigger `period_state` bảo vệ dữ liệu kỳ đã khoá | L | CORE-00-02, NEN-033 | MOD-04; TL-03 schema appraisal | B4 | 11 |  |
| [CORE-04-02](CORE-04-02-lap-ho-so-quy.md) | Consumer EVT-09 lập hồ sơ quý theo tổ chức tại thời điểm; consumer EVT-10 lưu `score_snapshot` | M | CORE-04-01, CORE-00-03 | EVT-09, EVT-10 | B4 | 13 |  |
| [CORE-04-03](CORE-04-03-may-trang-thai-ho-so.md) | Máy trạng thái `appraisal_profile` và chặn chuyển bước thiếu lý do | M | CORE-04-01 | baseline §9.1 | B4 | 12 |  |
| [CORE-04-04](CORE-04-04-phieu-tu-danh-gia.md) | Phiếu tự đánh giá: lưu nháp If-Match, kiểm tiêu chí chung bằng cel-java, nộp 202 chờ EVT-18 | L | CORE-04-03, CORE-08-14, DV-10-02 | QT-09 → SCR-0901 | B4 | 16 |  |
| [CORE-04-05](CORE-04-05-tham-dinh-de-xuat.md) | Thẩm định, trả lại tối đa hai lần, đề xuất, tách vai trò qua OPA | M | CORE-04-04 | SCR-0902; BR-09-14 | B4 | 17 |  |
| [CORE-04-06](CORE-04-06-api-dau-vao-tinh-diem.md) | API đọc lô `inputs` cho scoring và listener nhiệm vụ kết thúc | M | CORE-04-01, CORE-02-12 | SVC-07 | B4 | 17 |  |
| [CORE-04-07](CORE-04-07-quyet-dinh-hang-loat.md) | Quyết định xếp loại hàng loạt: lô, step-up, job 202, huỷ và tiếp tục, kiểm phân bố mức | L | CORE-04-05, DV-10-06 | SCR-0904 | B4 | 18 |  |
| [CORE-04-08](CORE-04-08-ky-so-khoa-ho-so.md) | Consumer EVT-18 chuyển `DA_KY_SO`, phát EVT-12; timer hạn khiếu nại chuyển `DA_KHOA` | M | CORE-04-07, CORE-08-04 | EVT-12, EVT-18 | B4 | 19 |  |
| [CORE-04-09](CORE-04-09-vet-tinh-diem.md) | Tra cứu vết tính điểm, điểm theo luồng, xuất XLSX | S | CORE-04-02, DV-07-03 | SCR-0903 | B4 | 16 |  |
| [CORE-04-10](CORE-04-10-xep-loai-nam.md) | Xếp loại năm: lập từ bốn quý, xử lý thiếu quý, chặn khi còn khiếu nại, tính lại | L | CORE-04-08, DV-07-07 | QT-10 → SCR-1001 | B4 | 20 |  |
| [CORE-04-11](CORE-04-11-co-bo-tri-lai.md) | Cờ "không hoàn thành hai năm liền" và đề xuất bố trí lại sang MOD-02 | S | CORE-04-10 | QT-10 | B4 | 21 |  |
| [CORE-04-12](CORE-04-12-phan-nhom-trien-vong.md) | Phân nhóm triển vọng: lưu nháp, BR-10-07..09, trình tập thể, ký danh sách | M | CORE-04-10 | SCR-1002 | B4 | 21 |  |

## CORE-06 · MOD-06 dossier: kiểm điểm, hồ sơ số

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [CORE-06-01](CORE-06-01-schema-dossier.md) | Migration schema `dossier` | M | CORE-00-02, NEN-033 | MOD-06; TL-03 schema dossier | B4 | 11 |  |
| [CORE-06-02](CORE-06-02-hoi-nghi-kiem-diem.md) | Hội nghị kiểm điểm: tạo, thành phần, quyền thư ký và chủ trì, gửi dự thảo | M | CORE-06-01, CORE-08-04 | QT-11 → SCR-1101 | B4 | 13 |  |
| [CORE-06-03](CORE-06-03-gop-y-an-danh.md) | Góp ý mã hoá C2 và chế độ ẩn danh qua OPA | S | CORE-06-02, NEN-015 | SCR-1101 | B4 | 14 |  |
| [CORE-06-04](CORE-06-04-ket-thuc-hoi-nghi.md) | Kết thúc hội nghị: biểu quyết, sinh biên bản 202, mục khắc phục | M | CORE-06-03, DV-11-01 | SCR-1101 | B4 | 15 |  |
| [CORE-06-05](CORE-06-05-truong-hop-dac-thu.md) | Phát hiện 13 trường hợp đặc thù theo bảng trong Policy Pack | L | CORE-06-01, CORE-05-03, CORE-00-03 | QT-11 | B4 | 14 |  |
| [CORE-06-06](CORE-06-06-tao-goi-ho-so.md) | Tạo gói hồ sơ khi có EVT-12, theo dõi job sinh biểu mẫu, sinh lại tệp lỗi | M | CORE-06-01, CORE-04-08, DV-11-04 | SCR-1102; EVT-12, EVT-17 | B4 | 20 |  |
| [CORE-06-07](CORE-06-07-ky-khoa-goi.md) | Manifest SHA-256, danh sách kiểm tra, ký gói, khoá `DA_NOP`, phiên bản gói mới khi EVT-22 | M | CORE-06-06, DV-10-02 | SCR-1102 | B4 | 21 |  |
| [CORE-06-08](CORE-06-08-xuat-goi-edxml.md) | Xuất gói ZIP có manifest ký và gửi gói con diện Trung ương qua EdXML | M | CORE-06-07, DV-13-05 | SCR-1102 | B4 | 22 |  |

## CORE-07 · MOD-07 oversight: khiếu nại, hậu kiểm, cảnh báo

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [CORE-07-01](CORE-07-01-schema-oversight.md) | Migration schema `oversight` | M | CORE-00-02, NEN-033 | MOD-07; TL-03 schema oversight | B4 | 11 |  |
| [CORE-07-02](CORE-07-02-gui-khieu-nai.md) | Khiếu nại: lưu nháp mã hoá, kiểm thời hạn, nộp có ký, chuyển hồ sơ `KHIEU_NAI`, EVT-25 | M | CORE-07-01, CORE-04-08 | QT-12 → SCR-1201; EVT-25 | B4 | 20 |  |
| [CORE-07-03](CORE-07-03-tiep-nhan-khieu-nai.md) | Chọn người xử lý qua OPA, máy trạng thái khiếu nại, tiếp nhận, không thụ lý, rút | M | CORE-07-02, NEN-040, DV-06-04 | SCR-1202 | B4 | 21 |  |
| [CORE-07-04](CORE-07-04-giai-trinh-ket-luan.md) | Yêu cầu giải trình, timer hạn, kết luận, văn bản trả lời có ký, tạo yêu cầu mở lại | L | CORE-07-03, DV-07-08, CORE-07-07 | SCR-1202 | B4 | 22 |  |
| [CORE-07-05](CORE-07-05-bao-luu-tu-dong.md) | Bảo lưu, tự đóng, nhật ký giải mã, gợi ý khiếu nại trùng | S | CORE-07-04, CORE-00-05 | QT-12 | B4 | 23 |  |
| [CORE-07-06](CORE-07-06-ma-hoa-dau-cuoi-c3.md) | Mã hoá đầu cuối C3 phía máy chủ: lưu bản mã, khoá công khai người nhận, bọc khoá, phục hồi tổ chức Shamir | L | CORE-07-02, NEN-015, NEN-042 | ADR-27; C3 | B4 | 21 |  |
| [CORE-07-07](CORE-07-07-yeu-cau-mo-lai.md) | Yêu cầu mở lại kỳ và mô phỏng ảnh hưởng | M | CORE-07-01, DV-07-08 | QT-13 → SCR-1301 | B4 | 18 |  |
| [CORE-07-08](CORE-07-08-bon-mat-phe-duyet.md) | Bốn mắt: chọn hai người duyệt qua OPA, phê duyệt có step-up và ký số, EVT-22 | M | CORE-07-07, NEN-040, DV-10-02, DV-06-04 | EVT-22 | B4 | 19 |  |
| [CORE-07-09](CORE-07-09-dieu-phoi-tinh-lai.md) | Điều phối tính lại, sinh lại, ký lại sau EVT-22; hậu kiểm có bằng chứng sổ cái | M | CORE-07-08, CORE-08-06, DV-09-05 | QT-13 | B4 | 20 |  |
| [CORE-07-10](CORE-07-10-tiep-nhan-canh-bao.md) | Cảnh báo bất thường: consumer EVT-20, chống trùng, gộp, hành động tự động, cờ chặn minh chứng | M | CORE-07-01, DV-15-07 | QT-14 → SCR-1401; EVT-20 | B4 | 12 |  |
| [CORE-07-11](CORE-07-11-xu-ly-canh-bao.md) | Xử lý cảnh báo: hợp lệ, giao kiểm tra, kết luận; quyền inspector; ngưỡng phát hiện có phiên bản | M | CORE-07-10, CORE-08-07, CORE-07-07 | SCR-1401 | B4 | 19 |  |
| [CORE-07-12](CORE-07-12-chi-muc-tra-cuu-nhat-ky-c2.md) | Chỉ mục kiểm toán và API tra cứu nhật ký truy cập dữ liệu C2 | M | CORE-00-05 | ADR-06 | B4 | 12 | Bổ sung sau khi viết thẻ |

## DV-01 · SVC-01 api-gateway

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-01-01](DV-01-01-tuyen-apisix-xac-thuc.md) | Tuyến APISIX theo module (ApisixRoute), xác thực JWT qua JWKS, plugin gán claim tenant | M | INF-08, DV-02-01 | SVC-01 | B5 | 2 |  |
| [DV-01-02](DV-01-02-gioi-han-toc-do-waf.md) | Giới hạn tốc độ ba tầng, plugin bảo vệ API, cắt tải, WAF Coraza với OWASP CRS 4 | M | DV-01-01 | TL-01 ch.14; TL-04 cắt tải | B5 | 3 |  |
| [DV-01-03](DV-01-03-tuyen-cong-khai-ci.md) | Danh sách tuyến công khai tường minh và kiểm thử CI quét toàn bộ CRD | S | DV-01-01 | S03 | B5 | 3 |  |
| [DV-01-04](DV-01-04-apisix-lop-ngoai.md) | APISIX lớp ngoài ở vùng toàn cục: phân giải tenant sang cell, đệm 5 phút, khám phá cell | M | DV-01-01, DV-03-02 | ADR-07 | B5 | 9 |  |

## DV-02 · SVC-02 iam (Keycloak)

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-02-01](DV-02-01-mau-realm-production.md) | Mẫu realm production theo cell với Organizations, PKCE, chính sách mật khẩu, khoá sau 5 lần sai, thời hạn phiên | M | – | ADR-08; BR-00-05 | B5 | 1 | Kế thừa realm dev của WEB-056 |
| [DV-02-02](DV-02-02-claim-ngu-canh.md) | Protocol mapper claim ngữ cảnh và API chọn đơn vị, vai trò (`PUT /org/me/context`) | M | DV-02-01, DV-04-04 | BR-00-07 | B5 | 12 |  |
| [DV-02-03](DV-02-03-mfa-step-up.md) | MFA theo vai trò, thiết bị, rủi ro; step-up `acr` và `auth_time` | M | DV-02-01 | BR-00-01, 02, 06 | B5 | 2 |  |
| [DV-02-04](DV-02-04-moi-gioi-vneid.md) | Môi giới VNeID: state, nonce, PKCE, liên kết danh tính bằng HMAC | L | DV-02-01, DV-04-01, NEN-015 | BR-00-03, 04 | B5 | 10 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần môi trường thử VNeID |
| [DV-02-05](DV-02-05-su-kien-keycloak.md) | Event listener Keycloak đẩy `iam.auth.event.v1`, báo truy cập bất thường, thu hồi phiên | M | DV-02-01, NEN-007 | BR-00-08 | B5 | 4 |  |
| [DV-02-06](DV-02-06-dong-bo-nguoi-dung.md) | Consumer EVT-16 đồng bộ người dùng qua Admin API | M | DV-02-01, DV-04-03 | EVT-16 | B5 | 12 |  |
| [DV-02-07](DV-02-07-consumer-evt14-keycloak.md) | Consumer EVT-14 cập nhật Organizations Keycloak khi tổ chức thay đổi | S | DV-02-01, DV-04-03 | EVT-14 | B5 | 12 | Bổ sung sau khi viết thẻ |
| [DV-02-08](DV-02-08-ma-khoi-phuc-mfa.md) | Mã khôi phục MFA: cấp, dùng một lần, thu hồi | S | DV-02-03, NEN-043 | BR-00-01 | B5 | 4 | Bổ sung sau khi viết thẻ |

## DV-03 · SVC-03 tenant-registry (Go, toàn cục)

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-03-01](DV-03-01-khung-tenant-registry.md) | Khung service Go và migration schema `registry` | M | NEN-017 | SVC-03; TL-03 schema registry | B5 | 7 |  |
| [DV-03-02](DV-03-02-phan-giai-tenant.md) | API phân giải tenant theo tên miền, danh sách cell, đệm | M | DV-03-01 | QT-00 | B5 | 8 |  |
| [DV-03-03](DV-03-03-vong-doi-tenant.md) | Tenant: máy trạng thái bảy trạng thái, API nháp, gợi ý mô hình thuê bao và chọn cell theo tải | L | DV-03-01 | QT-01 → SCR-0101, 0102; BR-01-01, 02 | B5 | 8 |  |
| [DV-03-04](DV-03-04-saga-cap-phat-1.md) | Saga cấp phát tenant phần 1: CSDL hoặc schema, đăng ký pool, KEK trong HSM | L | DV-03-03, DV-06-05 | NFR-07 | B5 | 11 |  |
| [DV-03-05](DV-03-05-saga-cap-phat-2.md) | Saga cấp phát tenant phần 2: Organization Keycloak, tên miền và TLS, sao chép Policy Pack và UI Pack, lịch mặc định, kích hoạt | L | DV-03-04, DV-02-01 | BR-01-03, 04 | B5 | 12 |  |
| [DV-03-06](DV-03-06-idp-cua-tenant.md) | Khai báo IdP của tenant và kiểm kết nối OIDC, SAML, LDAP | S | DV-03-03 | SCR-0102 | B5 | 9 |  |
| [DV-03-07](DV-03-07-chuyen-tenant-cell.md) | Chuyển tenant giữa các cell bằng sao chép logic, hai người duyệt | L | DV-03-05, NEN-043 | ADR-29 | B5 | 13 |  |

## DV-04 · SVC-04 org-service

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-04-01](DV-04-01-khung-org-service.md) | Khung service và migration schema `org` hai trục thời gian có ràng buộc loại trừ | L | NEN-019, NEN-033 | SVC-04; TL-03 schema org | B5 | 9 |  |
| [DV-04-02](DV-04-02-don-vi-asof.md) | Đơn vị và quan hệ: truy vấn `asOf`, `knownAt`, cây con, chặn vòng | M | DV-04-01 | QT-01 → SCR-0103; BR-01-06 | B5 | 10 |  |
| [DV-04-03](DV-04-03-thay-doi-to-chuc.md) | Thay đổi tổ chức (sáp nhập, chia tách, đổi tên) có tiền thân, phát EVT-14, EVT-15, EVT-16 | M | DV-04-02, NEN-011 | EVT-14..16; BR-01-05, 07 | B5 | 11 |  |
| [DV-04-04](DV-04-04-phan-cong.md) | Phân công: một vị trí chính tại một thời điểm, kiêm nhiệm, điều động tương lai | M | DV-04-02 | BR-01-08, 09 | B5 | 11 |  |
| [DV-04-05](DV-04-05-nhap-cay-excel.md) | Nhập cây tổ chức từ Excel (202, báo cáo lỗi) | S | DV-04-02 | SCR-0103 | B5 | 11 |  |
| [DV-04-06](DV-04-06-scim.md) | SCIM 2.0 cho HRM và IdP khách hàng | M | DV-04-04 | SVC-04 | B5 | 12 |  |
| [DV-04-07](DV-04-07-doi-soat-nhan-su.md) | Đối soát nhân sự: khớp người bằng HMAC và ngày sinh, bảng lệch, phân loại, API giải quyết | L | DV-04-04, DV-13-06 | QT-01 → SCR-0104; BR-01-10..12 | B5 | 12 |  |
| [DV-04-08](DV-04-08-org-republish.md) | Lệnh phát lại sự kiện tổ chức (`org-republish`) cho bên nhận bị lệch | S | DV-04-03, NEN-022 | EVT-14..16 | B5 | 12 | Bổ sung sau khi viết thẻ |

## DV-06 · SVC-06 workflow-service (Flowable)

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-06-01](DV-06-01-khung-flowable.md) | Khung Flowable 7 nhúng Spring Boot, CSDL `flowable`, lịch sử mức audit, tách API và async executor | L | NEN-019 | ADR-04; TL-01 ch.8 | B6 | 9 |  |
| [DV-06-02](DV-06-02-hop-viec.md) | `wf_template`, `wf_binding`, `wf_task_projection`; API hộp việc, hoàn thành, hoàn thành hàng loạt | M | DV-06-01 | SVC-06 | B6 | 10 |  |
| [DV-06-03](DV-06-03-bpmn-duyet-phan-1.md) | Mẫu BPMN luồng duyệt phần 1: OKR, kế hoạch, đề nghị gia hạn, kèm DMN chọn cấp duyệt, kiểm thử và linter | L | DV-06-02 | ADR-04 | B6 | 11 |  |
| [DV-06-04](DV-06-04-bpmn-duyet-phan-2.md) | Mẫu BPMN luồng duyệt phần 2: hồ sơ đánh giá, khiếu nại, mở lại kỳ, phát hành Policy Pack | L | DV-06-03 | ADR-04 | B6 | 12 |  |
| [DV-06-05](DV-06-05-khung-saga.md) | Khung saga tầng 3: bước, bù trừ, trạng thái bền, 202 và theo dõi | L | DV-06-01 | ADR-04 tầng 3 | B6 | 10 |  |
| [DV-06-06](DV-06-06-saga-chot-ky.md) | Saga chốt kỳ: tiền điều kiện, khoá, tính điểm, sinh biểu mẫu, ký lô, niêm phong, lập hồ sơ | L | DV-06-05, CORE-08-05, DV-07-06, DV-11-04, DV-10-06, DV-09-03 | QT-08 → SCR-0801; EVT-09 | B6 | 19 |  |
| [DV-06-07](DV-06-07-bu-tru-chot-ky.md) | Bù trừ saga chốt kỳ, chạy lại hồ sơ lỗi, kiểm tất định bằng chạy lại mẫu | L | DV-06-06 | BR-08-09, 11, 12; EVT-26 | B6 | 20 |  |
| [DV-06-08](DV-06-08-api-wf-binding.md) | API `wf_binding`: lưu, linter tham số, mô phỏng trên engine thử | M | DV-06-03 | SCR-1504 | B6 | 12 |  |
| [DV-06-09](DV-06-09-mau-luong-noi-bo.md) | Mẫu luồng nội bộ (BS-01): mở rộng `wf_template`, `wf_template_rollout`, linter, saga phát hành cuốn chiếu | L | DV-06-08 | SCR-1505; BS-01 | B6 | 13 |  |
| [DV-06-10](DV-06-10-giam-sat-tien-trinh.md) | API giám sát tiến trình theo mẫu (BS-01): chuyển người xử lý, gia hạn, sơ đồ | M | DV-06-02 | SCR-1506; BS-01 | B6 | 11 |  |
| [DV-06-11](DV-06-11-chieu-lich-su-g2.md) | Chiếu lịch sử tiến trình sang kho phân tích và chỉ số giám sát G2 | M | DV-06-02, DV-15-01 | G2 | B6 | 11 |  |
| [DV-06-12](DV-06-12-mau-duyet-kep-chi-huy.md) | Mẫu BPMN duyệt kép chỉ huy và cấp uỷ (`duyet-kep-chi-huy-cap-uy`) | M | DV-06-04 | ADR-04 | B6 | 13 | Bổ sung sau khi viết thẻ |

## DV-07 · SVC-07 scoring-service

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-07-01](DV-07-01-khung-scoring.md) | Khung service và migration schema `scoring` | M | NEN-019, NEN-033 | SVC-07; TL-03 schema scoring | B4 | 9 |  |
| [DV-07-02](DV-07-02-nap-policy-pack.md) | Nạp Policy Pack, biên dịch sẵn CEL và DMN, đệm theo phiên bản | M | DV-07-01, NEN-041, CORE-05-03 | ADR-19 | B4 | 14 |  |
| [DV-07-03](DV-07-03-chuoi-tinh-diem.md) | Chuỗi tính A, B, C, KPI nhóm, điểm và `score_trace` | L | DV-07-02 | BR-07-05 | B4 | 15 |  |
| [DV-07-04](DV-07-04-tinh-dan-su-kien.md) | Tính dần theo sự kiện EVT-06, EVT-08, EVT-13, EVT-22, phát EVT-10 | M | DV-07-03, NEN-012 | EVT-10 | B4 | 16 |  |
| [DV-07-05](DV-07-05-dmn-xep-loai.md) | DMN xếp loại, câu giải thích, snapshot có `input_hash`; luồng Đảng và Nhà nước song song | L | DV-07-03 | QT-09 → SCR-0903 | B4 | 16 |  |
| [DV-07-06](DV-07-06-tinh-lai-hang-loat.md) | Tính lại hàng loạt cuối kỳ: chia lô, worker co giãn, đọc `inputs`, hàng đợi lỗi (NFR-06) | L | DV-07-05, CORE-04-06 | NFR-06; BR-08-07 | B4 | 18 |  |
| [DV-07-07](DV-07-07-snapshot-nam.md) | Snapshot năm (băm của các băm quý) và DMN năm | M | DV-07-05 | QT-10 | B4 | 17 |  |
| [DV-07-08](DV-07-08-mo-phong-xem-truoc.md) | API mô phỏng và xem trước với phiên bản Policy Pack gốc | M | DV-07-05 | QT-12, QT-13 | B4 | 17 |  |

## DV-08 · SVC-08 evidence-service

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-08-01](DV-08-01-khung-evidence-service.md) | Khung WebFlux, migration schema `evidence`, client S3 cho Ceph RGW | M | NEN-019, NEN-033 | SVC-08; TL-03 schema evidence | B3 | 9 |  |
| [DV-08-02](DV-08-02-tai-len-nhieu-phan.md) | Tải lên nhiều phần có tiếp tục, SHA-256 khi nhận, Object Lock, URL tải ký 5 phút | L | DV-08-01 | BR-07-01, 02; ADR-13 | B3 | 10 |  |
| [DV-08-03](DV-08-03-khu-trung-theo-bam.md) | Khử trùng theo băm và phát hiện trùng tức thời phát EVT-20 | M | DV-08-02 | BR-07; QT-14 | B3 | 11 |  |
| [DV-08-04](DV-08-04-quet-ma-doc.md) | Quét mã độc ClamAV bất đồng bộ, cách ly, thông báo | M | DV-08-02 | BR-07-04 | B3 | 11 |  |
| [DV-08-05](DV-08-05-ocr-qua-ai.md) | Gọi OCR qua ai-gateway cho minh chứng | S | DV-08-02, DV-14-05, DV-08-04 | SCR-0702 | B3 | 12 |  |
| [DV-08-06](DV-08-06-xem-truoc-tep-minh-chung.md) | Xem trước tệp minh chứng (`getFilePreview`) qua Gotenberg, chỉ tệp đã quét sạch | S | DV-08-04, DV-11-01 | SCR-0703 | B3 | 12 | Bổ sung sau khi viết thẻ |

## DV-09 · SVC-09 ledger-service (Go)

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-09-01](DV-09-01-khung-ledger-migration.md) | Khung service và migration CSDL `ledger` chỉ ghi thêm | M | NEN-017 | SVC-09; TL-03 schema ledger | B6 | 7 |  |
| [DV-09-02](DV-09-02-ghi-chuoi-bam.md) | Consumer ghi chuỗi băm 64 phân vùng theo tenant, chuẩn hoá RFC 8785 | L | DV-09-01 | ADR-09, ADR-26 | B6 | 8 |  |
| [DV-09-03](DV-09-03-niem-phong-merkle.md) | Niêm phong Merkle hai cấp mỗi giờ, dấu thời gian RFC 3161, ký qua esign, EVT-19, bản neo | L | DV-09-02, DV-10-03 | EVT-19 | B6 | 11 |  |
| [DV-09-04](DV-09-04-kiem-tra-toan-ven.md) | Kiểm tra toàn vẹn định kỳ, chỉ ra bản ghi bị phá, phát EVT-20 | M | DV-09-03 | QT-13 | B6 | 12 |  |
| [DV-09-05](DV-09-05-tra-cuu-xuat-bang-chung.md) | API tra cứu, xuất bằng chứng có ký và công cụ kiểm chứng độc lập `ledger-verify` | L | DV-09-03 | SCR-1302 | B6 | 12 |  |

## DV-10 · SVC-10 esign-service

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-10-01](DV-10-01-khung-esign-provider.md) | Khung service, migration schema `esign`, giao diện nhà cung cấp `SignatureProvider` | M | NEN-019, NEN-033 | SVC-10 | B6 | 9 |  |
| [DV-10-02](DV-10-02-nha-cung-cap-mysign.md) | Nhà cung cấp MySign (CSC API) ký từ xa bất đồng bộ, EVT-18 | L | DV-10-01 | EVT-18 | B6 | 10 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần tài khoản thử MySign |
| [DV-10-03](DV-10-03-hsm-pkcs11-tsa.md) | Nhà cung cấp HSM qua PKCS#11 và dấu thời gian TSA | L | DV-10-01 | ADR-17 | B6 | 10 | Dev dùng SoftHSM |
| [DV-10-04](DV-10-04-viettel-ca-ca-chinh-phu.md) | Nhà cung cấp Viettel-CA và CA chuyên dùng Chính phủ | M | DV-10-01 | SVC-10 | B6 | 10 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần môi trường thử CA |
| [DV-10-05](DV-10-05-ban-co-yeu-cach-ly.md) | Nhà cung cấp Ban Cơ yếu cho bản cách ly qua `CryptoProvider` | M | DV-10-01 | ADR-06 | B6 | 10 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần thư viện Ban Cơ yếu |
| [DV-10-06](DV-10-06-ky-lo-quyet-dinh.md) | Ký lô 100.000 phiếu trong 4 giờ: phiên ký một lần xác nhận, worker co giãn | M | DV-10-03 | NFR-06; ADR-31 | B6 | 11 |  |

## DV-11 · SVC-11 document-service

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-11-01](DV-11-01-khung-docgen-gotenberg.md) | Khung service, migration schema `docgen`, Gotenberg, job sinh 202 | M | NEN-019, NEN-033 | SVC-11 | B6 | 9 |  |
| [DV-11-02](DV-11-02-mau-1a-1d.md) | Mẫu 1A, 1B, 1C, 1D bằng docx4j | M | DV-11-01, DL-05 | H5 | B6 | 10 |  |
| [DV-11-03](DV-11-03-mau-2-mau-3-bang-diem.md) | Mẫu 2, Mẫu 3, bảng chấm điểm | M | DV-11-01, DL-05 | H5 | B6 | 10 |  |
| [DV-11-04](DV-11-04-goi-ho-so-pdfa.md) | Gói hồ sơ PDF/A có tem, phiên bản mẫu theo Policy Pack, EVT-17 | M | DV-11-02, DV-11-03 | EVT-17 | B6 | 11 |  |

## DV-12 · SVC-12 notification-service (Go)

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-12-01](DV-12-01-khung-notification-consumer.md) | Khung service Go, migration schema `notify`, consumer EVT-21 và các sự kiện cần báo | M | NEN-017 | SVC-12; EVT-21 | B7 | 7 |  |
| [DV-12-02](DV-12-02-thong-bao-sse.md) | Thông báo thời gian thực SSE `/notifications/stream`, bus Valkey Streams giữa các pod | M | DV-12-01 | SVC-12 | B7 | 8 |  |
| [DV-12-03](DV-12-03-kenh-email-sms-day.md) | Kênh email, SMS, FCM, APNs có bulkhead, thử lại, tuỳ chọn kênh, mẫu thông báo | L | DV-12-01 | SVC-12 | B7 | 8 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần cổng SMS và tài khoản đẩy |
| [DV-12-04](DV-12-04-thong-bao-hang-loat.md) | Thông báo hàng loạt cuối kỳ chia theo tenant, giới hạn tốc độ theo kênh | S | DV-12-03 | NFR-06 | B7 | 9 |  |
| [DV-12-05](DV-12-05-thiet-bi-day-tuy-chon-kenh.md) | Đăng ký thiết bị nhận thông báo đẩy và tuỳ chọn kênh: bảng `notify`, API | S | DV-12-01, NEN-043 | SVC-12 | B7 | 8 | Bổ sung sau khi viết thẻ |

## DV-13 · SVC-13 integration-gateway

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-13-01](DV-13-01-khung-camel-acl.md) | Khung Camel, migration schema `integ`, khung lớp chống ô nhiễm, `mapping_xref`, dead letter | L | NEN-019, NEN-033 | ADR-20; SVC-13 | B5 | 9 |  |
| [DV-13-02](DV-13-02-cloudevents-jws-mtls.md) | CloudEvents ra ngoài có JWS và mTLS | M | DV-13-01 | TL-01 ch.6 | B5 | 10 |  |
| [DV-13-03](DV-13-03-ket-noi-voffice.md) | Bộ kết nối vOffice: văn bản chỉ đạo đến (EVT-24) và văn bản đã ban hành | L | DV-13-01 | EVT-24 | B5 | 10 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần môi trường thử vOffice |
| [DV-13-04](DV-13-04-ket-noi-ioffice.md) | Bộ kết nối iOffice | M | DV-13-03 | EVT-24 | B5 | 11 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần môi trường thử iOffice |
| [DV-13-05](DV-13-05-truc-lien-thong-edxml.md) | Trục liên thông văn bản quốc gia (EdXML, X-Road) | L | DV-13-01 | L01 | B5 | 10 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần kết nối thử Trục |
| [DV-13-06](DV-13-06-lgsp-ndxp-cbccvc.md) | LGSP, NDXP và CSDL cán bộ, công chức, viên chức (đồng bộ đêm, `sync_cursor`) | L | DV-13-01 | QT-01 | B5 | 10 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần kết nối thử NDXP |
| [DV-13-07](DV-13-07-csdl-dang-vien.md) | CSDL đảng viên | M | DV-13-06 | QT-01 | B5 | 11 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần kết nối thử |
| [DV-13-08](DV-13-08-hrm-so-lieu-kr.md) | HRM và số liệu nguồn cho KR | M | DV-13-01 | BR-03-04 | B5 | 10 |  |
| [DV-13-09](DV-13-09-ban-neo-chia-se-xep-loai.md) | Gửi bản neo sổ cái cho bên thứ ba và chia sẻ quyết định xếp loại ra ngoài | M | DV-13-02, DV-09-03 | EVT-19, EVT-12 | B5 | 12 |  |
| [DV-13-10](DV-13-10-goi-chuyen-ho-so-lien-cell.md) | Gói chuyển hồ sơ cán bộ liên cell có ký số | M | DV-13-02, DV-10-01 | baseline §8.7 | B5 | 11 |  |
| [DV-13-11](DV-13-11-pact-he-ngoai.md) | Kiểm thử hợp đồng Pact với từng hệ ngoài bằng đối tác giả lập | M | DV-13-03, QA-02 | J | B5 | 11 |  |

## DV-14 · SVC-14 ai-gateway (Python)

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-14-01](DV-14-01-khung-ai-gateway.md) | Khung FastAPI, migration schema `ai`, client vLLM, đổi token sang vai trò `ai_readonly` | M | NEN-018 | SVC-14; AC-12 | B7 | 5 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần GPU hoặc mô hình nhỏ cho dev |
| [DV-14-02](DV-14-02-chi-muc-doc-chunk.md) | Chỉ mục `doc_chunk` có ACL bằng pgvector, consumer EVT-24 | L | DV-14-01 | ADR-28 | B7 | 6 |  |
| [DV-14-03](DV-14-03-rao-chan-ai.md) | Rào chắn đầu vào, đầu ra và `guardrail_event`; không gửi dữ liệu C3 | L | DV-14-01 | TL-01 ch.17 | B7 | 6 |  |
| [DV-14-04](DV-14-04-api-tro-ly-ai.md) | API trợ lý: soạn OKR, soát KR, trích nhiệm vụ, giải thích điểm, 202 khi hàng đợi đầy | M | DV-14-03 | QT-17 → SCR-1701 | B7 | 7 |  |
| [DV-14-05](DV-14-05-ocr-minh-chung.md) | OCR minh chứng | M | DV-14-01 | SCR-0702 | B7 | 6 |  |
| [DV-14-06](DV-14-06-nhat-ky-goi-y-tom-tat.md) | Nhật ký gợi ý (chấp nhận, bỏ qua, nguồn) và tóm tắt tuần cho lãnh đạo | M | DV-14-04 | QT-17, QT-16 | B7 | 8 |  |
| [DV-14-07](DV-14-07-chuyen-giong-noi-van-ban.md) | Chuyển giọng nói thành văn bản (`transcribeSpeech`) | M | DV-14-01 | SVC-14 | B7 | 6 | Bổ sung sau khi viết thẻ |

## DV-15 · SVC-15 analytics

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-15-01](DV-15-01-clickhouse-mo-hinh-sao.md) | ClickHouse `analytics_<cell>` mô hình sao, Kafka engine và materialized view | L | INF-11, NEN-007 | ADR-14 | B7 | 4 |  |
| [DV-15-02](DV-15-02-debezium-phan-tich.md) | Connector Debezium phân tích và giả danh hoá cột C2 | M | DV-15-01, INF-10 | TL-03 CDC | B7 | 5 |  |
| [DV-15-03](DV-15-03-cube-chi-so-chuan.md) | Cube: chỉ số chuẩn của bảng điều hành, chiều đơn vị, kỳ, khối, luồng, bộ lọc an ninh | L | DV-15-01 | QT-16 → SCR-1601 | B7 | 5 |  |
| [DV-15-04](DV-15-04-analytics-api.md) | analytics-api: proxy Cube theo token, đệm 60 giây, API định nghĩa dashboard | M | DV-15-03, CORE-08-17 | SCR-1601, SCR-1602 | B7 | 13 |  |
| [DV-15-05](DV-15-05-superset-nhung.md) | Superset nhúng với guest token theo phạm vi | M | DV-15-03, DV-15-04 | ADR-14 | B7 | 14 |  |
| [DV-15-06](DV-15-06-chuoi-truy-vet.md) | Đi xuống tới minh chứng: chuỗi truy vết, xuất bằng chứng và báo cáo 202, nhắc và giao lại | M | DV-15-04 | SCR-1603 | B7 | 14 |  |
| [DV-15-07](DV-15-07-khung-job-bat-thuong.md) | Khung job phát hiện bất thường hằng đêm và dấu hiệu 1, 2, phát EVT-20 | M | DV-15-01 | QT-14; EVT-20 | B7 | 5 |  |
| [DV-15-08](DV-15-08-dau-hieu-3-5-7.md) | Dấu hiệu bất thường 3, 5, 7 | M | DV-15-07 | QT-14 | B7 | 6 |  |
| [DV-15-09](DV-15-09-dau-hieu-6-8.md) | Dấu hiệu bất thường 6, 8 và tự đóng khi hết dấu hiệu; dấu hiệu tỷ lệ chấp nhận khách quan | M | DV-15-07 | QT-14; BR-06-12 | B7 | 6 |  |
| [DV-15-10](DV-15-10-du-bao-tre-han.md) | Dự báo nguy cơ trễ nhiệm vụ | M | DV-15-01, CORE-02-26 | QT-06 | B7 | 14 |  |
| [DV-15-11](DV-15-11-mirrormaker-kho-quoc-gia.md) | MirrorMaker 2 đẩy dữ liệu tổng hợp lên kho phân tích quốc gia | M | DV-15-01, INF-10 | baseline §8.7 | B7 | 5 |  |

## DV-16 · SVC-16 search

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DV-16-01](DV-16-01-chi-muc-opensearch.md) | Chỉ mục OpenSearch, alias lọc theo tenant, indexer consumer EVT-04, EVT-24 | M | INF-11, NEN-012 | SVC-16 | B7 | 8 |  |
| [DV-16-02](DV-16-02-api-tim-kiem.md) | API tìm kiếm toàn cục, kiểm quyền lại ở lõi trước khi trả chi tiết | M | DV-16-01, CORE-00-02, NEN-013, NEN-014 | SCR-0002 | B7 | 11 |  |

## DL · Dữ liệu, nội dung nghiệp vụ và chuyển đổi

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [DL-01](DL-01-policy-pack-quy-dinh-moi.md) | Policy Pack "quy định mới": công thức CEL tính điểm luồng Đảng và luồng Nhà nước theo QĐ 366, NĐ 335/2025 | L | CORE-05-04 | QT-15; H | B11 | 14 | Dùng công thức giả lập (20/10), nạp qua Policy Pack để cập nhật động; thay bằng bản nghiệp vụ ký khi có |
| [DL-02](DL-02-dmn-xep-loai-dac-thu.md) | Bảng DMN xếp loại quý, năm và bảng 13 trường hợp đặc thù | M | DL-01, CORE-05-05 | QT-09, QT-10, QT-11 | B11 | 15 | Dùng công thức giả lập (20/10), nạp qua Policy Pack để cập nhật động; thay bằng bản nghiệp vụ ký khi có |
| [DL-03](DL-03-bo-ca-kiem-thu-vang.md) | Bộ ca kiểm thử vàng đầy đủ do nghiệp vụ ký | L | DL-02, CORE-05-06 | J6 | B11 | 16 | Dùng công thức giả lập (20/10), nạp qua Policy Pack để cập nhật động; thay bằng bản nghiệp vụ ký khi có |
| [DL-04](DL-04-task-lifecycle-tham-so.md) | Bảng chuyển trạng thái `task_lifecycle` và tham số luồng duyệt mặc định | S | CORE-05-03 | ADR-04 | B11 | 14 |  |
| [DL-05](DL-05-mau-docx-bieu-mau.md) | Mẫu DOCX 1A–1D, 2, 3 và bảng chấm điểm theo văn bản pháp lý | M | – | H5 | B11 | 1 | Dùng mẫu biểu giả lập theo văn bản pháp lý (20/10); thay bản chính thức khi có |
| [DL-06](DL-06-ui-pack-mac-dinh.md) | UI Pack mặc định, bộ token người cao tuổi, danh mục công việc mẫu theo khối | M | CORE-08-11, CORE-02-04 | NFR-07, NFR-11 | B11 | 18 |  |
| [DL-07](DL-07-du-lieu-khoi-tao.md) | Dữ liệu khởi tạo: 9 khối đối tượng, lịch nghỉ lễ quốc gia, danh mục chức danh | S | DV-04-01, CORE-08-04 | PL 2A | B11 | 13 |  |
| [DL-08](DL-08-sinh-du-lieu-thu-tai.md) | Bộ sinh dữ liệu thử quy mô (1,2 triệu hồ sơ một cell) cho kiểm thử hiệu năng | M | CORE-04-01, CORE-02-02, DV-04-01, CORE-03-01 | NFR-06 | B9 | 13 |  |
| [DL-12](DL-12-job-doi-soat.md) | Job đối soát R1–R8 và bốn job kiểm tra sổ cái | L | CORE-04-02, DV-09-04, DV-15-02 | TL-03 ch.17 | B11 | 14 |  |
| [DL-13](DL-13-luu-giu-xoa-mat-ma.md) | Lưu giữ, lưu trữ lạnh, giữ pháp lý, xoá bằng mật mã theo bảy lớp lưu giữ | M | NEN-015, INF-18, CORE-08-07 | NFR-09; TL-03 ch.16 | B11 | 13 |  |
| [DL-14](DL-14-xoay-vong-dek-kek.md) | Xoay vòng DEK định kỳ và bọc lại khoá theo KEK mới | M | NEN-015, INF-13 | ADR-17 | B11 | 7 | Bổ sung sau khi viết thẻ |
| [DL-15](DL-15-an-danh-hoa-phi-san-xuat.md) | Tác vụ ẩn danh hoá dữ liệu cho môi trường phi sản xuất (`anonymize`) | M | NEN-015, DL-08, NEN-005, NEN-006 | SEC-52 | B11 | 14 | Bổ sung sau khi viết thẻ |

## INF · Hạ tầng, CI/CD, bảo mật nền, quan sát, dự phòng

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [INF-01](INF-01-chart-thu-vien-vt-service.md) | Chart thư viện `vt-service`: probe, PDB, phân bố vùng, HPA, NetworkPolicy, tài nguyên | L | – | TL-04 ch.5 | B8 | 1 |  |
| [INF-02](INF-02-chart-dich-vu-vt-platform.md) | Chart từng service, chart tổng `vt-platform` và values cho ba mô hình phân phối | L | INF-01 | TL-04 ch.2, ch.5 | B8 | 2 |  |
| [INF-03](INF-03-values-dinh-co-cell.md) | Values định cỡ bốn hồ sơ cell, `capacity/assumptions.yaml`, script tính bảng, kiểm ngân sách kết nối trong CI | M | INF-02 | TL-05; TL-04 ch.4 | B8 | 3 |  |
| [INF-04](INF-04-gitops-argo-cd.md) | Kho GitOps: Argo CD ApplicationSet theo cell và môi trường, sync window, Argo Rollouts canary có phân tích SLI | L | INF-02 | ADR-16; OPS-02, 03, 05 | B8 | 3 |  |
| [INF-05](INF-05-pipeline-jenkins-java.md) | Thư viện pipeline Jenkins `vt-pipeline` cho Java: build, test, ArchUnit, Sonar, Semgrep, gitleaks, Trivy, openapi-diff, Pact, SBOM, Jib, cosign, provenance SLSA 3 | L | NEN-005 | ADR-16; SEC-37..39 | B8 | 3 |  |
| [INF-06](INF-06-pipeline-ngon-ngu-noi-dung.md) | Pipeline cho Go, Python, Flutter, Helm và nội dung (Policy Pack, UI Pack) | M | INF-05 | OPS-10 | B8 | 4 |  |
| [INF-07](INF-07-harbor-kyverno.md) | Harbor và chính sách Kyverno: chỉ ảnh có ký, không root, có giới hạn và probe, `vt-require-ha` | M | INF-05 | SEC-28, SEC-38 | B8 | 4 |  |
| [INF-08](INF-08-cum-cell-phan-1.md) | Cụm Kubernetes của cell phần 1: node pool, taint, 13 namespace, NetworkPolicy mặc định từ chối, APISIX ingress | L | – | TL-04 ch.3 | B8 | 1 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần cụm K8s thật hoặc kind cho dev |
| [INF-09](INF-09-cum-cell-phan-2.md) | Cụm Kubernetes của cell phần 2: Istio ambient mTLS STRICT, StorageClass Ceph RBD và NVMe cục bộ, egress gateway | L | INF-08 | ADR-11; SEC-32..36 | B8 | 2 |  |
| [INF-10](INF-10-kafka-strimzi-debezium.md) | Kafka bằng Strimzi, Apicurio, Kafka Connect Debezium, khai báo topic dạng mã | M | INF-08, NEN-007, INF-11 | ADR-03 | B8 | 4 |  |
| [INF-11](INF-11-csdl-va-kho-du-lieu.md) | PostgreSQL CloudNativePG sáu cụm, PgBouncer, Valkey, OpenSearch, ClickHouse theo định cỡ | L | INF-08 | ADR-02, ADR-14, ADR-15, ADR-18 | B8 | 2 |  |
| [INF-12](INF-12-ceph-object-lock.md) | Ceph RGW, bucket minh chứng Object Lock compliance, multisite | M | INF-09 | ADR-13 | B8 | 3 |  |
| [INF-13](INF-13-openbao-pki-hsm.md) | OpenBao năm node tự mở khoá bằng HSM, PKI nội bộ, cert-manager, External Secrets Operator | L | INF-08 | ADR-17; SEC-10, 11, 59 | B8 | 2 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần HSM thật cho production |
| [INF-14](INF-14-quan-sat-thu-thap.md) | Quan sát phần 1: OTel Collector lấy mẫu theo đuôi, Tempo, Prometheus và Thanos, Loki, Fluent Bit có bộ đệm đĩa | L | INF-08 | ADR-12; NFR-10 | B8 | 2 |  |
| [INF-15](INF-15-slo-canh-bao-dashboard.md) | Quan sát phần 2: SLI, dashboard Grafana, cảnh báo theo tốc độ tiêu ngân sách lỗi, 24 cảnh báo tối thiểu, ngưỡng nâng bậc NG-01..NG-14 | M | INF-14 | TL-04 ch.10; TL-05 | B8 | 3 |  |
| [INF-16](INF-16-co-gian-tu-dong.md) | Co giãn: Prometheus Adapter cho HPA, KEDA theo độ trễ Kafka, KEDA theo lịch hạn, Cluster Autoscaler có pod giữ chỗ | L | INF-14, INF-10, INF-01 | TL-04 ch.6 | B8 | 5 |  |
| [INF-17](INF-17-sao-luu-csdl.md) | Sao lưu phần 1: CNPG Barman Cloud, kho sao lưu bất biến, `restore-check` hằng ngày, Velero và etcd | L | INF-11 | TL-04 ch.8; OPS-18 | B8 | 3 |  |
| [INF-18](INF-18-sao-luu-du-lieu-khac.md) | Sao lưu phần 2: MirrorMaker 2, Ceph multisite, snapshot OpenSearch, ClickHouse, Valkey, giữ outbox 72 giờ, lịch sao lưu | L | INF-17, INF-12, NEN-022 | TL-04 ch.8 | B8 | 8 |  |
| [INF-19](INF-19-bao-mat-nen-tang.md) | Bảo mật nền: Falco, nhật ký kiểm toán API Kubernetes và luồng `sys.audit.v1` tới SIEM, Pod Security, chính sách egress | M | INF-09 | SEC-22, 30, 43, 50 | B8 | 3 |  |
| [INF-20](INF-20-vung-dieu-phoi-toan-cuc.md) | Vùng điều phối toàn cục: cụm quản trị, PostgreSQL toàn cục, GeoDNS PowerDNS, `dns-sync`, CDN tài nguyên tĩnh | L | INF-04 | TL-04 ch.2 | B8 | 4 |  |
| [INF-21](INF-21-moi-truong-phi-san-xuat.md) | Môi trường phi sản xuất: staging, CELL-PERF, cụm sinh tải, môi trường tạm theo nhánh cho E2E | M | INF-04 | TL-05 môi trường | B8 | 4 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần tài nguyên hạ tầng |
| [INF-22](INF-22-backend-tren-server-dev.md) | Triển khai backend lên server dev bằng Docker Compose (profile backend, cổng 35xxx) để kiểm thử tích hợp thật | M | CORE-00-01, NEN-020 | YC-HT-01, YC-PT-08 | B8 | 10 | Qua Remote Control trên máy Mac |
| [INF-23](INF-23-goi-cai-cach-ly.md) | Gói cài cách ly LTS: đóng gói Helm ngoại tuyến, Harbor nội bộ, kiểm chữ ký | L | INF-02, INF-07 | OPS-12; TL-04 mục 9.9 | B8 | 5 |  |
| [INF-24](INF-24-audit-archiver.md) | Dịch vụ `audit-archiver`: chuyển nhật ký kiểm toán sang kho lưu trữ bất biến theo thời hạn lưu giữ | M | INF-12, INF-19 | NFR-09; SEC | B8 | 4 | Bổ sung sau khi viết thẻ |
| [INF-25](INF-25-bo-dieu-khien-suy-giam.md) | Bộ điều khiển suy giảm ba mức khi quá tải (tắt tính năng phụ, cắt tải theo ưu tiên) | M | DV-01-02, INF-15 | TL-04 cắt tải | B8 | 4 | Bổ sung sau khi viết thẻ |
| [INF-26](INF-26-cong-cu-vtctl.md) | Công cụ vận hành `vtctl` cho các thao tác trong runbook (tenant, cell, khoá kỳ, phát lại sự kiện) | L | INF-04, DV-03-03 | TL-04 ch.11 | B8 | 9 | Bổ sung sau khi viết thẻ; nên tách theo nhóm lệnh |
| [INF-27](INF-27-quan-ly-truy-cap-dac-quyen.md) | Quản lý truy cập đặc quyền PAM cho quản trị hạ tầng và CSDL | M | INF-13 | SEC-42 | B8 | 3 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần giải pháp PAM |
| [INF-28](INF-28-cum-cho-am-ttdl-hai.md) | Cụm chờ ấm ở trung tâm dữ liệu thứ hai: CNPG replica cluster, Kafka MirrorMaker, Ceph multisite | L | INF-11, INF-18, INF-20, INF-04, INF-08, INF-09, INF-17 | NFR-05; DR | B8 | 9 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần TTDL thứ hai |

## QA · Kiểm thử tích hợp, hiệu năng, an toàn thông tin, dự phòng

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [QA-01](QA-01-chien-luoc-kiem-thu.md) | Chiến lược kiểm thử toàn hệ thống và danh mục khoảng 40 hành trình đầu cuối | S | – | TL-01 ch.18 | B9 | 1 |  |
| [QA-02](QA-02-pact-hop-dong.md) | Pact Broker và kiểm thử hợp đồng frontend với API, service với service | M | NEN-006, INF-05 | ADR-32 | B9 | 4 |  |
| [QA-03](QA-03-e2e-he-thong-that.md) | Bộ kiểm thử đầu cuối với hệ thống thật trên server dev (backend thật, Keycloak, APISIX) | M | INF-22, QA-01 | YC-PT-08 | B9 | 11 |  |
| [QA-04](QA-04-hanh-trinh-okr.md) | Hành trình đầu cuối: đăng nhập, khởi tạo tenant, tổ chức, OKR, check-in | M | QA-03, CORE-01-08, DV-03-05 | QT-00..QT-03 | B9 | 14 |  |
| [QA-05](QA-05-hanh-trinh-giao-viec.md) | Hành trình đầu cuối: kế hoạch, giao việc, tiến độ, minh chứng, nghiệm thu | L | QA-03, CORE-03-07, CORE-02-22 | QT-04..QT-07 | B9 | 20 |  |
| [QA-06](QA-06-hanh-trinh-danh-gia.md) | Hành trình đầu cuối: khoá kỳ, tự đánh giá, thẩm định, quyết định ký số, xếp loại năm | L | QA-05, DV-06-06, CORE-04-10 | QT-08..QT-10 | B9 | 21 |  |
| [QA-07](QA-07-hanh-trinh-giam-sat.md) | Hành trình đầu cuối: kiểm điểm, hồ sơ số, khiếu nại, mở lại kỳ, cảnh báo, Policy Pack, bảng điều hành, AI, UI Pack | L | QA-06, CORE-06-08, CORE-07-11, DV-15-06 | QT-11..QT-18 | B9 | 23 |  |
| [QA-08](QA-08-k6-perf-01-10.md) | Kịch bản k6 PERF-01..PERF-10 | L | QA-03, DL-08 | TL-04 ch.7 | B9 | 14 |  |
| [QA-09](QA-09-gatling-luong-flowable.md) | Kịch bản góp ý G2: PERF-11..PERF-13 (Gatling cho Flowable) | M | QA-08, DV-06-04 | G2 | B9 | 15 |  |
| [QA-10](QA-10-chi-phi-bao-ve-g3.md) | Kịch bản góp ý G3: PERF-14..PERF-18, PERF-25 | M | QA-08, NEN-015 | G3 | B9 | 15 |  |
| [QA-11](QA-11-lo-frontend-sse-ai.md) | Kịch bản PERF-19..PERF-24, PERF-26 | M | QA-08 | TL-04 ch.7 | B9 | 15 |  |
| [QA-12](QA-12-chien-dich-cell-perf.md) | Chiến dịch thử tải trên CELL-PERF và hiệu chuẩn bảng định cỡ theo hồ sơ cell | L | QA-09, QA-10, QA-11, INF-21 | TL-05 hiệu chuẩn | B9 | 16 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần CELL-PERF |
| [QA-13](QA-13-asvs-zap.md) | Kiểm thử ATTT: OWASP ASVS 4.0.3 mức 2 (L3 cho xác thực, phiên, mật mã) và OWASP ZAP toàn hệ thống | L | QA-03 | NFR-08; TL-04 mục 9.8 | B9 | 12 |  |
| [QA-14](QA-14-thu-hong-dr-01-09.md) | Thử hỏng PERF-07 và diễn tập khôi phục DR-01..DR-09 | L | INF-17, QA-08 | TL-04 ch.8 | B9 | 15 |  |
| [QA-15](QA-15-dr-chuyen-vung.md) | Diễn tập khôi phục DR-10..DR-16, chuyển vùng giữa hai trung tâm dữ liệu | L | QA-14, INF-18, INF-20 | NFR-05 | B9 | 16 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần TTDL thứ hai |
| [QA-16](QA-16-uat-tenant-thi-diem.md) | Kiểm thử chấp nhận nghiệp vụ: tenant thí điểm chạy trọn một kỳ đánh giá | L | QA-07 | Đợt 2 | B9 | 24 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần tenant và người dùng thật |

## MOB · SVC-18 mobile-app (Flutter)

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [MOB-01](MOB-01-khung-flutter.md) | Khung Flutter: token thiết kế sang ThemeData, đăng nhập OIDC PKCE, client API sinh từ hợp đồng | M | NEN-006, DV-02-01 | SVC-18; ADR-10 | B10 | 4 |  |
| [MOB-02](MOB-02-ngoai-tuyen-sqlcipher.md) | Lưu trữ ngoại tuyến SQLCipher, khoá trong Keystore/Keychain, hàng đợi đồng bộ có Idempotency-Key | M | MOB-01 | SVC-18 | B10 | 5 |  |
| [MOB-03](MOB-03-viec-cua-toi-di-dong.md) | SCR-0504 Việc của tôi và cập nhật tiến độ trên di động | M | MOB-02, CORE-02-19, CORE-02-18 | QT-05, QT-06 | B10 | 18 |  |
| [MOB-04](MOB-04-check-in-kr-di-dong.md) | SCR-0302 Check-in KR trên di động | M | MOB-02, CORE-01-08 | QT-03 | B10 | 14 |  |
| [MOB-05](MOB-05-nop-minh-chung-ocr.md) | SCR-0702 Nộp minh chứng trên di động có OCR, tải theo khối | M | MOB-02, DV-08-02, CORE-03-05 | QT-07 | B10 | 19 |  |
| [MOB-06](MOB-06-bieu-mau-dong-tu-danh-gia.md) | Bộ dựng biểu mẫu từ JSON Schema và CEL trên Flutter, phiếu tự đánh giá | L | MOB-02, CORE-08-13, CORE-04-04 | QT-09 | B10 | 17 |  |
| [MOB-07](MOB-07-ky-so-mysign.md) | Ký số MySign trên di động | M | MOB-06, DV-10-02 | QT-09 | B10 | 18 |  |
| [MOB-08](MOB-08-che-do-cao-tuoi.md) | SCR-1803 Chế độ Đảng viên cao tuổi trên di động | M | MOB-03, MOB-06, DL-06 | NFR-11 | B10 | 19 |  |
| [MOB-09](MOB-09-thong-bao-day.md) | Thông báo đẩy FCM, APNs | S | MOB-01, DV-12-03, DV-12-05 | SVC-12 | B10 | 9 |  |
| [MOB-10](MOB-10-pipeline-phat-hanh.md) | Pipeline build, ký và phát hành ứng dụng; kiểm thử đầu cuối và khả năng truy cập trên di động | M | MOB-03, INF-06 | NFR-11 | B10 | 19 | Làm với bản giả lập (quyết định 20/10); nối hệ thật khi có: cần tài khoản cửa hàng ứng dụng |

## VH · Vận hành và đưa vào sử dụng

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [VH-01](VH-01-runbook-ops-01-13.md) | Runbook OPS-01..OPS-13 | L | INF-04 | TL-04 ch.11 | B11 | 4 |  |
| [VH-02](VH-02-runbook-ops-14-26.md) | Runbook OPS-14..OPS-26 | L | INF-17 | TL-04 ch.11 | B11 | 4 |  |
| [VH-03](VH-03-truc-su-co-cab.md) | Trực vận hành, mức sự cố SEV-1..SEV-4, quy trình thay đổi, đóng băng thay đổi | M | VH-01 | TL-04 ch.11 | B11 | 5 |  |
| [VH-04](VH-04-dien-tap-onboarding.md) | Diễn tập vòng đời tenant: đưa tenant mới vào vận hành trong một ngày (OPS-07) | M | DV-03-05, INF-04 | NFR-07 | B11 | 13 |  |
| [VH-05](VH-05-huong-dan-su-dung.md) | Hướng dẫn sử dụng theo vai trò (AC-01..AC-10) | L | QA-07 | YC-PT-11 | B11 | 24 |  |
| [VH-06](VH-06-tai-lieu-quan-tri.md) | Tài liệu quản trị, cài đặt cho ba mô hình phân phối | M | INF-23 | TL-04 ch.2 | B11 | 6 |  |
| [VH-07](VH-07-ho-so-cap-do-4.md) | Hồ sơ đề xuất cấp độ 4 theo TCVN 11930:2017 | M | QA-13, INF-19 | NFR-08 | B11 | 13 |  |
| [VH-08](VH-08-thi-diem-chuyen-tenant.md) | Kế hoạch đưa tenant thí điểm vào sử dụng và mở rộng theo nhóm tenant (hệ thống mới độc lập, không chuyển từ hệ cũ) | M | QA-16 | Đợt 2, Đợt 3 | B11 | 25 |  |
| [VH-09](VH-09-cham-checklist-quy.md) | Chấm lại checklist 110 tiêu chí và bảng chỉ số chuyển đổi mỗi quý | S | VH-03 | TL-01 ch.20 | B11 | 6 |  |

## WEB · Phân hệ web: phần còn thiếu so với toàn hệ thống

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [WEB-070](WEB-070-noi-api-that-okr-viec-minh-chung.md) | Nối các màn hình OKR, giao việc, minh chứng với API thật, tắt giả lập theo module bằng cấu hình | L | QA-03, CORE-01-08, CORE-02-19, CORE-03-07 | YC-PT-08 | W | 20 |  |
| [WEB-071](WEB-071-noi-api-that-danh-gia-giam-sat.md) | Nối các màn hình đánh giá, hồ sơ số, giám sát với API thật | L | WEB-070, CORE-04-10, CORE-06-08, CORE-07-11 | YC-PT-08 | W | 23 |  |
| [WEB-072](WEB-072-noi-api-that-quan-tri-cau-hinh.md) | Nối các màn hình quản trị, cấu hình, Policy Pack, bảng điều hành với API thật | L | WEB-070, CORE-05-10, CORE-08-11, DV-15-04 | YC-PT-08 | W | 21 |  |
| [WEB-073](WEB-073-xu-ly-cat-tai-retry-after.md) | Xử lý 429 và 503 có `Retry-After`, thử lại có backoff, giữ bản nháp | S | WEB-007 | TL-04 cắt tải | W | 1 |  |
| [WEB-074](WEB-074-traceparent-telemetry.md) | Gắn `traceparent` vào mọi lời gọi của `@vt/api-client` và liên kết với telemetry | S | WEB-011 | NFR-10 | W | 1 |  |
| [WEB-075](WEB-075-kham-pha-cell.md) | Khám phá cell khi đăng nhập (chuyển hướng 307, mã 421) | S | WEB-006, DV-01-04 | ADR-07 | W | 10 |  |
| [WEB-076](WEB-076-doc-co-tinh-nang.md) | Đọc cờ tính năng phía web | S | CORE-08-07, NEN-043 | MOD-08 | W | 13 |  |

## CNH · Cá nhân hoá theo người dùng (chờ người dùng chốt)

| Mã | Tên | Cỡ | Phụ thuộc | Truy vết | Luồng | Tầng | Ghi chú |
|---|---|---|---|---|---|---|---|
| [CNH-01](CNH-01-de-xuat-thiet-ke-ca-nhan-hoa.md) | Đề xuất bổ sung thiết kế cá nhân hoá: mã màn hình, API, bảng `config.user_preference` | M | – | ADR-05 | B2 | 1 | Người dùng đã đồng ý làm (20/10); CNH-01 bổ sung thiết kế trước |
| [CNH-02](CNH-02-luu-tuy-chon-ca-nhan.md) | Backend lưu tuỳ chọn cá nhân: bố cục trang chủ, cột, bộ lọc đã lưu, chế độ người cao tuổi | M | CNH-01, CORE-08-01, CORE-08-11 | NFR-11 | B2 | 15 | Người dùng đã đồng ý làm (20/10); CNH-01 bổ sung thiết kế trước |
| [CNH-03](CNH-03-bo-cuc-trang-chu-ca-nhan.md) | Web: lưu và khôi phục bố cục trang chủ và dashboard cá nhân | M | CNH-02, WEB-048 | SCR-0002, SCR-1602 | W | 16 | Người dùng đã đồng ý làm (20/10); CNH-01 bổ sung thiết kế trước |
| [CNH-04](CNH-04-cot-bo-loc-da-luu.md) | Web: cột và bộ lọc đã lưu trên các danh sách | M | CNH-02 | TL-02 quy ước danh sách | W | 16 | Người dùng đã đồng ý làm (20/10); CNH-01 bổ sung thiết kế trước |
| [CNH-05](CNH-05-dong-bo-che-do-cao-tuoi.md) | Web và di động: đồng bộ chế độ người cao tuổi qua máy chủ thay cho lưu cục bộ | S | CNH-02 | NFR-11 | W | 16 | Người dùng đã đồng ý làm (20/10); CNH-01 bổ sung thiết kế trước |
