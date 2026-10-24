# CORE-06-06 · SCR-1102 Tạo gói hồ sơ khi có EVT-12, theo dõi sinh biểu mẫu, sinh lại tệp lỗi

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-06-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | CORE-06-01, CORE-04-08, DV-11-04 |
| Truy vết | QT-11 bước 10–12 → SCR-1102 → API `GET/POST /api/v1/dossiers/packages`, `GET .../packages/{id}`, `GET .../packages/{id}/forms`, `POST .../packages/{id}/regenerate`, `POST /api/v1/dossiers/render-jobs` → MOD-06 → `dossier.dossier_package` (`DANG_LAP` → `DANG_SINH` → `CHO_KY`), `dossier.form_instance` → nhận EVT-12, EVT-17 (`dossier.form.generated.v1`) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khi hồ sơ được quyết định (EVT-12), MOD-06 bảo đảm có gói hồ sơ của đơn vị và kỳ, yêu cầu SVC-11 sinh biểu mẫu chính thức (Mẫu 1A–1D, 2, 3, bảng chấm điểm, PDF/A-2b có tem phiên bản), theo dõi từng tệp qua EVT-17 và cho sinh lại riêng các tệp lỗi (MSG-11-07). Gói đủ tệp chuyển `CHO_KY`, sẵn sàng cho bước ký gói.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-11: "Mô tả các bước" (dòng 539, bước 10–12 và đoạn về tải cuối năm), "Trạng thái đối tượng" (dòng 562), BR-11-02, BR-11-03 (dòng 579), "Ngoại lệ và xử lý" (dòng 613, sinh lỗi một phần), "SCR-1102" (dòng 675, MSG-11-07).
- TL-03 bảng `dossier_package` (dòng 3021), `form_instance` (dòng 3040); "Schema docgen" của SVC-11.
- `contracts/openapi/modules/dossiers.yaml`: `listDossierPackages`, `createDossierPackage`, `getDossierPackage`, `listDossierPackageForms`, `regenerateDossierPackageForms`, `createDossierRenderJob`.
- Bản trích `tl02b §QT-11 BE-Q11-06`.

## 3. Phạm vi công việc

- Consumer EVT-12 có inbox: tạo gói `DANG_LAP` cho (đơn vị tại ngày khoá kỳ, kỳ, loại `QUY`/`NAM`) nếu chưa có, sinh `code` dạng `DP-yyyy-{Q/N}-nnnn` duy nhất trong tenant; ghi hồ sơ vào danh mục chờ sinh.
- `createDossierPackage` (thủ công, AC-07): cùng quy tắc, trùng thì 409; `listDossierPackages`, `getDossierPackage`, `listDossierPackageForms` có phân trang con trỏ và OPA phạm vi đơn vị.
- Khi mọi hồ sơ của đơn vị đã có quyết định hoặc thuộc trường hợp đặc thù: gửi yêu cầu sinh tới SVC-11 (202), tạo `form_instance` `CHO_SINH` theo danh mục biểu mẫu của Policy Pack, gói sang `DANG_SINH`.
- Consumer EVT-17: cập nhật `object_key`, `pdf_sha256`, `template_version`, `data_snapshot_hash`, `status = 'DA_SINH'`; tệp lỗi đặt `LOI` và gói về `DANG_LAP` (giữ tệp đã sinh); tệp cuối đạt thì gói sang `CHO_KY`.
- `regenerateDossierPackageForms` (202): chỉ sinh lại tệp `LOI`, `Idempotency-Key`.
- Bảo đảm luồng chịu được lô 100.000 phiếu (NFR-06: sinh và ký ≤ 4 giờ): consumer xử lý theo lô, không giữ khoá dòng gói trong lúc chờ SVC-11.

## 4. Ngoài phạm vi

- Worker sinh PDF/A (DV-11-02..04); manifest và ký gói (CORE-06-07).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/dossier/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/dossier/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, Testcontainers PostgreSQL + Kafka; phản hồi khớp `dossiers.yaml`.
- Kiểm thử: 1.000 EVT-12 cùng đơn vị chỉ tạo một gói; một tệp lỗi đưa gói về `DANG_LAP`, sinh lại chỉ gửi tệp lỗi; EVT-17 trùng không đổi băm.
- Đo xử lý 100.000 EVT-17 trên máy kiểm thử, ghi kết quả vào báo cáo so với ngân sách NFR-06.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
