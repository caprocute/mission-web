# CORE-00-04 · QT-00/SCR-0002 Phía máy chủ của trang chủ "Việc của tôi hôm nay"

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-00-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-02-18, CORE-01-08 (bổ sung nên có: CORE-08-09 cho bố cục SDUI có phiên bản, CORE-08-02 cho mốc kỳ, NEN-041 cho đánh giá `visibleWhen`) |
| Truy vết | QT-00 → SCR-0002 → `GET /api/v1/ui/layouts/home` (`getHomeLayout`, MOD-08), `GET /api/v1/tasks/mine?view=today` (`listMyTasks`, MOD-02), `GET /api/v1/okr/key-results?owner=me&needCheckin=true` (`listOkrKeyResults`, MOD-01), `GET /api/v1/periods` (`listPeriods`) → bảng `task.task`, `task.task_assignee`, `okr.key_result`, `okr.kr_checkin`, `config.period`, `config.ui_pack_version` → MSG-00-09, MSG-00-11 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Làm cho trang chủ SCR-0002 tải đủ dữ liệu trong p95 ≤ 1 giây (TL-02a QT-00 bước 8) bằng đúng các API mà hợp đồng đã định, không tạo endpoint tổng hợp mới: máy chủ trả bố cục SDUI của trang chủ đã lọc theo vai trò trong ngữ cảnh phân công, và bảo đảm các khối dữ liệu "Việc hôm nay", "KR cần check-in", "Mốc của kỳ" có số đếm đúng và nhanh. Khi xong, frontend gọi song song ba bốn API của hợp đồng và có trang chủ đúng TL-02, kể cả khi tenant chưa có UI Pack riêng.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-00 "Mô tả các bước" bước 8 (L331), đặc tả SCR-0002 (L436–474: bảng trường 1–10, bảng nút và hành động, MSG-00-09..MSG-00-11).
- `contracts/openapi/modules/config.yaml` thao tác `getHomeLayout` (`/ui/layouts/home`) và `getCurrentUiPack`; `contracts/openapi/modules/tasks.yaml` `listMyTasks` (`view=today`, lược đồ `MyTasksPage.counts`); `contracts/openapi/modules/okr.yaml` `listOkrKeyResults` (`needCheckin`, `counts.needCheckin`); `config.yaml` `listPeriods` (`PeriodMilestones`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SDUI renderer trong Angular" (L1683–1727, `visibleWhen`) và "Cơ chế bộ đệm: CDN, ETag, IndexedDB" (L1753–1757); `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` chương "Bộ nhớ đệm Valkey" mục "Chính sách theo loại dữ liệu" (bộ đếm cá nhân 60 giây).
- `wiki/15a-ma-tran-phan-quyen.md` các dòng SCR-0002.

## 3. Phạm vi công việc

- Hiện thực `getHomeLayout` trong MOD-08: lấy bố cục trang chủ từ UI Pack hiện hành của tenant (nếu CORE-08-09 đã có) hoặc bố cục mặc định của khối đóng gói sẵn; đánh giá `visibleWhen` ở máy chủ bằng `vt-cel` với ngữ cảnh vai trò của phân công đang chọn và loại bỏ khối không được thấy; trả `ETag` và hỗ trợ `If-None-Match` → 304; khối "Tóm tắt tuần" chỉ có khi cờ tính năng AI của tenant bật.
- Bảo đảm số đếm trang chủ: kiểm `listMyTasks?view=today` trả đúng việc đến hạn hôm nay (trường 3), việc chậm (trường 4, cờ `qua_han`), tối đa 7 dòng chưa `HOAN_THANH`/`HUY` sắp theo mức khẩn (trường 7); `listOkrKeyResults?owner=me&needCheckin=true` trả `counts.needCheckin` là KR chưa có check-in trong tuần (trường 5); nếu các task CORE-02-18, CORE-01-08 chưa đáp ứng chỗ nào thì bổ sung truy vấn đọc trong đúng module đó, không tạo đường dẫn mới.
- Đệm bộ đếm cá nhân trong Valkey 60 giây qua `TenantCacheKey` (khoá theo tenant, người, phân công), vô hiệu khi nhận sự kiện ứng dụng EVT-05, EVT-06 (MOD-02) và EVT-02 (MOD-01) của chính người đó; màn hình sửa vẫn đọc thẳng CSDL.
- Mốc kỳ (trường 8): bảo đảm `listPeriods` trả `milestones` của kỳ đang mở; tuần hiện hành (trường 1) tính từ `config.period.start_date` ở phía giao diện theo hợp đồng.
- Đo hiệu năng: kiểm thử tích hợp mồi dữ liệu cỡ một người dùng điển hình (≥ 200 nhiệm vụ, ≥ 20 KR) và một kịch bản tải nhỏ (k6 hoặc JUnit đồng thời) đo p95 của bốn lời gọi chạy song song.
- Điểm lệch cần lưu ý: TL-02a gợi ý một "API tổng hợp trang chủ" (việc BE-00-9) và trường 6 "Số minh chứng chờ nghiệm thu"; hợp đồng (ADR-32) không có endpoint tổng hợp và `listEvidences` (`status=DA_NOP,DANG_NGHIEM_THU&reviewer=me`) không trả số đếm; task theo hợp đồng, không thêm đường dẫn, và ghi vào báo cáo đề xuất mở task sửa hợp đồng nếu cần số đếm minh chứng.

## 4. Ngoài phạm vi

- Thông báo (`/notifications`, SVC-12): DV-12-01, DV-12-02; tóm tắt tuần do AI: nhóm DV-14.
- Đổi phân công `PUT /org/me/context`: DV-02-02; giao diện trang chủ: các task WEB.

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**` (phần bố cục trang chủ)
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`, `.../okr/**` (chỉ truy vấn đọc và bộ đếm phục vụ SCR-0002)
- `backend/mission-core/src/main/resources/**` (bố cục mặc định của khối), `backend/mission-core/src/test/**`

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl mission-core -am verify` chạy xanh, gồm kiểm thử hợp đồng `getHomeLayout`, `listMyTasks`, `listOkrKeyResults` với `examples` của hợp đồng.
- Bố cục trả cho `member` không chứa khối chỉ dành cho `leader`/`manager` theo ma trận SCR-0002; gửi `If-None-Match` đúng nhận 304.
- p95 của bốn lời gọi song song ≤ 1 giây trong kịch bản đo (báo số đo, cấu hình máy, số dòng mồi).
- Số đếm đúng sau khi cập nhật tiến độ: tối đa 60 giây trễ do đệm, hoặc ngay lập tức khi nhận sự kiện vô hiệu (kiểm thử).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
