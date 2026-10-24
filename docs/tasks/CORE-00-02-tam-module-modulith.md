# CORE-00-02 · ADR-01/ADR-23 Tám module lục giác, khai báo Spring Modulith, ma trận phụ thuộc, ArchUnit, sinh tài liệu module

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-00-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 (mốc M1) |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-00-01 |
| Truy vết | ADR-01, ADR-23 → MOD-01 `okr`, MOD-02 `task`, MOD-03 `product`, MOD-04 `appraisal`, MOD-05 `policy`, MOD-06 `dossier`, MOD-07 `oversight`, MOD-08 `config` → TL-01 "Khai báo module bằng Spring Modulith", "Quy tắc ArchUnit", "Giao tiếp giữa module" → `modulith.event_publication` → `GET /api/v1/jobs/{id}` (`getJob`, `cancelJob`) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng khung tám module của mission-core đúng ma trận phụ thuộc TL-01 và khoá nó bằng kiểm thử: mỗi module là một gói với bốn lớp và gói `internal`, khai báo `@ApplicationModule(allowedDependencies)`, cổng `api` có `@NamedInterface`, Spring Modulith `verify()` và ArchUnit chặn mọi phụ thuộc sai ngay khi build. Cùng lúc cấu hình Event Publication Registry cho sự kiện trong tiến trình và gắn `/jobs/{id}` dùng chung. Khi xong, các luồng nghiệp vụ B2, B3, B4 làm song song trên tám module mà không giẫm lên nhau và không thể vô tình tạo vòng phụ thuộc.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục SVC-05: "Cấu trúc gói Java theo module" (L389–448), "Khai báo module bằng Spring Modulith" (L450–480, ma trận phụ thuộc), "Quy tắc ArchUnit" (L482–551), "Giao tiếp giữa module" (L553–597, `PeriodApi`, `@ApplicationModuleListener`, cấu hình Event Publication Registry), "Sự kiện phát và nhận" (L830–832); ADR-23 (L3612).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 5.1 (MOD), 7 (sự kiện mỗi module phát và nhận).
- `contracts/openapi/modules/jobs.yaml`; kết quả NEN-013 (`JobSource`, `JobQueryService`), NEN-032 (`modulith.event_publication`).

## 3. Phạm vi công việc

- Tạo tám gói `vn.viettel.okrkpi.{config,policy,okr,task,product,appraisal,dossier,oversight}`, mỗi gói có `api` (`@NamedInterface("api")`, interface, DTO `record`, sự kiện công bố), `application`, `domain`, `infrastructure` (`web`, `persistence`, `client`, `listener`), `internal`, kèm `package-info.java` có `@ApplicationModule(displayName, allowedDependencies)` đúng ma trận: `config` không phụ thuộc; `policy` → `config`; `okr` → `config`, `policy`; `task` → `config`, `policy`, `okr`; `product` → `config`, `policy`, `task`; `appraisal` → `config`, `policy`, `task`, `product`, `okr`; `dossier` → `config`, `appraisal`; `oversight` → `config`, `appraisal`, `task`, `product`.
- Đặt sẵn giao diện cổng tối thiểu mà TL-01 nêu tên (`config.api.PeriodApi` với `current`, `assertWritable`, `PeriodSnapshot`; `okr.api.OkrApi`; `policy.api.PolicyQueryApi`) dưới dạng interface chưa hiện thực để các luồng biết hợp đồng nội bộ; hiện thực thuộc task của module.
- `ArchitectureRulesTest`: `ApplicationModules.of(MissionCoreApplication.class).verify()` và các luật TL-01 (`domainIsPure`, `applicationDoesNotSeeInfrastructure`, `internalIsPrivateToModule`, `controllersOnlyInWebAdapter`, `transactionsOnlyInApplication`, cấm `System.out`, ngoại lệ chung, `java.util.logging`) cộng luật thư viện nền (không `DataSource.getConnection()`, không client Valkey trực tiếp); kiểm thử âm chứng minh một phụ thuộc `config → task` làm build hỏng.
- Sinh tài liệu: `Documenter` xuất PlantUML C4 mức 3 và module canvas vào `backend/mission-core/target/spring-modulith-docs`; chép bản đã sinh vào `wiki/16-...` nếu trang đã có (hoặc ghi lệnh sinh vào báo cáo).
- Event Publication Registry: `spring.modulith.events.jdbc.schema-initialization.enabled=false`, `republish-outstanding-events-on-restart=true`, bảng `modulith.event_publication` (NEN-032); một cặp sự kiện mẫu giữa hai module trong kiểm thử `@ApplicationModuleTest` để chứng minh bất đồng bộ sau commit và phát lại khi khởi động lại; ghi quy tắc: sự kiện ra ngoài luôn qua outbox, module trong lõi nghe sự kiện ứng dụng, không đọc lại từ Kafka.
- Gắn `getJob` và `cancelJob` (`jobs.yaml`, owner SVC-05) bằng `JobQueryService` của NEN-013: chỉ người khởi tạo xem được, người khác nhận 404; kiểm thử hợp đồng kế thừa `ContractConformanceTest` của NEN-006 cho `jobs.yaml`.

## 4. Ngoài phạm vi

- Nghiệp vụ của từng module, migration schema module: nhóm CORE-01..CORE-08.
- `org_replica`: CORE-00-03.

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/**` (gói module, `package-info.java`, cổng `api` tối thiểu, `jobs`)
- `backend/mission-core/src/main/resources/application*.yaml` (cấu hình Modulith), `backend/mission-core/src/test/**`
- `wiki/16-kien-truc-mission-core-va-microservice.md` (chỉ chèn sơ đồ module đã sinh)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl mission-core -am verify` chạy xanh gồm `verify()`, ArchUnit, `@ApplicationModuleTest` và kiểm thử hợp đồng `jobs.yaml`.
- Kiểm thử âm (phụ thuộc sai, truy cập `internal` của module khác) làm build thất bại; ghi đầu ra lỗi vào báo cáo rồi gỡ.
- Sơ đồ module sinh ra khớp ma trận TL-01 L450–480.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
