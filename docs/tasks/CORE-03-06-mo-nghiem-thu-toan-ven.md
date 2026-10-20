# CORE-03-06 · SCR-0703 Mở nghiệm thu và kiểm toàn vẹn tệp

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-03-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B3 |
| Cỡ việc | S |
| Phụ thuộc | CORE-03-03 |
| Truy vết | QT-07 → SCR-0703 → API `POST /api/v1/evidences/{id}/versions/{v}/open-review` (`openEvidenceReview`), `GET /api/v1/evidences` (hàng chờ) → MOD-03 → `product.evidence_version` (`DA_NOP` → `DANG_NGHIEM_THU`) → EVT-20 khi không toàn vẹn (suy luận về kênh) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Người nghiệm thu mở một phiên bản minh chứng đã nộp; lần mở đầu tiên chuyển phiên bản sang `DANG_NGHIEM_THU` sau khi máy chủ kiểm lại băm của từng tệp với SVC-08. Băm không khớp thì chặn nghiệm thu, báo MSG-07-13 và phát cảnh báo an ninh mức cao. Có bước này thì quyết định nghiệm thu luôn dựa trên tệp nguyên vẹn.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-07: "Mô tả các bước" (dòng 1890), "Ngoại lệ và xử lý" (dòng 1945), "SCR-0703. Nghiệm thu chất lượng 5 mức" (dòng 2030, chức năng mở dòng 2066, MSG-07-11, MSG-07-13).
- `contracts/openapi/modules/products.yaml`: `listEvidences` (bộ lọc hàng chờ nghiệm thu), `getEvidenceVersion`, `openEvidenceReview`; `getFileScanResult`, `getFileDownloadUrl`.
- `wiki/15a-ma-tran-phan-quyen.md` dòng SCR-0703 (AC-07 chỉ đọc minh chứng, không nghiệm thu).
- Bản trích `tl02a §QT-07 BE-07-8`.

## 3. Phạm vi công việc

- Hiện thực `openEvidenceReview` có `Idempotency-Key`: chỉ người được giao nghiệm thu, khác người nộp và người phối hợp (BR-07-06), mở được; lần đầu đổi `DA_NOP` → `DANG_NGHIEM_THU`, các lần sau chỉ trả dữ liệu.
- Kiểm toàn vẹn: so `content_sha256` của từng tệp với bản ghi của SVC-08 qua cổng đọc; không khớp thì giữ trạng thái, trả MSG-07-13, ghi sổ cái và phát cảnh báo an ninh mức cao (TL-02a dòng 1945; cách phát qua EVT-20 hoặc kênh SOC chọn theo DV-08 và ghi rõ trong báo cáo).
- Hiện thực bộ lọc hàng chờ của `listEvidences` cho người nghiệm thu, phân trang con trỏ; phiên bản có `is_duplicate_suspect` trả kèm giải trình để giao diện hiện MSG-07-11.
- Chặn mở khi tệp chưa `SACH` (CHECK CSDL đã có) và trả lỗi rõ ràng thay vì lỗi ràng buộc.
- Ghi nhật ký truy cập khi người nghiệm thu xem nội dung có trường C2.

## 4. Ngoài phạm vi

- Xác nhận, yêu cầu bổ sung, từ chối (CORE-03-07); kiểm toàn vẹn định kỳ toàn kho (DV-08-xx).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/product/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/product/**`
- `deploy/policies/` (chỉ quy tắc Rego cho quyền mở nghiệm thu)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; phản hồi khớp `products.yaml`.
- Kiểm thử: người nộp tự mở bị 403; băm lệch bị chặn và có bản ghi outbox cảnh báo; mở lần hai không đổi trạng thái.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
