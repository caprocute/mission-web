# DV-13-08 · SVC-13/BR-03-04 HRM và số liệu nguồn cho KR

@@HDR@@

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-13-01 |
| Truy vết | QT-03 (BR-03-04, MSG-03-03) → API `GET /api/v1/okr/key-results/{id}/source-value` (MOD-01 gọi SVC-13) → SVC-13 → bảng `integ.connector`, `integ.sync_cursor`, `integ.inbox_message`, `integ.mapping_xref` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Kết nối nền tảng với HRM, ERP, HIS, hệ quản lý trường học theo SCIM 2.0, REST hoặc tệp lô SFTP để nhận nhân sự và chỉ số chuyên môn, và cung cấp dịch vụ "lấy số liệu nguồn" cho KR trong ≤ 3 giây. Khi xong, chủ sở hữu KR gắn nguồn được thấy giá trị gợi ý và thời điểm lấy, ghi đè phải có lý do (BR-03-04), nguồn lỗi thì nhập tay.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Các bộ kết nối" (L1409–1426, dòng HRM, ERP, HIS, quản lý trường học: SCIM 2.0, REST, tệp lô SFTP).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-03 (L982, BR-03-04, MSG-03-03, mục tích hợp lấy số liệu nguồn qua SVC-13).
- `contracts/openapi/modules/okr.yaml` `getOkrKeyResultSourceValue` (≤ 3 giây, 502 `OKR-502-SOURCE-UNAVAILABLE`) và lược đồ check-in có `sourceValue`.
- Bản trích `extract/tl02a-may-chu.md` QT-03 (BE-03-4).

## 3. Phạm vi công việc

- Viết adapter tệp lô SFTP (`camel-sftp`): nhận tệp CSV/Excel theo lịch, kiểm cấu trúc, lưu nguyên văn, chuyển đổi từng dòng; tệp lỗi vào `dead_letter` kèm số dòng lỗi.
- Viết adapter REST chung cho HRM/HIS có cấu hình ánh xạ trường theo `connector` (không viết cứng cho một hãng), kéo theo `sync_cursor`.
- Viết use case "lấy số liệu nguồn" cho MOD-01: nhận mã nguồn đã cấu hình cho KR, gọi hệ nguồn với thời hạn 3 giây và Resilience4j, trả giá trị và thời điểm lấy; lỗi hoặc quá hạn trả lỗi để MOD-01 trả 502 theo hợp đồng.
- Lưu ánh xạ chỉ số ngoài ↔ KR nội bộ trong `mapping_xref` (thời gian hiệu lực), không đặt mã ngoài trong schema `okr`.
- Chuyển nhân sự từ HRM cho org-service theo cùng cơ chế DV-13-06 khi tenant dùng HRM làm nguồn.

## 4. Ngoài phạm vi

- API `getOkrKeyResultSourceValue` ở MOD-01 và ghi check-in có lý do ghi đè (CORE-01 tương ứng).
- SCIM 2.0 đẩy từ HRM và IdP khách hàng (DV-04-06).

## 5. Tệp được phép sửa

- `backend/services/integration-gateway/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/integration-gateway -am verify` xanh, gồm kiểm thử SFTP với máy chủ SFTP trong Testcontainers (tệp đúng, tệp sai cấu trúc, tệp trùng) và kiểm thử REST giả lập.
- Use case số liệu nguồn trả trong ≤ 3 giây khi nguồn chậm 10 giây (cắt ở 3 giây) và trả lỗi đúng loại để MOD-01 ánh xạ 502.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
