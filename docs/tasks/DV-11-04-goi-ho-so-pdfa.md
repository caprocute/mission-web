# DV-11-04 · SVC-11/EVT-17 Gói hồ sơ PDF/A có tem, phiên bản mẫu theo Policy Pack

@@HDR@@

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | DV-11-02, DV-11-03 |
| Truy vết | QT-11 (BR-11-03, BR-11-09, bước 10) → API `POST /api/v1/dossiers/render-jobs` → SVC-11 → bảng `docgen.template`, `docgen.render_job`, `docgen.render_output` → EVT-17 → NFR-06, PERF-20 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn thiện sinh bản chính thức cho gói hồ sơ: mỗi tệp PDF/A-2b mang tem phiên bản Policy Pack và `template_version`, mẫu chọn đúng phiên bản của kỳ (kỳ cũ sinh đúng mẫu cũ), tệp xong phát EVT-17 cho MOD-06 theo dõi gói, và đường ống đạt thông lượng cuối kỳ. Khi xong, 100.000 phiếu của một cell sinh được trong khung 4 giờ của NFR-06 (cùng với ký lô), khoảng 7 phiếu/giây.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-11 "Quy tắc nghiệp vụ" (L579, BR-11-03 tem phiên bản, BR-11-09), SCR-1102 (L675, gói hồ sơ số và ký số).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-11 document-service" (L1142–1154, ước tính 300–600 ms/phiếu, 4–6 pod Gotenberg × 2 luồng).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Danh mục kịch bản thử tải PERF" (L1743, PERF-20).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema docgen" (L3751), "Tổ chức bucket" (L4509, bucket `dossier` compliance 20 năm).
- `contracts/openapi/modules/dossiers.yaml` `createDossierRenderJob` (lược đồ theo gói), trạng thái gói `DANG_SINH`.
- Bản trích `extract/tl02b-may-chu.md` QT-11 (BE-Q11-06, BE-Q11-07).

## 3. Phạm vi công việc

- Thêm tem vào mọi tệp chính thức (chân trang hoặc siêu dữ liệu XMP bằng OpenPDF): mã Policy Pack và phiên bản, `form_code`, `template_version`, mã gói, `pdf_sha256` của dữ liệu đầu vào (BR-11-03).
- Chọn mẫu theo phiên bản gắn với kỳ qua `policy_binding` (đọc từ dữ liệu trong lệnh sinh hoặc bản sao cấu hình), không theo phiên bản mới nhất; kiểm thử kỳ cũ sinh đúng mẫu cũ.
- Xử lý yêu cầu theo gói: tách thành nhiều `render_job` theo hồ sơ và loại mẫu, mỗi tệp xong phát một EVT-17 có mã gói, mã biểu mẫu, `pdf_sha256`; tệp lỗi thử lại có giới hạn, quá lần thử thì `LOI` để MOD-06 gọi sinh lại chỉ tệp lỗi.
- Ghi tệp vào bucket `dossier` có Object Lock; `render_output.pdfa_validated` bắt buộc đúng trước khi phát EVT-17.
- Tối ưu thông lượng: pool kết nối tới Gotenberg, song song có giới hạn theo số pod × luồng, metric hàng đợi cho KEDA; đo phiếu/giây với 4 và 6 pod Gotenberg.

## 4. Ngoài phạm vi

- Trạng thái gói `dossier_package`, manifest SHA-256 và ký gói (CORE-06-06, CORE-06-07); ký lô (DV-10-06).
- Kịch bản PERF-20 đầy đủ trên CELL-PERF (QA-11).

## 5. Tệp được phép sửa

- `backend/services/document/**`
- `data/golden/docgen/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/document -am verify` xanh, gồm kiểm thử tích hợp: một gói 20 hồ sơ sinh đủ tệp, mỗi tệp có tem đọc được từ siêu dữ liệu, đủ số EVT-17, tệp lỗi giả lập được sinh lại riêng.
- Kiểm thử chứng minh kỳ cũ dùng `template_version` cũ dù đã có phiên bản mới.
- Đo thông lượng cục bộ (phiếu/giây theo số luồng Gotenberg) và ngoại suy cho 100.000 phiếu; ghi vào báo cáo cùng giả định so với mốc ≈ 7 phiếu/giây.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
