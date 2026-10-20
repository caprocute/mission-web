# CORE-03-03 · QT-07/SCR-0701 Khai báo sản phẩm, lưu nháp và nộp phiên bản minh chứng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-03-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B3 |
| Cỡ việc | L (nên tách: phần A sản phẩm và nháp; phần B nộp, consumer, EVT-07) |
| Phụ thuộc | CORE-03-02, DV-08-02 |
| Truy vết | QT-07 → SCR-0701 → API `/api/v1/products`, `/api/v1/evidences/{id}/versions/{v}/submit` → MOD-03 → `product.deliverable`, `product.evidence`, `product.evidence_version` → EVT-07 (`product.evidence.submitted.v1`); nhận `evidence.object.stored.v1` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cán bộ chủ trì khai báo được sản phẩm của nhiệm vụ, tạo minh chứng, lưu nháp phiên bản n+1 gắn tệp đã tải lên SVC-08 và nộp phiên bản để nghiệm thu. Máy chủ kiểm đủ điều kiện nộp (tệp sạch, băm khớp, giải trình trùng, cam kết), phát EVT-07 qua outbox và liên động nhiệm vụ sang `CHO_NGHIEM_THU` khi đủ sản phẩm. Đây là đầu vào bắt buộc của nghiệm thu và tính điểm.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-07: "Mô tả các bước" (dòng 1880), "Quy tắc nghiệp vụ" (dòng 1917: BR-07-01..04), "Ngoại lệ và xử lý" (dòng 1935), "SCR-0701. Nộp minh chứng" (dòng 1949, bảng chức năng và MSG-07-01..05).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Sự kiện phát và nhận" (dòng 830) và SVC-08 "Dữ liệu, sự kiện, co giãn" (dòng 1104).
- `contracts/openapi/modules/products.yaml`: `listDeliverables`, `createDeliverable`, `getDeliverable`, `listEvidences`, `createEvidence`, `getEvidence`, `createEvidenceVersion`, `getEvidenceVersion`, `updateEvidenceVersionDraft`, `submitEvidenceVersion`.
- Bản trích `tl02a §QT-07 BE-07-5`; `wiki/06-quy-uoc-api.md`; `wiki/15a-ma-tran-phan-quyen.md` dòng SCR-0701.

## 3. Phạm vi công việc

- Hiện thực các operation trên theo giao diện sinh từ hợp đồng (`interfaceOnly=true`); lệnh ghi nhận `Idempotency-Key` (lưu 48 giờ), `PUT` lưu nháp nhận `If-Match` và trả 409 khi lệch `row_version`.
- Chỉ người chủ trì nhiệm vụ được nộp (TL-02a dòng 1956); kiểm vai trò bằng token và phạm vi bằng OPA qua `@RequiresPermission`.
- Viết consumer `evidence.object.stored.v1` có inbox: gắn `object_id`, `content_sha256`, `scan_status` vào phiên bản nháp đang chờ; tệp nhiễm (MSG-07-02) giữ phiên bản ở `NHAP` và không cho nộp (BR-07-04).
- Nộp (`submitEvidenceVersion`): chặn khi băm không khớp (MSG-07-03, BR-07-02), khi còn tệp chưa `SACH` (BR-07-04), khi trùng băm với minh chứng của nhiệm vụ khác mà thiếu `reuse_justification` 20–1.000 ký tự (MSG-07-01, BR-07-03, đặt `is_duplicate_suspect`, `duplicate_of_id`), khi thiếu `attested_at`.
- Nộp thành công: phiên bản sang `DA_NOP`, ghi outbox EVT-07 trong cùng giao dịch, gọi liên động của CORE-03-02 (MSG-07-04, MSG-07-05), phát EVT-21 nhắc người nghiệm thu.
- Chặn ghi khi kỳ `DANG_KHOA`/`DA_KHOA` qua `PeriodApi` (BR-08-06, MSG-08-06); nộp sau ngày cuối kỳ chỉ nhận tới hạn nghiệm thu tồn đọng (TL-02a dòng 1943).
- Đăng ký lược đồ Avro EVT-07 trong `contracts/events/avro/` nếu chưa có (chế độ BACKWARD).
- Điểm lệch cần lưu ý: TL-01 phụ lục C ghi `POST /products/{id}/evidence-versions`, hợp đồng ghi `POST /evidences/{id}/versions`; chọn hợp đồng (API-first). TL-01 dòng 1106 ghi topic `evidence.object.stored.v1`, TL-03 ghi `sys.evidence.object.stored.v1`; chọn theo hợp đồng sự kiện do DV-08-02 đăng ký và ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Tải tệp nhiều phần, băm, Object Lock, quét mã độc (DV-08-02, DV-08-03); văn bản vOffice (CORE-03-04); OCR (CORE-03-05); nghiệm thu (CORE-03-06, CORE-03-07).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/product/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/product/**`
- `contracts/events/avro/` (chỉ thêm lược đồ EVT-07 nếu chưa có)
- `deploy/policies/` (chỉ quy tắc Rego và ca `opa test` cho thao tác nộp minh chứng)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, gồm kiểm thử tích hợp Testcontainers PostgreSQL + Kafka cho consumer và outbox; so khớp phản hồi với `products.yaml`; `openapi-diff` không báo phá tương thích.
- Gửi lại cùng `Idempotency-Key` trả cùng kết quả, không phát EVT-07 lần hai; sự kiện `object.stored` trùng không ghi đè.
- Mỗi lỗi chặn trả RFC 9457 có `type` trỏ đúng BR/MSG; p95 lệnh nộp ≤ 800 ms (NFR-03, đo trong kiểm thử tải nhỏ).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
