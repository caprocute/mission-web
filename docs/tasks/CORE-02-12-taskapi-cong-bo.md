# CORE-02-12 · MOD-02/ADR-23 `TaskApi` công bố và sự kiện trong tiến trình cho MOD-03, MOD-04

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-12` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | S |
| Phụ thuộc | CORE-02-13 |
| Truy vết | ADR-23 → MOD-02 (`task::api`) → MOD-03, MOD-04, MOD-07 → bảng `task.task`, `task.task_assignee`, `modulith.event_publication` → sự kiện trong tiến trình `NhiemVuDaGiao`, `TrangThaiNhiemVuDoi` (tương ứng EVT-04, EVT-06) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Công bố bề mặt `task.api` để các module được phép phụ thuộc `task` (MOD-03 `product`, MOD-04 `appraisal`, MOD-07 `oversight`) đọc nhiệm vụ và nhận sự kiện trong tiến trình mà không chạm vào bảng hay lớp nội bộ của MOD-02. Sự kiện trong tiến trình đi qua Event Publication Registry của Spring Modulith (ADR-23) để không mất khi pod dừng; MOD-04 có bộ lắng nghe mẫu `TaskStatusListener` làm mới dữ liệu đầu vào hồ sơ đánh giá khi nhiệm vụ kết thúc.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Cấu trúc gói Java theo module" (L389–449), "Khai báo module bằng Spring Modulith" (L450), "Giao tiếp giữa module" (L553–598), "ADR-23. Event Publication Registry cho sự kiện trong tiến trình" (L3612).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục bảng `modulith.event_publication` (L894–908).
- Bản trích `extract/tl01-thanh-phan.md` mục A4 (ma trận phụ thuộc module) và B2.

## 3. Phạm vi công việc

- Viết `vn.viettel.okrkpi.task.api.TaskApi`, `TaskSummary` (record bất biến) và hiện thực `TaskApiService`; tối thiểu gồm: lấy tóm tắt nhiệm vụ theo id; liệt kê nhiệm vụ của một người hoặc đơn vị trong kỳ cho dữ liệu đầu vào đánh giá; lệnh chuyển trạng thái do MOD-03 yêu cầu (`SUBMIT`, `APPROVE`, `REWORK` của máy trạng thái CORE-02-13); ghi nhận nhiệm vụ đã có minh chứng `DA_XAC_NHAN` để MOD-02 dùng cho BR-04-11, BR-06-09 và guard CEL (tên phương thức là suy luận).
- Viết sự kiện công bố `task.api.events.NhiemVuDaGiao` và `TrangThaiNhiemVuDoi` (record gồm tenant, kỳ, nhiệm vụ, trạng thái mới, danh sách người được giao, `isTerminal`) và phát chúng trong cùng giao dịch với outbox EVT-04, EVT-06.
- Lý do thiết kế ghi trong chú thích: MOD-02 không được phụ thuộc `product` (ma trận chỉ cho `product` → `task`), nên mọi thông tin về minh chứng mà MOD-02 cần phải do MOD-03 đẩy vào qua `TaskApi`.
- Viết bộ lắng nghe mẫu `@ApplicationModuleListener` trong mã kiểm thử để chứng minh sự kiện tới và được phát lại sau khởi động lại; `TaskStatusListener` thật của MOD-04 do task CORE-04 viết theo mẫu TL-01.
- Cập nhật `package-info.java` của `task` khai báo `@NamedInterface("api")` và `allowedDependencies` đúng ma trận.

## 4. Ngoài phạm vi

- Logic làm mới dữ liệu đánh giá trong MOD-04 (CORE-04); liên động minh chứng với máy trạng thái phía MOD-03 (CORE-03-02).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, gồm `ApplicationModules.verify()`: không module nào truy cập gói ngoài `task.api`; `task` không phụ thuộc `product`, `appraisal`.
- Kiểm thử Spring Modulith `Scenario`: chuyển nhiệm vụ sang `HOAN_THANH` phát `TrangThaiNhiemVuDoi` với `isTerminal = true`; bộ lắng nghe lỗi thì bản ghi `event_publication` còn mở và được phát lại khi khởi động lại.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
