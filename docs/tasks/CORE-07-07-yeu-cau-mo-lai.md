# CORE-07-07 · QT-13/SCR-1301 Yêu cầu mở lại kỳ và mô phỏng ảnh hưởng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-07-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-07-01, DV-07-08 |
| Truy vết | QT-13 bước 4 → SCR-1301 → API `GET/POST /api/v1/oversight/reopen-requests`, `GET .../reopen-requests/{id}`, `POST .../reopen-requests/{id}/simulate`, `POST .../reopen-requests/{id}/cancel` → MOD-07 → `oversight.reopen_request` (`CHO_DUYET_1`, `DA_DONG`) → SVC-07 (mô phỏng) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cơ quan tổ chức (AC-07) hoặc cơ quan kiểm tra (AC-10) lập yêu cầu mở lại một kỳ đã khoá trong phạm vi hẹp (hồ sơ, đơn vị, trường dữ liệu), có thời hạn, lý do và căn cứ, xem trước ảnh hưởng tới điểm và mức bằng đúng phiên bản Policy Pack của kỳ trước khi gửi duyệt. Đây là con đường duy nhất để thi hành kết luận khiếu nại hay hậu kiểm trên dữ liệu đã chốt (BR-12-07, BR-13-01).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-13: "Mô tả các bước" (dòng 962, bước 4 và đoạn về "mở trong phạm vi"), "Trạng thái đối tượng" (dòng 984), "Quy tắc nghiệp vụ" (dòng 999: BR-13-05, BR-13-06), "Ngoại lệ và xử lý" (dòng 1016: lý do 50–4.000 ký tự, mã `MLK-yyyy-nnn`), "SCR-1301. Yêu cầu mở lại kỳ" (dòng 1028).
- TL-03 bảng `oversight.reopen_request` (dòng 3144).
- `contracts/openapi/modules/oversight.yaml`: `listReopenRequests`, `createReopenRequest` (`OVERSIGHT-409-PERIOD-NOT-LOCKED`), `getReopenRequest`, `simulateReopenRequest`, `cancelReopenRequest`.
- Bản trích `tl02b §QT-13 BE-Q13-01`.

## 3. Phạm vi công việc

- `createReopenRequest` (`Idempotency-Key`): kỳ phải `DA_KHOA` (đọc `PeriodApi`), nếu không trả 409 `OVERSIGHT-409-PERIOD-NOT-LOCKED`; `scope` `DON_VI`/`HO_SO` cần `scope_ids` khác rỗng, `TOAN_KY` chỉ khi có quyết định hậu kiểm toàn diện (BR-13-05); thời hạn mở 1–10 ngày làm việc; lý do 50–4.000 ký tự; sinh `code` `MLK-yyyy-nnn` duy nhất; lưu `scope_json` chi tiết (bảng, trường được phép sửa); trạng thái `CHO_DUYET_1`; có thể gắn khiếu nại hoặc cảnh báo nguồn.
- Cung cấp cổng ứng dụng nội bộ để CORE-07-04 (kết luận khiếu nại) và CORE-07-11 (kết luận vi phạm) tạo yêu cầu với cùng kiểm tra.
- `simulateReopenRequest`: gọi API mô phỏng của SVC-07 với phiên bản Policy Pack của kỳ (BR-13-06), trả điểm và mức trước, sau cho từng hồ sơ trong phạm vi; không ghi gì.
- `cancelReopenRequest`: chỉ người tạo, trước khi đủ hai phê duyệt, bắt buộc lý do; chuyển `DA_DONG`.
- `listReopenRequests`, `getReopenRequest`: phân trang con trỏ, OPA phạm vi.
- Ghi sổ cái qua outbox cho tạo và huỷ (BR-13-07).

## 4. Ngoài phạm vi

- Chỉ định người duyệt và phê duyệt (CORE-07-08); trạng thái kỳ `MO_LAI_HAU_KIEM` (CORE-08-06).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/oversight/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/oversight/**`
- `deploy/policies/` (chỉ quy tắc Rego cho người lập yêu cầu mở lại)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; mã lỗi khớp `oversight.yaml`.
- Kiểm thử: kỳ `MO` bị 409; `TOAN_KY` không có quyết định hậu kiểm toàn diện bị 422; thời hạn 11 ngày làm việc bị 422; mô phỏng không tạo dòng nào trong `appraisal.score_snapshot` hay `scoring.*`.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
