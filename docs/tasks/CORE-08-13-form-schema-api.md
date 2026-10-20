# CORE-08-13 · QT-18/SCR-1802 API biểu mẫu động `form_schema`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-13` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-08-08, CORE-05-03 |
| Truy vết | QT-18 → SCR-1802 → `GET /api/v1/ui/forms` (`listUiForms`), `POST /api/v1/ui/forms/{key}/versions` (`createUiFormVersion`), `GET /api/v1/ui/forms/{key}/current` (`getCurrentUiForm`), `GET/PUT /api/v1/ui/forms/{key}/versions/{v}` (`getUiFormVersion`, `updateUiFormVersion`), `POST /api/v1/ui/packs/{id}/versions/{v}/forms` (`attachUiPackForm`) → MOD-08 → `config.form_schema` → BR-18-04, BR-18-06, BR-18-07, BR-18-11, MSG-18-06, MSG-18-07, lỗi `UI-409-SCORED-FIELD`, `UI-422-CEL-INVALID`, `UI-422-FORM-TEST-FAILED` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Lưu và phục vụ biểu mẫu động theo phiên bản (JSON Schema có `x-cel`, `x-classification` và phần trình bày) cho Config Studio và cho shell, kiểm rằng mọi trường tính điểm khớp khai báo của Policy Pack và không bị đổi kiểu hay xoá. Khi xong, SCR-1802 soạn, lưu, xem và gắn biểu mẫu vào UI Pack nháp qua API thật; shell tải biểu mẫu hiện hành có ETag để đệm.

## 2. Đầu vào bắt buộc đọc

- `TL-02b-quy-trinh.md` QT-18 "Quy tắc nghiệp vụ" (dòng 2008: BR-18-04, BR-18-06, BR-18-07, BR-18-11, khoá dữ liệu snake_case ≤ 64, điều kiện hiển thị không vòng), "SCR-1802. Trình dựng biểu mẫu động" (dòng 2105), MSG-18-06, MSG-18-07 (dòng 2150–2151).
- `TL-01-thiet-ke-kien-truc.md` G1 "Biểu mẫu động: Formly, JSON Schema và CEL" (dòng 1729), "Quản trị phiên bản UI Pack và Policy Pack" (dòng 1799).
- `TL-03-thiet-ke-csdl.md` "Bảng config.form_schema" (dòng 1570).
- `contracts/openapi/modules/config.yaml` nhóm `/ui/forms` và `/ui/packs/{id}/versions/{v}/forms`.
- Bản trích `extract/tl02b-may-chu.md` QT-18 (BE-Q18-06).

## 3. Phạm vi công việc

- Aggregate `FormSchema` theo `(tenant_id, form_key, version)` với trạng thái `NHAP`, `DA_PHAT_HANH`, `NGUNG`; tạo phiên bản nhân bản từ phiên bản chỉ định (Idempotency-Key), sửa chỉ khi `NHAP` và bắt buộc `If-Match`.
- Kiểm khi lưu: JSON Schema hợp lệ; khoá dữ liệu snake_case ≤ 64 ký tự, duy nhất; mọi trường có `x-classification` C1 hoặc C2 (BR-18-11); biểu thức `x-cel` biên dịch được bằng `vt-cel` (MSG-18-06, `UI-422-CEL-INVALID`); điều kiện hiển thị không tham chiếu vòng.
- Kiểm trường tính điểm qua `PolicySchemaPort`: trường mà Policy Pack tham chiếu (`policy_pack_ref`) phải tồn tại, đúng kiểu, không bị xoá (MSG-18-07, `UI-409-SCORED-FIELD`).
- `getCurrentUiForm` và `getUiFormVersion` trả `ETag` theo phiên bản, hỗ trợ `If-None-Match` 304.
- `attachUiPackForm`: chỉ gắn phiên bản đã đạt bộ test của CORE-08-14 (`UI-422-FORM-TEST-FAILED`), Policy Pack khớp dải của UI Pack (422 theo BR-18-06), UI Pack đích phải `NHAP`.
- BR-18-07: đổi thuần trình bày (nhãn, thứ tự, widget) không đánh dấu cần chạy lại test tính điểm; đổi trường tính điểm thì bắt buộc test lại.
- Điểm lệch cần lưu ý: TL-01 nói Policy Pack sở hữu JSON Schema phiếu (UI Pack chỉ tham chiếu `schemaRef`), còn TL-02b và TL-03 lưu `schema_json` trong `config.form_schema` kèm `policy_pack_ref`. Chọn theo hợp đồng `config.yaml` (lưu trong `form_schema`) và luôn kiểm khớp với khai báo của Policy Pack; ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Bộ kiểm tra dùng chung cho API nghiệp vụ và chạy test hai engine (CORE-08-14).
- Màn hình SCR-1802 (WEB-050).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử tích hợp Testcontainers cho: đổi kiểu trường tính điểm bị 409, CEL sai bị 422, khoá trùng bị 422, điều kiện vòng bị 422, `If-None-Match` trả 304.
- Kiểm thử hợp đồng sáu thao tác khớp `config.yaml`; `openapi-diff` không phá tương thích.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
