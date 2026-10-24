# CORE-02-17 · QT-05/BR-05-08 Đăng ký nhiệm vụ, duyệt hoặc từ chối, đưa vào điều chỉnh kế hoạch

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-17` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-02-14; cần CORE-02-10 (điều chỉnh kế hoạch) để đưa đăng ký đã duyệt vào phiên bản n+1 (bổ sung) |
| Truy vết | QT-05 bước 3 → SCR-0503 → API `POST /api/v1/tasks/registrations`, thao tác duyệt hoặc từ chối đăng ký (chưa có trong `tasks.yaml`) → MOD-02 → bảng `task.task` (`source='DANG_KY'`), `task.plan_item`, `task.outbox_event` → EVT-21; BR-05-08; MSG-05-12 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho cán bộ (AC-06) chủ động đăng ký nhiệm vụ từ danh mục áp dụng; quản lý trực tiếp duyệt hoặc từ chối, và đăng ký được duyệt được đưa vào lần điều chỉnh kế hoạch gần nhất. Nhiệm vụ đăng ký chưa được duyệt không giao được (BR-05-08), nhờ vậy việc tự đăng ký không làm lệch kế hoạch đã ký.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-05 "Mô tả các bước" bước 3 (L1428), BR-05-08 (L1457–1471), SCR-0503 (nút "Gửi đăng ký", L1613–1619), MSG-05-12.
- `contracts/openapi/modules/tasks.yaml`: `registerTask` (lược đồ `TaskRegistration`), `listApplicableCatalogItems`, `createPlanAdjustment`.
- Thẻ CORE-02-10 (điều chỉnh kế hoạch) và CORE-02-14 (`AssignmentChecker`).

## 3. Phạm vi công việc

- Hiện thực `registerTask` (Idempotency-Key): chỉ từ mục danh mục đang áp dụng cho vị trí của người gọi, tạo nhiệm vụ `NHAP` `source='DANG_KY'` có người đăng ký là chủ trì, hiển thị với người đăng ký (khác nhiệm vụ nháp thông thường), ghi EVT-21 tới quản lý trực tiếp (phân giải qua `org_replica`).
- Điểm lệch cần lưu ý: TL-02a yêu cầu quản lý duyệt hoặc từ chối đăng ký nhưng `tasks.yaml` không có thao tác này. Bước đầu của task là thêm thao tác theo quy ước `POST /tasks/{id}/<động-từ>` vào `tasks.yaml` (chỉ thêm), chạy lint và `openapi-diff`, được người phụ trách hợp đồng duyệt rồi mới viết mã.
- Hiện thực duyệt đăng ký: chỉ quản lý trực tiếp, khác người đăng ký; đánh dấu đã duyệt và gắn nhiệm vụ vào phiên bản điều chỉnh kế hoạch gần nhất ở `NHAP` (tạo dòng kế hoạch mới có `change_reason` "đăng ký nhiệm vụ"); chưa có phiên bản điều chỉnh mở thì xếp chờ và báo người lập (MSG-05-12).
- Hiện thực từ chối có lý do bắt buộc: nhiệm vụ chuyển `HUY` qua máy trạng thái, EVT-21 tới người đăng ký.
- Mở rộng `AssignmentChecker`: nhiệm vụ `DANG_KY` chưa duyệt thì chặn giao (BR-05-08).

## 4. Ngoài phạm vi

- Giao diện đăng ký trên web và di động (task WEB, MOB).

## 5. Tệp được phép sửa

- `contracts/openapi/modules/tasks.yaml` (chỉ thêm thao tác duyệt, từ chối đăng ký, sau khi được duyệt)
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`

## 6. Tiêu chí hoàn thành

- `cd contracts && npm run lint` xanh; `openapi-diff` không báo phá tương thích.
- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử: đăng ký mục không áp dụng cho vị trí trả 422; giao nhiệm vụ đăng ký chưa duyệt bị chặn với `rule` BR-05-08; duyệt tạo đúng một dòng kế hoạch trong phiên bản điều chỉnh đang mở; người đăng ký tự duyệt trả 403.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
