# CORE-02-10 · QT-04/BR-04-10..12 Điều chỉnh kế hoạch: phiên bản n+1, so sánh, giới hạn số lần

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | L |
| Phụ thuộc | CORE-02-09; cần CORE-02-12 (`TaskApi` nhận thông tin minh chứng đã xác nhận do MOD-03 đẩy vào) cho BR-04-11 (bổ sung) |
| Truy vết | QT-04 → SCR-0402, SCR-0403 → API `POST /api/v1/plans/{id}/versions` (tạo điều chỉnh), `GET /plans/{id}/versions/{v}/diff?base=`, `PUT /plans/{id}/versions/{v}`, `POST /plans/{id}/versions/{v}/check` → MOD-02, MOD-05 → bảng `task.plan_version` (`change_reason`), `task.plan_item` (`origin_item_id`, `change_reason`), `task.task` → BR-04-10, BR-04-11, BR-04-12; MSG-04-07, MSG-04-11, MSG-04-12 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho đơn vị điều chỉnh kế hoạch đã duyệt mà không sửa phiên bản đã ký: tạo phiên bản n+1 từ phiên bản hiệu lực, ghi lý do cho từng dòng thay đổi, giới hạn số lần điều chỉnh mỗi quý theo Policy Pack, không bỏ việc đã có minh chứng được xác nhận và không đổi hạn của nhiệm vụ đang thực hiện. Người duyệt xem so sánh thêm, sửa, bỏ giữa hai phiên bản trước khi duyệt; phiên bản mới hiệu lực từ ngày duyệt, không hồi tố.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-04 "Mô tả các bước" (hạn điều chỉnh, L1191), "Trạng thái đối tượng" (L1195–1209), BR-04-10..BR-04-12 (L1211–1230), "Ngoại lệ và xử lý" (ngoại lệ thêm một lần điều chỉnh, L1237), SCR-0403 (so sánh, L1371).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Bảng task.plan_version", "Bảng task.plan_item" (`origin_item_id`, `change_reason`).
- `contracts/openapi/modules/tasks.yaml`: `createPlanAdjustment`, `diffPlanVersions` (tham số `base`, `mode` `changes|all`, giá trị `THEM`, `SUA`, `BO`, `KHONG_DOI`), `savePlanVersion`, `checkPlanVersion`.

## 3. Phạm vi công việc

- Hiện thực `createPlanAdjustment`: chỉ khi có phiên bản `DA_DUYET` hiệu lực, kỳ `MO`, trước hạn điều chỉnh (tham số, mặc định trước tháng cuối quý); số lần điều chỉnh đã duyệt trong quý chưa đạt giới hạn (tham số, mặc định 2) thì tạo phiên bản n+1 `NHAP` sao chép mọi dòng, đặt `origin_item_id`; vượt giới hạn trả 422 kèm MSG-04-07.
- Kiểm trong `savePlanVersion` và `checkPlanVersion` của phiên bản điều chỉnh: mỗi dòng thêm, sửa, bỏ phải có `change_reason`; phiên bản n+1 phải có lý do chung (CHECK đã có ở CSDL).
- BR-04-11: bỏ dòng đã sinh nhiệm vụ có minh chứng `DA_XAC_NHAN` bị chặn (MSG-04-11); MOD-02 không gọi ngược `product` (ma trận phụ thuộc chỉ cho `product` → `task`), nên dữ liệu "đã có minh chứng xác nhận" lấy từ thuộc tính do MOD-03 cập nhật vào nhiệm vụ qua `TaskApi` (CORE-02-12; cách hiện thực là suy luận).
- BR-04-12: dòng sinh nhiệm vụ đang `DANG_THUC_HIEN` không được đổi hạn qua điều chỉnh (MSG-04-12, hướng người dùng sang đề nghị gia hạn QT-06).
- Hiện thực `diffPlanVersions` tính thêm, sửa, bỏ, không đổi dựa trên `origin_item_id`, kèm trường thay đổi và lý do.
- Ngoại lệ người đứng đầu cho thêm một lần điều chỉnh vượt giới hạn (L1237): hợp đồng chưa có thao tác; task chỉ chuẩn bị điểm mở rộng trong domain (đếm số lần cho phép = tham số + số ngoại lệ đã cấp) và ghi đề nghị bổ sung hợp đồng vào báo cáo.
- Phiên bản điều chỉnh đi qua cùng luồng gửi duyệt và duyệt ký số của CORE-02-08, CORE-02-09; payload EVT-03 mang phiên bản cũ để CORE-02-11 tính chênh lệch.
- Gợi ý tách: (a) tạo điều chỉnh, giới hạn số lần, lý do từng dòng; (b) so sánh phiên bản và kiểm BR-04-11, BR-04-12.

## 4. Ngoài phạm vi

- Cập nhật, huỷ nhiệm vụ theo phiên bản mới (CORE-02-11); API cấp ngoại lệ điều chỉnh (cần bổ sung hợp đồng).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử: điều chỉnh lần thứ ba trong quý với giới hạn 2 trả 422 kèm MSG-04-07; bỏ dòng có minh chứng xác nhận trả 422 kèm MSG-04-11; đổi hạn dòng có nhiệm vụ đang thực hiện trả 422 kèm MSG-04-12; dòng sửa thiếu lý do bị chặn khi gửi.
- So sánh hai phiên bản 300 dòng với 10 thêm, 5 sửa, 3 bỏ trả đúng phân loại, p95 ≤ 300 ms (suy luận).
- Kiểm thử hợp đồng cho `createPlanAdjustment`, `diffPlanVersions` xanh; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
