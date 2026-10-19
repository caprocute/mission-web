# CORE-08-14 · ADR-05/ADR-19 Bộ kiểm tra JSON Schema, `x-cel`, `x-classification` dùng chung

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-14` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | L (nên tách: a. bộ kiểm dùng chung cho API nghiệp vụ; b. chạy test biểu mẫu hai engine) |
| Phụ thuộc | CORE-08-13, NEN-041 |
| Truy vết | QT-18 → SCR-1802 → `POST /api/v1/ui/forms/{key}/versions/{v}/test-runs` (`runUiFormTests`) → MOD-08 `config.api.FormValidationApi` → `config.form_schema` → BR-18-04, BR-18-05, BR-18-11, MSG-18-08; dùng bởi API nghiệp vụ MOD-01..MOD-04 (ví dụ `PUT /api/v1/appraisals/profiles/{id}/self-assessment`); ADR-05 lớp 3, ADR-19 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Máy chủ là nguồn sự thật cho biểu mẫu động: mọi dữ liệu gửi từ biểu mẫu tới API nghiệp vụ được kiểm lại toàn bộ bằng JSON Schema và các quy tắc `x-cel` chạy trên cel-java, đồng thời trả danh sách trường C2 để module mã hoá khi lưu. Bộ test biểu mẫu chạy cel-java tại máy chủ và so với kết quả cel-js trình duyệt gửi lên; lệch thì chặn. Khi xong, MOD-04 và các module khác chỉ cần gọi một cổng để kiểm phiếu, và Config Studio có API chạy bộ test đúng hợp đồng.

## 2. Đầu vào bắt buộc đọc

- `TL-01-thiet-ke-kien-truc.md` G1 "Biểu mẫu động: Formly, JSON Schema và CEL" (dòng 1729); ADR-05 (dòng 3432); mục "Ngôn ngữ biểu thức CEL và bảng quyết định DMN" (dòng 2448).
- `TL-02b-quy-trinh.md` QT-18 "Quy tắc nghiệp vụ" (dòng 2008: BR-18-04, BR-18-05, BR-18-08, BR-18-11), SCR-1802 (dòng 2105), MSG-18-08 (dòng 2152).
- `contracts/openapi/modules/config.yaml` `runUiFormTests`, schema `UiFormTestRunRequest`, `UiFormTestRunResult`.
- Thẻ NEN-041 (`vt-cel`) và CORE-04-04 (bên dùng đầu tiên).
- Bản trích `extract/tl02b-may-chu.md` QT-18 (BE-Q18-07, BE-Q18-08), `extract/tl01-xuyen-suot.md` X-G1-08.

## 3. Phạm vi công việc

- Công bố `config.api.FormValidationApi.validate(tenant, formKey, schemaVersion, payload)` trả kết quả gồm lỗi theo trường (đường dẫn chấm, khớp schema `FieldError` của hợp đồng) và danh sách trường C2 theo `x-classification` để module gọi `FieldEncryptor` (BR-18-11).
- Kiểm JSON Schema bằng thư viện không sinh mã lúc chạy; lược đồ biên dịch một lần và đệm theo `(tenant, form_key, version)`. Nếu thư viện JSON Schema chưa có trong danh mục PL B hay thư viện nền, nêu lý do chọn trong PR để người phụ trách duyệt.
- Chạy `x-cel` và `rules[]` (`id`, `when`, `assert`, `message`, `serverOnly`) bằng `vt-cel`, có hàm mở rộng như `ngay_lam_viec` theo lịch tenant (CORE-08-04); ngân sách toàn bộ CEL một biểu mẫu ≤ 5 ms (TL-01).
- Chuyển tiếp phiên bản (BR-18-04): chấp nhận dữ liệu theo hai phiên bản schema liên tiếp nếu cùng Policy Pack; phiên bản khác Policy Pack bị từ chối.
- `runUiFormTests`: nhận ca test và kết quả cel-js của trình duyệt, chạy cel-java, đánh dấu ca lệch (MSG-18-08), lưu kết quả đạt hay không vào phiên bản biểu mẫu để CORE-08-13 dùng khi gắn vào UI Pack (BR-18-05); trả kết quả đồng bộ.
- Tạo bộ ca kiểm thử biểu mẫu dùng chung (YAML) để CI của `@vt/form-engine` chạy cùng ca với cel-js.

## 4. Ngoài phạm vi

- Gắn lời gọi kiểm vào từng API nghiệp vụ (task của module tương ứng, ví dụ CORE-04-04).
- Hiện thực hàm CEL mở rộng (NEN-041).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `data/golden/form-tests/**`
- `backend/pom.xml`, `backend/mission-core/pom.xml` (chỉ khi thêm thư viện JSON Schema đã được duyệt)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử đơn vị cho kiểu sai, thiếu trường bắt buộc, quy tắc CEL vi phạm, trường C2 được liệt kê, payload phiên bản cũ cùng Policy Pack được nhận.
- Kiểm thử hiệu năng: biểu mẫu 50 trường và 30 quy tắc kiểm trong ≤ 5 ms p95 sau khi đệm.
- `runUiFormTests` trả lệch khi cel-js gửi kết quả khác cel-java; kiểm thử hợp đồng khớp `config.yaml`.
- ArchUnit xanh: module khác chỉ dùng `config.api.FormValidationApi`, không truy cập lớp nội bộ.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
