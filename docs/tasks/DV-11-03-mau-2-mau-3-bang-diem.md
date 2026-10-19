# DV-11-03 · SVC-11/H5 Mẫu 2, Mẫu 3 và bảng chấm điểm

@@HDR@@

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | DV-11-01, DL-05 |
| Truy vết | QT-09 (SCR-0904 "Xuất Mẫu 3"), QT-11, QT-13 (sinh lại Mẫu 2, 3 sau EVT-22) → API `POST /api/v1/dossiers/forms`, `POST /api/v1/dossiers/render-jobs` → SVC-11 → bảng `docgen.template`, `docgen.render_job`, `docgen.render_output` → EVT-17 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực bộ sinh Mẫu 2 (tổng hợp của tập thể/đơn vị), Mẫu 3 (danh sách quyết định xếp loại) và bảng chấm điểm (vết tính từ `scoring.score_trace` và `appraisal.score_snapshot`) trên đường ống DV-11-01. Khi xong, người quyết định xuất được Mẫu 3 dạng nháp từ danh sách hồ sơ đang chọn (202) và saga sinh được bản chính thức của ba loại tài liệu cho gói hồ sơ.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-09 SCR-0904 (bảng chức năng "Xuất Mẫu 3"), QT-11 "Quy tắc nghiệp vụ" (L579, BR-11-02, BR-11-03, BR-11-04), QT-13 (L933, sinh lại và ký lại Mẫu 2, 3 khi mở lại kỳ, BR-13-08 không ghi đè biểu mẫu).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-11 document-service" (L1142) và chương Policy Pack mục "Tính lại tái lập được" (L2523).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema docgen (SVC-11)" (L3751), schema `scoring` (`score_trace`) và `appraisal` (`score_snapshot`).
- `contracts/openapi/modules/dossiers.yaml` `createDossierDraftForms`, `createDossierRenderJob`, `getDossierForm`.

## 3. Phạm vi công việc

- Viết `FormRenderer` cho `MAU_2` và `MAU_3`: Mẫu 3 nhận danh sách hồ sơ (tối đa theo giới hạn hợp đồng), sắp theo đơn vị và mức, có tổng hợp số lượng theo mức; Mẫu 2 tổng hợp theo đơn vị và kỳ.
- Viết `FormRenderer` cho bảng chấm điểm: dựng bảng từ vết tính (cấu phần, trọng số, hệ số, điểm) của đúng `policy_version` đã chốt; không tính lại điểm trong document-service, chỉ hiển thị dữ liệu snapshot.
- Hiện thực `createDossierDraftForms` (202, nhãn "Bản nháp" trên tệp, `form_instance` không gắn gói), kết quả là mã biểu mẫu ở `resultLocation` của job.
- Sinh lại sau mở kỳ hậu kiểm: tạo phiên bản tệp mới, không ghi đè `render_output` cũ (BR-13-08), tệp mới tham chiếu yêu cầu mở lại.
- Bảng dài nhiều trang: lặp tiêu đề bảng, ngắt trang đúng hàng; kiểm với 2.000 dòng để phát hiện chậm hoặc tràn bộ nhớ.
- Lập bộ dữ liệu vàng cho ba tài liệu như DV-11-02.

## 4. Ngoài phạm vi

- Mẫu 1A–1D (DV-11-02); gói hồ sơ và tem (DV-11-04); ký số (DV-10).
- Tính điểm và vết tính (SVC-07, DV-07).

## 5. Tệp được phép sửa

- `backend/services/document/**`
- `data/golden/docgen/mau-2-3-bang-diem/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/document -am verify` xanh, gồm kiểm thử tích hợp sinh đủ ba tài liệu từ bộ dữ liệu vàng, PDF/A-2b qua veraPDF.
- Kiểm thử chứng minh bản nháp có nhãn "Bản nháp" và không gắn gói; sinh lại sau mở kỳ tạo tệp mới, tệp cũ còn nguyên và `pdf_sha256` cũ không đổi.
- Mẫu 3 với 2.000 hồ sơ sinh xong và bộ nhớ worker không vượt giới hạn cấu hình (ghi số đo).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
