# CORE-06-07 · SCR-1102 Manifest SHA-256, danh sách kiểm tra, ký gói, khoá `DA_NOP`, phiên bản gói mới khi EVT-22

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-06-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-06-06, DV-10-02 |
| Truy vết | QT-11 bước 12–13, QT-13 → SCR-1102 → API `GET /api/v1/dossiers/packages/{id}/checklist` (`getDossierPackageChecklist`), `POST .../packages/{id}/sign` (`signDossierPackage`) → MOD-06 → `dossier.dossier_package` (`CHO_KY` → `DA_KY` → `DA_NOP`), `dossier.form_instance` → SVC-10 → nhận EVT-18, EVT-22 (`oversight.period.reopened.v1`) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Trước khi ký, máy chủ lập manifest gồm băm SHA-256 từng tệp và chạy danh sách kiểm tra BR-11-09 (mọi hồ sơ `DA_KY_SO`, không còn khiếu nại mở, băm khớp manifest, đủ chữ ký cá nhân hoặc có ghi nhận "không ký"); người có thẩm quyền ký manifest với dấu thời gian RFC 3161, gói chuyển `DA_NOP` và bị khoá (BR-11-10, MSG-11-05). Khi kỳ được mở lại hậu kiểm, hệ thống tạo phiên bản gói mới, giữ nguyên gói cũ.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-11: "Mô tả các bước" (dòng 539, bước 12–13), "Trạng thái đối tượng" (dòng 562, nhánh EVT-22), BR-11-09, BR-11-10 (dòng 579), "Ngoại lệ và xử lý" (dòng 613: thiếu chữ ký cá nhân, nộp kèm hồ sơ đang khiếu nại theo tham số tenant), "SCR-1102" (dòng 675, MSG-11-04, MSG-11-05).
- TL-03 bảng `dossier_package` (dòng 3021: `manifest`, `manifest_hash`, `package_sha256`).
- `contracts/openapi/modules/dossiers.yaml`: `getDossierPackageChecklist`, `signDossierPackage` (lỗi `DOSSIER-409-CHECKLIST-NOT-PASSED`, `DOSSIER-409-INVALID-STATE`, `DOSSIER-409-PACKAGE-LOCKED`, `DOSSIER-409-VERSION-CONFLICT`, `ESIGN-422-CERTIFICATE-INVALID`).
- Bản trích `tl02b §QT-11 BE-Q11-08, 09`.

## 3. Phạm vi công việc

- `getDossierPackageChecklist`: trả từng mục kiểm tra (mã, nhãn, đạt/chưa đạt, số hồ sơ vi phạm); trạng thái hồ sơ đọc qua cổng `api` của MOD-04, khiếu nại mở suy ra từ trạng thái `KHIEU_NAI` của hồ sơ; cho phép bỏ qua hồ sơ "đang khiếu nại" chỉ khi tham số tenant bật.
- Lập manifest chuẩn hoá (JCS) liệt kê biểu mẫu, tệp kèm, băm; tính `manifest_hash`; kiểm lại băm từng tệp với SVC-08 trước khi ký.
- `signDossierPackage`: `Idempotency-Key`, `If-Match`, AAL2; danh sách chưa đạt trả 409 + MSG-11-04; tạo yêu cầu ký mục đích `GOI_HO_SO` ở SVC-10, trả 202 kèm `signRequestId`.
- Consumer EVT-18 của chữ ký gói: lưu thông tin chữ ký và dấu thời gian, chuyển `DA_KY` rồi `DA_NOP`, đặt `submitted_at`, ghi sổ cái qua outbox, phát EVT-21 (MSG-11-05); báo MOD-04 khoá các hồ sơ trong gói (`DA_KY_SO` → `DA_KHOA`).
- Mọi API ghi lên gói `DA_NOP` trả 409 `DOSSIER-409-PACKAGE-LOCKED` (lớp mã; trigger CSDL đã có từ CORE-06-01).
- Consumer EVT-22: với gói `DA_NOP` thuộc phạm vi mở lại, tạo phiên bản gói mới ở `DANG_LAP` tham chiếu gói cũ, không sửa gói cũ.
- Điểm lệch cần lưu ý: bảng chức năng SCR-1102 ghi ký gói chuyển thẳng `DA_NOP`, bảng trạng thái ghi `DA_KY` rồi `DA_NOP`; hợp đồng mô tả `DA_KY` rồi `DA_NOP`, chọn theo hợp đồng.

## 4. Ngoài phạm vi

- Xuất ZIP và gửi gói con diện TW (CORE-06-08); dịch vụ ký và TSA (DV-10-xx).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/dossier/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/dossier/**`
- `deploy/policies/` (chỉ quy tắc Rego cho ký gói)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka; mã lỗi khớp `dossiers.yaml`.
- Kiểm thử: gói có hồ sơ chưa `DA_KY_SO` bị 409; một tệp bị đổi băm làm danh sách chưa đạt; sau `DA_NOP` mọi lệnh ghi bị 409; EVT-22 tạo gói phiên bản mới và gói cũ không đổi.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
