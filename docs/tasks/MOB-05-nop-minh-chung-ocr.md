# MOB-05 · QT-07/SCR-0702 Nộp minh chứng trên di động có OCR, tải theo khối

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh MOB-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – quy mô quốc gia (ứng dụng di động) |
| Luồng đề xuất | B10 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | MOB-02, DV-08-02 (bổ sung hiển nhiên: CORE-03-05 cho trích thông tin và gợi ý nhiệm vụ khớp) |
| Truy vết | QT-07 → SCR-0702 (MSG-07-06, MSG-07-07, MSG-07-08) → API `POST /api/v1/files/uploads` (`createFileUpload`), `PUT /api/v1/files/uploads/{uploadId}/parts/{n}` (`uploadFilePart`), `POST /api/v1/files/uploads/{uploadId}/complete` (`completeFileUpload`), `POST /api/v1/ai/evidence/extract` (`extractEvidenceFields`), `POST /api/v1/evidences` (`createEvidence`), `POST /api/v1/evidences/{id}/versions/{v}/submit` (`submitEvidenceVersion`, Idempotency-Key) → MOD-03, SVC-08, SVC-14 → bảng `evidence.object_version` (`sha256`), `product.evidence_version`, `product.deliverable`, `ai.suggestion_log` → EVT-07 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Người chủ trì nhiệm vụ nộp minh chứng từ văn bản giấy bằng ba chạm: chụp, xem kết quả AI đọc (số ký hiệu, ngày ban hành, trích yếu, nhiệm vụ khớp), xác nhận và nộp. Ảnh được chuyển thành PDF và băm SHA-256 trên thiết bị, tải lên theo khối có tiếp tục khi mất sóng, ảnh gốc không lưu vào thư viện ảnh của điện thoại.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/DI-DONG.md`.
- TL-02a `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`: QT-07 đặc tả SCR-0702 "Nộp minh chứng trên di động" (dòng 1992: bảng trường, chức năng, MSG-07-06..MSG-07-08), SCR-0701 (nộp trên web, quy tắc chung), "Quy tắc nghiệp vụ" QT-07; mục "Quy ước về nội dung do AI tạo" (viền tím, nhãn "AI").
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, mục "SVC-08 evidence-service" (tải luồng, SHA-256, Object Lock, quét mã độc).
- Hợp đồng `contracts/openapi/modules/products.yaml` (nhóm `/files/uploads*`, `/evidences*`), `contracts/openapi/modules/platform.yaml` (`extractEvidenceFields`).
- `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md`, `wiki/08-bao-mat-frontend.md`.

## 3. Phạm vi công việc

- Dựng tab "Minh chứng" và lối vào từ MOB-03: khung chụp có hướng dẫn căn văn bản, chọn từ thư viện ảnh; ảnh chụp không ghi vào thư viện ảnh của máy.
- Nén ảnh và ghép thành PDF trên thiết bị, giới hạn ≤ 20 MB, tính SHA-256 trên tệp PDF; ảnh tạm xoá ngay sau khi tạo PDF.
- Tải lên nhiều phần qua `createFileUpload`, `uploadFilePart`, `completeFileUpload`; lưu tiến trình tải để tiếp tục sau khi mất sóng hoặc ứng dụng bị đóng (qua MOB-02); máy chủ kiểm lại băm.
- Gọi `extractEvidenceFields`; hiện ba ô AI điền (số ký hiệu, ngày ban hành, trích yếu) có viền tím và nhãn "AI" cho tới khi người dùng xác nhận hoặc sửa (ô đã sửa mất viền tím); luôn hiện MSG-07-07; ảnh không đủ nét hiện MSG-07-06; độ khớp nhiệm vụ < 0,5 hiện MSG-07-08 và bắt chọn nhiệm vụ.
- Kiểm nhanh ở máy: ngày ban hành không sau hôm nay, trích yếu ≤ 500 ký tự và không trống; nhiệm vụ khớp phải là nhiệm vụ đang mở của người nộp.
- "Xác nhận và nộp" tạo hoặc dùng minh chứng, gửi `submitEvidenceVersion` với `Idempotency-Key`, chỉ bật khi kết quả quét mã độc sạch.

## 4. Ngoài phạm vi

- Nghiệm thu minh chứng (chỉ trên máy tính); OCR phía máy chủ (DV-14-05, CORE-03-05).

## 5. Tệp được phép sửa

- `mobile/lib/features/evidence/**`, `mobile/test/**`, `mobile/integration_test/**`, tệp ARB tiếng Việt trong `mobile/`

## 6. Tiêu chí hoàn thành

- `cd mobile && flutter analyze` sạch, `flutter test` xanh (băm SHA-256 khớp vector kiểm, giới hạn 20 MB, quy tắc viền tím, ngưỡng 0,5).
- Kiểm thử tích hợp với máy chủ giả lập: ngắt mạng giữa chừng khi tải phần thứ hai, bật lại thì tải tiếp từ phần còn thiếu; nộp hai lần cùng khoá chỉ tạo một phiên bản.
- Kiểm tra thủ công hoặc tự động rằng thư viện ảnh không có ảnh mới sau khi chụp; đếm chạm từ tab "Minh chứng" tới nộp = 3.
- Chạy trên trình giả lập Android và iOS (máy ảnh giả lập; ghi rõ nền tảng chưa chạy được); báo cáo theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
