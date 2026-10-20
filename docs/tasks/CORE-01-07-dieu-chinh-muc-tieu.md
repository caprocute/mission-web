# CORE-01-07 · QT-02/BR-02-10 Điều chỉnh mục tiêu giữa kỳ bằng phiên bản mới

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | S |
| Phụ thuộc | CORE-01-06 |
| Truy vết | QT-02 → SCR-0202, SCR-0203 → API thao tác tạo phiên bản điều chỉnh (chưa có trong `okr.yaml`, xem mục 3) → MOD-01 → bảng `okr.objective` (`version_no`), `okr.key_result`, `okr.objective_review` → BR-02-10 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho phép điều chỉnh mục tiêu đã duyệt khi kỳ còn `MO` mà không sửa tại chỗ: hệ thống tạo phiên bản mới ở trạng thái nháp rồi trình duyệt lại, trong lúc phiên bản đã duyệt vẫn hiệu lực cho check-in. Khi phiên bản mới được duyệt, phiên bản cũ hết hiệu lực từ ngày duyệt. Nhờ vậy lịch sử mục tiêu không bị ghi đè, đúng BR-02-10.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-02 "Trạng thái đối tượng" (điều chỉnh giữa kỳ, L804, L812), "Quy tắc nghiệp vụ" BR-02-10, "Ngoại lệ và xử lý" (cấp trên điều chỉnh sau khi cấp dưới đã duyệt, L836).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Bảng okr.objective" (cột `version_no`) và "Bảng okr.objective_review".
- `contracts/openapi/modules/okr.yaml`: `updateOkrObjective` (409 khi không ở `NHAP`), lược đồ `OkrObjective.versionNo`; `contracts/openapi/README.md` mục quy ước và "Điểm đã chốt".

## 3. Phạm vi công việc

- Điểm lệch cần lưu ý: TL-02a yêu cầu tạo phiên bản điều chỉnh, nhưng `okr.yaml` không có thao tác nào tạo phiên bản mới cho mục tiêu `DA_DUYET`, và mô tả `versionNo` là "lần trình duyệt". Bước đầu của task là thêm một thao tác theo quy ước `POST /okr/objectives/{id}/<động-từ>` vào `okr.yaml` (chỉ thêm, không đổi thao tác cũ), chạy `npm run lint` và `openapi-diff` chứng minh không phá tương thích, và được người phụ trách hợp đồng duyệt trước khi viết mã.
- Hiện thực use case tạo phiên bản điều chỉnh: chỉ khi mục tiêu `DA_DUYET` và kỳ `MO`; tạo bản ghi mục tiêu mới cùng `code`, `version_no` tăng 1, sao chép KR (giữ mã KR) và căn cứ, trạng thái `NHAP` để người soạn sửa rồi trình qua luồng của CORE-01-05 (mô hình "mỗi phiên bản một dòng" là suy luận từ cột `version_no`, ghi vào `wiki/14`).
- Bảo đảm chỉ một phiên bản đang điều chỉnh tại một thời điểm; tạo lần hai trả 409 `OKR-409-INVALID-STATE`.
- Khi phiên bản mới được duyệt: phiên bản cũ chuyển hết hiệu lực từ ngày duyệt (không xoá, không sửa nội dung), API đọc cây chỉ trả phiên bản hiệu lực kèm số phiên bản; phát EVT-01 cho phiên bản mới.
- Khi mục tiêu cấp trên được điều chỉnh sau khi mục tiêu cấp dưới đã duyệt, ghi outbox EVT-21 tới chủ sở hữu các mục tiêu liên kết, không tự đổi mục tiêu cấp dưới (L836).
- Chuyển giá trị check-in sang KR của phiên bản mới thuộc CORE-01-12, task này chỉ phát tín hiệu nội bộ (sự kiện ứng dụng Spring Modulith) để CORE-01-12 lắng nghe.

## 4. Ngoài phạm vi

- Chuyển giá trị hiện tại và uỷ quyền check-in (CORE-01-12).
- Màn hình điều chỉnh ở web (task WEB).

## 5. Tệp được phép sửa

- `contracts/openapi/modules/okr.yaml` (chỉ thêm một thao tác, sau khi được duyệt)
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd contracts && npm run lint` xanh; `openapi-diff` giữa bản cũ và bản mới của `okr.yaml` không báo thay đổi phá tương thích.
- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử tích hợp: trong khi phiên bản 2 ở `CHO_DUYET`, check-in vào KR của phiên bản 1 vẫn thành công; sau khi phiên bản 2 được duyệt, cây chỉ hiện phiên bản 2 và phiên bản 1 vẫn đọc được qua lịch sử.
- PUT vào mục tiêu `DA_DUYET` vẫn trả 409 (không sửa tại chỗ).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
