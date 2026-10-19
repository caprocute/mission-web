# MOB-06 · QT-09/SCR-0901 Bộ dựng biểu mẫu từ JSON Schema và CEL trên Flutter, phiếu tự đánh giá

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh MOB-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – quy mô quốc gia (ứng dụng di động) |
| Luồng đề xuất | B10 |
| Cỡ việc | L (> 1 ngày, nên tách: xem cuối mục 3) |
| Phụ thuộc | MOB-02, CORE-08-13 (bổ sung hiển nhiên: nhóm CORE-04 có API phiếu tự đánh giá) |
| Truy vết | QT-09, QT-18 → SCR-0901 (BR-09-05, BR-18-04, BR-18-05, BR-18-11) → API `GET /api/v1/ui/forms/{key}/current` (`getCurrentUiForm`), `GET /api/v1/appraisals/profiles/mine` (`listMyAppraisalProfiles`), `GET /api/v1/appraisals/profiles/{id}` (`getAppraisalProfile`), `PUT /api/v1/appraisals/profiles/{id}/self-assessment` (`saveAppraisalSelfAssessment`, If-Match), `POST /api/v1/appraisals/profiles/{id}/submit` (`submitAppraisalSelfAssessment`, 202) → MOD-08, MOD-04 → bảng `config.form_schema`, `appraisal.self_assessment`, `appraisal.general_criteria_result` → EVT-11, EVT-18 (sau ký, MOB-07) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Ứng dụng di động dựng được biểu mẫu động từ cùng JSON Schema mà web dùng (`config.form_schema`), áp điều kiện hiển thị và kiểm tra CEL, và dùng bộ dựng này cho phiếu tự đánh giá quý, năm: lưu nháp từng bước (ngoại tuyến được), lưu lên máy chủ với `If-Match`, rồi nộp để chuyển sang ký số. Máy chủ luôn kiểm lại toàn bộ schema và `x-cel` (BR-18-04); kiểm ở máy chỉ để phản hồi nhanh.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/DI-DONG.md`.
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, mục "SVC-18 mobile-app" (biểu mẫu dùng chung schema; CEL bằng thư viện Dart hoặc gọi API kiểm tra máy chủ khi trực tuyến, hoãn khi ngoại tuyến) và chương giao diện theo tenant (lớp biểu mẫu động).
- TL-02b `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md`: QT-09 đặc tả SCR-0901 "Phiếu tự đánh giá" (bảng trường, giải trình 20–2.000 ký tự, HTXSNV cần minh chứng `DA_XAC_NHAN`), "Quy tắc nghiệp vụ" (BR-09-05, BR-09-12); QT-18 "Quy tắc nghiệp vụ" (BR-18-04..BR-18-06, BR-18-11) và đặc tả SCR-1802.
- Hợp đồng `contracts/openapi/modules/config.yaml` (`getCurrentUiForm`), `contracts/openapi/modules/appraisals.yaml` (nhóm `/appraisals/profiles*`).
- Mã web tham chiếu: `frontend/projects/form-engine` (cách đọc `x-cel`, `x-classification`); `wiki/03-giao-dien-theo-tenant.md`, `wiki/05b-quy-trinh-va-man-hinh-qt09-18.md`.

## 3. Phạm vi công việc

- Viết bộ dựng biểu mẫu Flutter đọc JSON Schema và phần giao diện của `form_schema`, chỉ dựng các kiểu trường có trong danh mục dùng chung với web (văn bản, số, chọn một, chọn nhiều, ngày, khối lặp), trường lạ thì báo lỗi rõ thay vì tự dựng.
- Đánh giá điều kiện hiển thị và kiểm tra `x-cel`: dùng thư viện CEL cho Dart nếu đủ trưởng thành (nêu lý do chọn, cần người phụ trách duyệt phụ thuộc), nếu không thì gọi API kiểm tra của máy chủ khi trực tuyến và hoãn kiểm khi ngoại tuyến như TL-01 cho phép.
- Tôn trọng `x-classification`: trường C2 chỉ lưu trong CSDL mã hoá của MOB-02, không ghi nhật ký, màn hình có trường C2 bật chặn chụp màn hình.
- Dựng phiếu tự đánh giá: danh sách hồ sơ của tôi, mở hồ sơ, điền tiêu chí chung (quy đổi bốn mức theo BR-09-05 để hiển thị), mức tự đề xuất, giải trình 20–2.000 ký tự; lưu nháp từng bước bằng `saveAppraisalSelfAssessment` với `If-Match`, 409 thì báo tải lại.
- Nộp bằng `submitAppraisalSelfAssessment` (202), hiển thị trạng thái "chờ ký" và chuyển sang luồng ký của MOB-07.
- Viết bộ ca kiểm thử biểu mẫu dùng chung với web (cùng tệp ca của BR-18-05) để chứng minh kết quả CEL trên di động khớp cel-js và cel-java.
- Gợi ý tách: MOB-06a bộ dựng biểu mẫu và CEL; MOB-06b phiếu tự đánh giá.

## 4. Ngoài phạm vi

- Ký số (MOB-07); luồng rút gọn ba bước cho đảng viên cao tuổi (MOB-08); thẩm định, quyết định (chỉ trên máy tính).

## 5. Tệp được phép sửa

- `mobile/lib/core/forms/**`, `mobile/lib/features/appraisal/**`, `mobile/test/**`, `mobile/integration_test/**`, tệp ARB tiếng Việt trong `mobile/`

## 6. Tiêu chí hoàn thành

- `cd mobile && flutter analyze` sạch, `flutter test` xanh; bộ ca biểu mẫu dùng chung cho kết quả trùng 100% với kỳ vọng.
- Kiểm thử tích hợp với máy chủ giả lập: lưu nháp gửi `If-Match` đúng phiên bản; 409 hiện thông báo tải lại; nộp nhận 202 và hiển thị "chờ ký".
- Biểu mẫu 50 trường dựng xong ≤ 50 ms trên trình giả lập tầm trung (đối chiếu BR-18-08, ghi số đo).
- Chạy trên trình giả lập Android và iOS (ghi rõ nền tảng chưa chạy được); báo cáo theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
