# NEN-019 · TL-04 Mẫu service Java (lục giác, actuator, probe, values Helm) và lệnh `scripts/new-service.sh`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-019` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-010, NEN-011, NEN-012, NEN-016 |
| Truy vết | ADR-01, ADR-16, ADR-24 → TL-04 "Cấu hình triển khai từng service" (WI-20, WI-23) → SVC-04, SVC-06, SVC-07, SVC-08, SVC-10, SVC-11, SVC-13 và SVC-05 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Có một mẫu service Java chuẩn và một lệnh tạo service mới từ mẫu, để mọi service Java (org, workflow, scoring, evidence, esign, document, integration và cả mission-core) khởi đầu giống nhau: cấu trúc lục giác, thư viện nền đã nối, Actuator và probe đúng TL-04, tắt êm, cấu hình ảnh Jib, values Helm khớp chart thư viện, kiểm thử kiến trúc sẵn. Khi xong, `./scripts/new-service.sh org-service` sinh một service build xanh trong vài giây, các task DV-xx-01 chỉ việc thêm nghiệp vụ.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` chương "Cấu hình triển khai từng service": "Nguyên tắc cấu hình chung" (L835–849: probe trên 8081, `server.shutdown=graceful`, `timeout-per-shutdown-phase=25s`, `preStop` 5 s, `terminationGracePeriodSeconds: 40`, `MaxRAMPercentage=70`, luồng ảo), "Bảng cấu hình theo service" (L851–881), "Helm values mẫu cho mission-core" (L883–969), "Manifest mẫu: Deployment, HPA và PDB" (L1036–1196).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Cấu trúc gói Java theo module" (L389–448) và "Quy tắc ArchUnit" (L482–551).
- `scripts/new-remote.sh` (mẫu lệnh tương tự phía web), `docs/quy-dinh/BACKEND.md`, `docs/quy-dinh/HA-TANG.md`.

## 3. Phạm vi công việc

- Tạo mẫu `backend/services/service-template/` (Spring Boot 3.5, Java 21) với gói gốc `vn.viettel.okrkpi.<ten>` và bốn lớp `api`, `application`, `domain`, `infrastructure` (+ `internal`), một use case mẫu tối giản đi qua đủ lớp, phụ thuộc `vt-kernel`, `vt-platform-starter`, `vt-api-contract` (phần của service), `vt-contract-test` cho kiểm thử.
- `application.yaml` mẫu: `spring.threads.virtual.enabled=true`, `spring.jpa.open-in-view=false`, `spring.flyway.enabled=false`, `ddl-auto=validate`, cổng quản trị 8081 với `/actuator/health/liveness` (không kiểm phụ thuộc ngoài), `/actuator/health/readiness` (pool CSDL, producer Kafka), `/actuator/prometheus`, tắt êm 25 s; hồ sơ `dev` trỏ hạ tầng `infra/dev` qua biến môi trường, không chứa mật khẩu.
- Kiểm thử mẫu: `ArchitectureRulesTest` áp các luật TL-01 (`domainIsPure`, `applicationDoesNotSeeInfrastructure`, `internalIsPrivateToModule`, `controllersOnlyInWebAdapter`, `transactionsOnlyInApplication`, cấm `System.out`, ngoại lệ chung, `java.util.logging`) cộng luật của thư viện nền; một kiểm thử tích hợp Testcontainers khởi động ứng dụng và gọi probe.
- Cấu hình Jib kế thừa từ POM cha; nhãn ảnh gồm tên service và phiên bản.
- Mẫu values Helm `backend/services/service-template/helm/values.yaml` theo cấu trúc TL-04 WI-23 (workload, replicas, resources, jvm, env OTel, datasources, probes, pdb, scaling, secrets dạng tham chiếu OpenBao), giá trị mặc định cho cell nhỏ; chỉ là values, chart thư viện thuộc INF-01.
- Viết `scripts/new-service.sh <ten-dich-vu> [--go|--java]` (bản này chỉ cần `--java`): chép mẫu sang `backend/services/<ten>/`, đổi tên gói và `artifactId`, thêm mô-đun vào `backend/services/pom.xml`, chép values sang `deploy/charts/<ten>/values.yaml`; từ chối tên không có trong danh mục SVC của baseline mục 5 (đối chiếu danh sách tên triển khai).
- Kiểm thử lệnh: tạo service thử trong thư mục tạm, build xanh, rồi dọn sạch; script chạy được với `bash -n` và `shellcheck`.

## 4. Ngoài phạm vi

- Chart thư viện `vt-service` và chart từng service: INF-01, INF-02.
- Khung mission-core (nhiều DataSource, tám module): CORE-00-01, CORE-00-02; khung service Go: NEN-017 và DV-03-01/DV-09-01/DV-12-01.

## 5. Tệp được phép sửa

- `backend/services/service-template/**`, `backend/services/pom.xml`, `backend/pom.xml` (chỉ thêm mô-đun `services`)
- `scripts/new-service.sh` (mới)
- `deploy/charts/.gitkeep` hoặc `deploy/charts/README.md` (chỉ để giữ thư mục và hướng dẫn)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl services/service-template -am verify` chạy xanh, gồm ArchUnit và kiểm thử probe.
- `./scripts/new-service.sh scoring-service` trong bản sao tạm sinh service build xanh; tên không có trong baseline (ví dụ `abc`) bị từ chối.
- `shellcheck scripts/new-service.sh` không cảnh báo.
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 16 (nếu đã có) mục "Tạo service mới".

## 7. Báo cáo

Theo AGENTS.md mục 6.
