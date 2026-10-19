# DV-11-02 · SVC-11/H5 Mẫu 1A, 1B, 1C, 1D bằng docx4j

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-11-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | DV-11-01, DL-05 |
| Truy vết | QT-09 (SCR-0901 xem trước Mẫu 1B), QT-11 (BR-11-02, BR-11-04) → API `POST /api/v1/dossiers/forms/preview`, `POST /api/v1/dossiers/render-jobs` → SVC-11 → bảng `docgen.template`, `docgen.render_job`, `docgen.render_output` → EVT-17 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực bốn bộ sinh Mẫu 1A, 1B, 1C, 1D (bản tự kiểm điểm theo nhóm đối tượng) trên đường ống của DV-11-01: ánh xạ dữ liệu snapshot đã quyết định vào mẫu DOCX của DL-05, vùng số liệu khoá, nhãn tiêu chí chung theo mẫu của tenant. Khi xong, dự thảo Mẫu 1A–1D sinh được trong ≤ 5 phút cho một hồ sơ trong luồng thường và xem trước Mẫu 1B trả về trong khoảng 2 giây.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-11 (L510), "Quy tắc nghiệp vụ" (L579: BR-11-02 biểu mẫu sinh từ snapshot và bản ghi `QUYET_DINH`, BR-11-04 ánh xạ nhãn tiêu chí chung theo mẫu trong `docgen.template`); SCR-0901 (QT-09, xem trước Mẫu 1B).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-11 document-service" (L1142).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` từ điển `docgen.template` (L3763, `subject_group_codes[]`) và `dossier.form_instance` (schema dossier).
- `contracts/openapi/modules/dossiers.yaml` `previewDossierForm`, `createDossierRenderJob`, lược đồ dữ liệu biểu mẫu.
- Tệp mẫu DOCX và bảng trường do DL-05 bàn giao trong `data/` (đọc README của DL-05).

## 3. Phạm vi công việc

- Viết bốn `FormRenderer` cho `MAU_1A`, `MAU_1B`, `MAU_1C`, `MAU_1D`, chọn mẫu theo `subject_group_codes` của `docgen.template` và phiên bản hiệu lực của kỳ (không chọn theo ngày hiện tại).
- Ánh xạ dữ liệu: thông tin cán bộ (C2 chỉ giải mã trong bộ nhớ worker, không ghi nhật ký), kết quả tiêu chí chung, nhiệm vụ và điểm từ snapshot, mức đề xuất/quyết định; vùng số liệu đặt khoá chỉnh sửa trong DOCX (vùng bảo vệ) theo BR-11-02.
- Ánh xạ nhãn tiêu chí chung theo khai báo trong mẫu (BR-11-04), không viết cứng nhãn trong mã; định dạng ngày, số theo tiếng Việt.
- Hiện thực `previewDossierForm` cho Mẫu 1B: sinh đồng bộ từ dữ liệu hiện tại, không lưu `render_output`, có giới hạn tốc độ theo người dùng; nếu quá thời gian thì trả lỗi RFC 9457 để giao diện thử lại (suy luận về cách xử lý quá thời gian).
- Lập bộ dữ liệu vàng nhỏ cho mỗi mẫu (đầu vào JSON + PDF kỳ vọng dạng văn bản trích) để kiểm hồi quy khi đổi mẫu.

## 4. Ngoài phạm vi

- Nội dung pháp lý của mẫu DOCX (DL-05); Mẫu 2, 3, bảng chấm điểm (DV-11-03); gói và tem (DV-11-04).
- Trường hợp phiếu có nhận xét C3 (máy chủ không sinh được PDF, theo TL-01 L2372): chỉ trả lỗi nghiệp vụ rõ ràng, luồng phía thiết bị thuộc task web/di động.

## 5. Tệp được phép sửa

- `backend/services/document/**`
- `data/golden/docgen/mau-1a-1d/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/document -am verify` xanh, gồm kiểm thử tích hợp Gotenberg sinh đủ bốn mẫu từ bộ dữ liệu vàng, PDF/A-2b qua veraPDF, văn bản trích khớp kỳ vọng.
- Kiểm thử chứng minh đổi phiên bản mẫu trong `docgen.template` cho kỳ mới không làm đổi PDF của kỳ cũ; vùng số liệu không sửa được khi mở DOCX bằng trình soạn thảo (kiểm thuộc tính bảo vệ).
- Xem trước Mẫu 1B ≤ 2 giây ở máy thử (ghi số đo); không có dữ liệu C2 trong nhật ký (kiểm bằng kiểm thử bắt log).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
