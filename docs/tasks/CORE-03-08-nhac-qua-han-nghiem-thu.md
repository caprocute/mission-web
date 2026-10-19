# CORE-03-08 · BR-07-12 Nhắc quá hạn nghiệm thu và nghiệm thu thay theo uỷ quyền

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-03-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B3 |
| Cỡ việc | S |
| Phụ thuộc | CORE-03-07 (bổ sung đề xuất: CORE-08-04 cho hàm ngày làm việc, CORE-08-16 cho dữ liệu uỷ quyền) |
| Truy vết | QT-07 → SCR-0703 → tác vụ nền MOD-03 → `product.evidence_version` (`DA_NOP`, `DANG_NGHIEM_THU`), `product.acceptance` → EVT-21 (MSG-07-14) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Minh chứng chờ nghiệm thu quá 5 ngày làm việc (tham số Policy Pack) được nhắc tới người nghiệm thu và cấp trên (BR-07-12, MSG-07-14); khi người nghiệm thu vắng quá hạn, người được uỷ quyền nghiệm thu thay được, quyết định ghi rõ là nghiệm thu thay. Việc này tránh minh chứng tồn đọng làm trễ khoá kỳ.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`: mục "Lịch nghiệp vụ của một quý" (dòng 178–186), QT-07 BR-07-12 (bảng "Quy tắc nghiệp vụ", dòng 1917), "Ngoại lệ và xử lý" (dòng 1942, nghiệm thu thay), MSG-07-14 (dòng 2079).
- TL-03 bảng `product.acceptance` (dòng 2581) để chọn cách ghi người nghiệm thu thay.
- `wiki/15-phan-quyen.md` (uỷ quyền); bản trích `tl02a §QT-07 BE-07-11`.

## 3. Phạm vi công việc

- Viết tác vụ định kỳ bằng cơ chế lập lịch của thư viện nền (không thêm thư viện mới), chạy một bản trên cụm, quét phiên bản `DA_NOP`/`DANG_NGHIEM_THU` quá ngưỡng ngày làm việc lấy từ `PolicyQueryApi`, tính bằng thư viện ngày làm việc dùng chung theo `config.holiday_calendar`.
- Gom theo người nghiệm thu, phát một EVT-21 mỗi người mỗi ngày với số lượng và số ngày (MSG-07-14); gửi bản tóm tắt tới cấp trên theo cây tổ chức trong `org_replica`; không phát trùng khi tác vụ chạy lại.
- Cho phép người có uỷ quyền nghiệm thu hợp lệ (đọc từ cổng uỷ quyền của MOD-08, CORE-08-16) gọi các lệnh của CORE-03-07; kiểm OPA vẫn áp tách vai trò với người thay.
- Ghi nhận nghiệm thu thay: lưu người thay và người được thay vào sổ cái và trường mô tả của `acceptance` (cách lưu chọn theo TL-03; nếu cần cột mới thì dừng, ghi câu hỏi vào `wiki/14`, không tự thêm cột).
- Kiểm thử với lịch có ngày nghỉ và làm bù.

## 4. Ngoài phạm vi

- Mô hình uỷ quyền và API quản trị (CORE-08-16); kênh gửi thông báo (DV-12-xx).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/product/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/product/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md` (nếu phát sinh câu hỏi về cột lưu người thay)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh.
- Kiểm thử: minh chứng chờ đúng 5 ngày làm việc chưa nhắc, ngày thứ 6 nhắc; chạy tác vụ hai lần trong ngày chỉ tạo một EVT-21; người không có uỷ quyền nghiệm thu thay bị 403.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
