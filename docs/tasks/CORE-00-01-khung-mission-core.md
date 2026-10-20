# CORE-00-01 · SVC-05 Khung ứng dụng mission-core: Spring Boot 3.5, luồng ảo, tám DataSource theo module, Actuator, cấu hình mẫu

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-00-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 (mốc M1) |
| Luồng đề xuất | B2 |
| Cỡ việc | L (nên tách: a) ứng dụng, cấu hình, Actuator, probe, ảnh; b) tám DataSource + giao dịch theo module + định tuyến bản sao đọc) |
| Phụ thuộc | NEN-019, NEN-031 (bổ sung nên có: NEN-006, NEN-013, NEN-030 để nối hợp đồng, quy ước API và kiểm phiên bản lược đồ) |
| Truy vết | ADR-01, ADR-02, ADR-22 → SVC-05 mission-core → TL-01 "Công nghệ và phiên bản", "Dữ liệu sở hữu", "Co giãn, chịu lỗi, bảo mật", "Cấu hình mẫu" → tài khoản `svc_<module>` trên 8 schema module + SELECT `org_replica` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng ứng dụng `mission-core` chạy được từ mẫu service của NEN-019: một tiến trình Spring Boot 3.5 trên Java 21 với luồng ảo, tám nhóm kết nối CSDL riêng cho tám module (mỗi module chỉ dùng tài khoản `svc_<module>` của mình qua PgBouncer), giao dịch gắn tenant theo NEN-010, Actuator và probe đúng TL-04, cấu hình mẫu theo TL-01 và values Helm mẫu theo TL-04. Khi xong, ứng dụng khởi động với hạ tầng dev hoặc Testcontainers, báo sẵn sàng, và CORE-00-02 có chỗ để đặt tám module.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục SVC-05: "Vai trò" (L379–383), "Công nghệ và phiên bản" (L385–387), "Quản lý giao dịch" (L710–752), "Dữ liệu sở hữu" (L824–828), "Co giãn, chịu lỗi, bảo mật" (L834–840), "Cấu hình mẫu" (L842–901); "Bản sao đọc và CQRS" (L2767–2769), "Định cỡ pool kết nối" (L2777–2781).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Nguyên tắc cấu hình chung" (L835–849), "Bảng cấu hình theo service" dòng SVC-05 (L851–881), "Helm values mẫu cho mission-core" (L883–969).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Lõi nghiệp vụ mission-core (SVC-05) và tám module" (L363–471), "Kết nối và PgBouncer" (L4570–4574), "Đọc từ bản sao" (L4576–4578).
- Kết quả NEN-010, NEN-016, NEN-019; `contracts/openapi/modules/jobs.yaml`.

## 3. Phạm vi công việc

- Tạo `backend/mission-core/` bằng `scripts/new-service.sh` rồi chỉnh: lớp `vn.viettel.okrkpi.MissionCoreApplication`, `spring.threads.virtual.enabled=true`, `open-in-view=false`, `hibernate.jdbc.batch_size=50`, `order_updates=true`, ghi nhật ký truy vấn > 500 ms, `ddl-auto=validate`, `spring.flyway.enabled=false`, `SchemaVersionGuard` cho chín schema.
- Tám DataSource cấu hình dưới `okrkpi.datasources.<module>` (`okr`, `task`, `product`, `appraisal`, `policy`, `dossier`, `oversight`, `config`): URL PgBouncer với `prepareThreshold=0`, `ApplicationName=mission-core-<module>`, kích thước pool theo values mẫu TL-04 (task 8, okr 4, appraisal 4, product 3, config 2, policy 1, dossier 1, oversight 1), `connectionTimeout` 2 s, `maxLifetime`, `leakDetectionThreshold` như TL-04 L883–969; mỗi module một `EntityManagerFactory` quét gói `<module>.infrastructure.persistence` và một `TenantJpaTransactionManager` đặt tên theo module.
- Không dùng XA; ghi trong README của mission-core quy tắc TL-01: use case chạm hai module dùng kết nối module kia trong giao dịch chỉ đọc riêng hoặc qua `*.api`; không gọi mạng trong giao dịch; `@Transactional(timeout = 5)`.
- Định tuyến bản sao đọc theo TL-01 L2767–2769 (chưa có hàng riêng trong danh mục, bổ sung ở đây): giao dịch `readOnly` đi `readPooler` khi được bật bằng cấu hình, cờ "đã ghi trong 2 giây" đọc bản chính, mặc định tắt ở dev.
- Actuator trên cổng quản trị 8081: `health`, `prometheus`, `info`; readiness gồm tám pool và producer Kafka, liveness không phụ thuộc ngoài; tắt êm 25 s; observation key `cell`.
- Values Helm mẫu `deploy/charts/mission-core/values.yaml` theo TL-04 L883–969 (Rollout canary, replicas 6/24, 8 pool, OTel, secret tham chiếu `kv/cells/<cell>/pg-core/<module>`), không có giá trị bí mật.
- Hồ sơ `dev` chạy với `infra/dev` (mission-core nghe cổng 35100 theo ghi chú server dev trong `infra/dev/README.md`), mật khẩu đọc từ biến môi trường.
- Kiểm thử tích hợp Testcontainers (dùng `vt-testkit` nếu có): khởi động đủ tám pool với tám tài khoản `svc_<module>`, `/actuator/health/readiness` trả `UP`; tắt CSDL thì readiness `DOWN` còn liveness `UP`; một truy vấn qua pool `task` không đọc được schema `appraisal` (quyền NEN-031).

## 4. Ngoài phạm vi

- Tám module, Spring Modulith, ArchUnit, `/jobs`: CORE-00-02; `org_replica`: CORE-00-03.
- Migration schema nghiệp vụ: CORE-0x-01; chart thư viện và triển khai: INF-01, INF-02; chạy trên server dev: INF-22.

## 5. Tệp được phép sửa

- `backend/mission-core/**` (mới), `backend/pom.xml` (chỉ thêm mô-đun)
- `deploy/charts/mission-core/values.yaml` (mới)
- `wiki/16-kien-truc-mission-core-va-microservice.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl mission-core -am verify` chạy xanh với kiểm thử tích hợp ở mục 3.
- `./mvnw -q -pl mission-core jib:buildTar` tạo ảnh không root; khởi động ảnh với Testcontainers hoặc hạ tầng dev cục bộ và gọi probe thành công (ghi lệnh và kết quả).
- Thời gian khởi động được đo và ghi vào báo cáo (mục tiêu probe startup TL-04: dưới 150 s).
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 16 (nếu đã có) mục "Cấu hình mission-core".

## 7. Báo cáo

Theo AGENTS.md mục 6.
