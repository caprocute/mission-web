# DL-05 · H5/SVC-11 Mẫu DOCX 1A–1D, 2, 3 và bảng chấm điểm theo văn bản pháp lý

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – lõi nghiệp vụ trên nền tảng mới |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | – (DV-11-02, DV-11-03 phụ thuộc task này) |
| Truy vết | H5; QT-09, QT-11 (BR-11-02, BR-11-03, BR-11-04) → API `POST /api/v1/dossiers/forms/preview` (`previewDossierForm`), `POST /api/v1/dossiers/forms` (`createDossierDraftForms`), `POST /api/v1/dossiers/render-jobs` (`createDossierRenderJob`) → SVC-11, MOD-06 → bảng `docgen.template`, `dossier.form_instance` → EVT-17 |
| Trạng thái | Bản giả lập xong 20/10/2026: bảy mẫu `GIA_LAP` có kê khai và tệp DOCX sinh tự động, công cụ kiểm tra và kiểm thử; thay bằng bản chính thức khi người dùng cung cấp mẫu |

## 1. Mục tiêu

Có bộ mẫu DOCX đúng văn bản pháp lý cho Mẫu 1A, 1B, 1C, 1D, Mẫu 2, Mẫu 3 và bảng chấm điểm, mỗi mẫu có vùng trộn dữ liệu đặt tên ổn định, tệp kê khai trường ánh xạ tới nguồn dữ liệu và phiên bản gắn với Policy Pack. document-service (docx4j, Gotenberg) dùng bộ mẫu này để sinh PDF/A-2b ở QT-09, QT-11 (tiêu chí H5); kỳ cũ phải sinh đúng mẫu cũ nên mẫu nằm trong thư mục `templates/` của gói.

Theo quyết định ngày 20/10/2026 (`docs/KE-HOACH-TONG-THE-HE-THONG.md` mục 1a), bộ mẫu trong task này là mẫu giả lập dựng theo đặc tả SCR-0903, SCR-1102 và các mẫu của QĐ 366-QĐ/TW, NĐ 335/2025, đánh dấu rõ là giả lập trong kê khai và `metadata` của Policy Pack. Mẫu có phiên bản theo Policy Pack nên thay được: khi có bản mẫu chính thức, quản trị viên đưa mẫu mới vào phiên bản Policy Pack mới và phát hành theo QT-15, không sửa mã và không phát hành phần mềm.

## 2. Đầu vào bắt buộc đọc

- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, mục "SVC-11 document-service" (dòng 1142) và chương "Policy Pack…" mục "Cấu trúc gói" (thư mục `templates/`).
- TL-02b `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md`: QT-11 "Quy tắc nghiệp vụ" (BR-11-02 vùng số liệu khoá, BR-11-03 tem phiên bản, BR-11-04 ánh xạ nhãn tiêu chí chung) và đặc tả SCR-1102; QT-09 "Ngoại lệ và xử lý" (trường hợp không tự đánh giá ghi trên Mẫu 3), đặc tả SCR-0903.
- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`, mục "Schema docgen" và bảng `appraisal.score_snapshot`, `appraisal.rating_proposal` (`level_kind`), `appraisal.rating_explanation`, `dossier.form_instance`.
- Hợp đồng `contracts/openapi/modules/dossiers.yaml` (lược đồ yêu cầu xem trước và sinh biểu mẫu).
- Bản mẫu chính thức (DOCX hoặc PDF) kèm số hiệu văn bản ban hành và xác nhận phông chữ được phép nhúng: chưa có, không chặn task; khi có thì dùng để soạn phiên bản mẫu thay thế.

## 3. Phạm vi công việc

- Dựng tệp DOCX giả lập cho Mẫu 1A, 1B, 1C, 1D, Mẫu 2, Mẫu 3 và bảng chấm điểm, bám đặc tả SCR-0903, SCR-1102 và bố cục các mẫu trong văn bản pháp lý mà TL dẫn, dùng phông chữ mở được phép nhúng; vùng dữ liệu dùng content control có tag ổn định dạng `snake_case` để docx4j trộn.
- Đánh dấu giả lập: mỗi `manifest.json` có `gia_lap: true` và ghi chú nguồn bố cục (tên trường là suy luận), chân trang mẫu có dòng "Mẫu thử nghiệm" bên cạnh tem phiên bản.
- Viết tệp kê khai `manifest.json` cho mỗi mẫu: tên vùng, kiểu, nguồn (`appraisal.score_snapshot`, bốn bản ghi `rating_proposal` theo `level_kind`, `rating_explanation`, `appraisal.self_assessment`, `org_replica.*`), vùng nào khoá số liệu theo BR-11-02.
- Đặt tem phiên bản Policy Pack và `template_version` ở chân trang mỗi mẫu (BR-11-03); ánh xạ nhãn tiêu chí chung theo từng mẫu (BR-11-04) bằng bảng tra trong kê khai.
- Thể hiện các trường hợp đặc biệt: Mẫu 3 ghi "không tự đánh giá" khi cán bộ quá hạn; Mẫu 2 ghi chú trường hợp §4 không xếp loại; câu giải thích hạ mức liền kề in nguyên văn từ `rating_explanation`.
- Đặt mẫu vào `templates/` của gói `khoi-dang/quy-dinh-moi` và gói luồng Nhà nước (Mẫu dùng chung thì tham chiếu, không chép), cập nhật `CHANGELOG.md`.
- Viết kiểm tra tĩnh (Python 3.12, chạy `python -I`): mỗi DOCX hợp lệ OOXML, tập tag trong tệp trùng khớp tập trường trong kê khai, không còn tag thừa hoặc thiếu.
- Không viết cứng bố cục hay tên vùng của mẫu trong mã document-service; mọi thay đổi mẫu đi qua phiên bản `template_version` trong Policy Pack.

## 4. Ngoài phạm vi

- Mã trộn mẫu, chuyển PDF/A, ký số, gói hồ sơ (DV-11-01..DV-11-04, nhóm DV-10).
- Nội dung công thức và xếp loại (DL-01, DL-02).

## 5. Tệp được phép sửa

- `data/policy-pack/khoi-dang/quy-dinh-moi/templates/**`, `data/policy-pack/<khối-nhà-nước>/<tên>/templates/**`
- `data/seed/docgen/**` (kê khai dùng chung, tập lệnh kiểm tra)
- `CHANGELOG.md` của hai gói

## 6. Tiêu chí hoàn thành

- `python -I data/seed/docgen/kiem_mau.py data/policy-pack` báo 0 lỗi cho cả bảy mẫu.
- Với DV-11-01 đã gộp: sinh thử mỗi mẫu bằng dữ liệu giả lập qua Gotenberg trong Testcontainers, tệp ra đạt PDF/A-2b (kiểm bằng veraPDF hoặc công cụ kiểm PDF/A mà DV-11-01 đã chọn) và có tem phiên bản.
- Với DV-11-01 đã gộp, ca chứng minh thay mẫu: đưa một phiên bản mẫu mới (ví dụ đổi bố cục Mẫu 2) vào phiên bản Policy Pack mới; kỳ áp dụng phiên bản mới sinh tệp theo mẫu mới, kỳ cũ vẫn sinh mẫu cũ, cùng bản build, không đổi mã.
- Báo cáo có bảng liệt kê từng mẫu đang là giả lập và căn cứ đã dùng để dựng bố cục.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
