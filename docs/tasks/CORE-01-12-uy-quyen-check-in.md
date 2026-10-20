# CORE-01-12 · QT-03 Uỷ quyền check-in thay và chuyển giá trị sang phiên bản mục tiêu mới

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-01-12` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | S |
| Phụ thuộc | CORE-01-08, CORE-01-07 |
| Truy vết | QT-03 → SCR-0301 → API `POST /api/v1/okr/key-results/{id}/checkins` (người check-in thay), lược đồ `OkrKeyResult.checkinDelegate`; thao tác giao quyền check-in thay chưa có trong `okr.yaml` → MOD-01 → bảng `okr.key_result`, `okr.kr_checkin` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Xử lý hai ngoại lệ của QT-03: khi chủ sở hữu KR vắng, họ hoặc quản lý giao tạm quyền check-in cho người khác và mọi check-in đó được ghi là "check-in thay"; khi phiên bản điều chỉnh của mục tiêu được duyệt, giá trị hiện tại được chuyển sang KR cùng mã của phiên bản mới để tiến độ không bị đặt lại.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-03 "Ngoại lệ và xử lý" (chủ sở hữu vắng L1063, phiên bản mục tiêu mới L1065), SCR-0301 (người được check-in, L1076).
- `contracts/openapi/modules/okr.yaml`: `createOkrCheckin`, `listOkrKeyResults` (`owner=me` gồm KR được giao check-in thay), lược đồ `OkrKeyResult.checkinDelegate` (đánh dấu suy luận).
- `wiki/15-phan-quyen.md` (uỷ quyền), `wiki/15a-ma-tran-phan-quyen.md` dòng SCR-0301.

## 3. Phạm vi công việc

- Điểm lệch cần lưu ý: hợp đồng có trường `checkinDelegate` nhưng không có thao tác giao hoặc thu hồi quyền check-in thay, TL-03 cũng không có cột tương ứng. Task đề xuất một thao tác thêm vào `okr.yaml` theo quy ước `POST /okr/key-results/{id}/<động-từ>` và một migration bổ sung cột hoặc bảng nhỏ trong schema `okr` (đánh dấu "bổ sung", chỉ thêm), chạy `openapi-diff`, chờ người phụ trách duyệt trước khi viết mã.
- Hiện thực giao quyền check-in thay có thời hạn (từ ngày, đến ngày), chỉ chủ sở hữu KR hoặc quản lý trực tiếp được giao, người nhận thuộc cùng đơn vị (suy luận); thu hồi được bất cứ lúc nào; ghi lịch sử.
- Cắm vào port kiểm người được check-in của CORE-01-08: người được giao còn hiệu lực được gửi check-in; check-in đó lưu người thực hiện thực tế và cờ "check-in thay" để hiển thị và đối chiếu (cách lưu theo trường hợp đồng hoặc cột bổ sung, ghi rõ).
- Lắng nghe sự kiện ứng dụng "phiên bản mục tiêu mới được duyệt" do CORE-01-07 phát: với mỗi KR cùng mã ở phiên bản mới, đặt `current_value` bằng giá trị hiện tại của KR phiên bản cũ và tính lại tiến độ; KR mới không có KR cùng mã thì giữ giá trị đầu; không sao chép check-in.

## 4. Ngoài phạm vi

- Uỷ quyền duyệt OKR (thuộc `wf_binding` và DV-06-03).
- Giao diện giao quyền ở web (task WEB).

## 5. Tệp được phép sửa

- `contracts/openapi/modules/okr.yaml` (chỉ thêm thao tác, sau khi được duyệt)
- `backend/mission-core/src/main/resources/db/migration/okr/**` (chỉ migration mới)
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/okr/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/okr/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd contracts && npm run lint` xanh và `openapi-diff` không báo phá tương thích (nếu có sửa hợp đồng).
- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Kiểm thử: người được giao check-in trong thời hạn thành công và bản ghi có cờ check-in thay; hết hạn hoặc đã thu hồi nhận 403; người không được giao nhận 403.
- Kiểm thử: phiên bản 2 được duyệt thì KR `KR 1.2` của phiên bản 2 có `current_value` bằng của phiên bản 1; KR mới thêm giữ giá trị đầu.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
