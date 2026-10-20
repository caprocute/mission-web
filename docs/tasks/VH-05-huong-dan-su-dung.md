# VH-05 · YC-PT-11 Hướng dẫn sử dụng theo vai trò AC-01..AC-10

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh VH-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (trước buổi kiểm thử chấp nhận QA-16; cập nhật ở đợt 3 cho di động và AI) |
| Luồng đề xuất | B11 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | QA-07 |
| Truy vết | YC-PT-11, NFR-11 → QT-00..QT-18 → SCR theo wiki 05a, 05b → AC-01..AC-10 theo ma trận `wiki/15a` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Viết bộ hướng dẫn sử dụng cho mười vai trò người dùng, mỗi vai trò một tài liệu theo việc thường ngày của vai trò đó, có ảnh màn hình chụp tự động từ hành trình đầu cuối để tự cập nhật khi giao diện đổi. Khi xong, mỗi vai trò có tài liệu đọc được trên web và in được, có phần riêng cho chế độ Đảng viên cao tuổi, và được dùng trong buổi kiểm thử chấp nhận QA-16.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 10 (AC-01..AC-12), mục 11 (QT và SCR).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`, `TL-02b-quy-trinh.md` (thuật ngữ nghiệp vụ, thông điệp MSG hiển thị cho người dùng).
- `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md`, `wiki/05b-quy-trinh-va-man-hinh-qt09-18.md`, `wiki/15a-ma-tran-phan-quyen.md`, `wiki/09-hieu-nang-va-trai-nghiem.md` (chế độ người cao tuổi), `wiki/13-thuat-ngu.md`.
- `frontend/e2e/hanh-trinh/danh-muc.json` và mã hành trình QA-04..QA-07.

## 3. Phạm vi công việc

- Mười tài liệu `docs/huong-dan-su-dung/ac-01-quan-tri-nen-tang.md` .. `ac-10-kiem-tra-giam-sat.md` (vị trí suy luận); mỗi tài liệu gồm: vai trò làm gì trong hệ thống, việc thường ngày theo trình tự, từng bước có ảnh, câu hỏi thường gặp, nơi liên hệ hỗ trợ.
- Chỉ mô tả chức năng mà ma trận `wiki/15a` cho phép vai trò đó; không liệt kê chức năng vai trò không thấy.
- Văn phong nghiêm túc, đơn giản, tiếng Việt có dấu, đúng thuật ngữ TL-02; không in mã SCR, QT, BR, MSG, tên API, mã trạng thái thô trong phần người dùng đọc (mã chỉ đặt trong chú thích HTML ẩn để truy vết).
- Script chụp ảnh `frontend/e2e/huong-dan/chup-anh.spec.ts`: chạy lại các bước hành trình với dữ liệu thử, chụp ảnh ở chế độ thường và chế độ người cao tuổi, lưu vào `docs/huong-dan-su-dung/anh/`; không chụp dữ liệu cá nhân thật.
- Phần "Chế độ Đảng viên cao tuổi" cho AC-06: bật chế độ, các thao tác rút gọn ≤ 3 chạm.
- Bản in: script dựng PDF từ Markdown (Pandoc có sẵn trong môi trường tài liệu, suy luận) cho từng vai trò.
- Gợi ý tách: (a) AC-04..AC-06 (đông người dùng nhất); (b) AC-07..AC-10; (c) AC-01..AC-03 và script chụp ảnh.

## 4. Ngoài phạm vi

- Tài liệu quản trị và cài đặt hệ thống (VH-06); hướng dẫn trên ứng dụng di động (bộ MOB); video đào tạo.

## 5. Tệp được phép sửa

- `docs/huong-dan-su-dung/**`, `frontend/e2e/huong-dan/**`.

## 6. Tiêu chí hoàn thành

- Đủ mười tài liệu; `cd frontend && npx playwright test e2e/huong-dan` chạy xanh trên server dev và sinh ảnh cho mọi bước.
- Script kiểm `docs/huong-dan-su-dung/kiem-tra.py` xác nhận không có chuỗi dạng mã kỹ thuật (`SCR-`, `QT-`, `MSG-`, `/api/`, mã trạng thái viết hoa có gạch dưới) trong văn bản hiển thị.
- Một người không thuộc nhóm phát triển đọc tài liệu AC-06 và làm được việc cập nhật tiến độ trên staging (ghi nhận trong báo cáo).

## 7. Báo cáo

Theo AGENTS.md mục 6.
