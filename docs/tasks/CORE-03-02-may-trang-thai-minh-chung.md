# CORE-03-02 · BR-07-10 Máy trạng thái phiên bản minh chứng và liên động với máy trạng thái nhiệm vụ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-03-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-03-01, CORE-02-13 |
| Truy vết | QT-07 → MOD-03 `product` (domain) → `product.evidence_version.status`, `product.deliverable.status` → `TaskApi` của MOD-02 → EVT-06 (do MOD-02 phát) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực trong lớp `domain` của MOD-03 máy trạng thái phiên bản minh chứng đúng baseline 9.1 và TL-02a, tách biệt với máy trạng thái thực hiện nhiệm vụ của MOD-02 (BR-07-10), cùng cơ chế liên động: đủ sản phẩm nộp thì nhiệm vụ sang `CHO_NGHIEM_THU`, đủ sản phẩm xác nhận thì sang `HOAN_THANH`, bị yêu cầu bổ sung hoặc từ chối thì về `DANG_THUC_HIEN`. Khi xong, các API nộp và nghiệm thu ở CORE-03-03, CORE-03-06, CORE-03-07 chỉ gọi lệnh domain, không tự đặt trạng thái.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` mục QT-07: "Trạng thái đối tượng" (dòng 1899), "Quy tắc nghiệp vụ" (dòng 1917, BR-07-05, BR-07-10), "Ngoại lệ và xử lý" (dòng 1935).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 9.1 "Trạng thái chuẩn".
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Máy trạng thái nhiệm vụ" (dòng 599) và "Giao tiếp giữa module" (dòng 553).
- TL-03 bảng `product.deliverable` (dòng 2516, trạng thái `CHUA_CO`, `DANG_THUC_HIEN`, `DA_XAC_NHAN_MOT_PHAN`, `DA_XAC_NHAN`).
- Bản trích `tl02a §QT-07 BE-07-4`; `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md` phần QT-07.

## 3. Phạm vi công việc

- Viết aggregate `EvidenceVersion` với các lệnh `saveDraft`, `submit`, `openReview`, `accept`, `requestRevision`, `reject`; mỗi lệnh kiểm trạng thái nguồn hợp lệ và trả lỗi nghiệp vụ có mã MSG khi sai, không có lệnh nào đưa phiên bản rời `DA_XAC_NHAN`, `YEU_CAU_BO_SUNG`, `TU_CHOI`.
- Viết quy tắc tổng hợp `deliverable.status` từ các phiên bản: `quantity_accepted` chỉ đếm phiên bản `DA_XAC_NHAN` (BR-07-05), cập nhật `DA_XAC_NHAN_MOT_PHAN` hoặc `DA_XAC_NHAN`.
- Viết dịch vụ liên động gọi cổng `TaskApi` của MOD-02 (CORE-02-13): yêu cầu chuyển `CHO_NGHIEM_THU` khi mọi sản phẩm của nhiệm vụ đã có phiên bản `DA_NOP` trở lên, `HOAN_THANH` khi mọi sản phẩm `DA_XAC_NHAN`, `DANG_THUC_HIEN` khi một phiên bản bị `YEU_CAU_BO_SUNG` hoặc `TU_CHOI`; ngày hoàn thành tạm là ngày nộp phiên bản cuối.
- Bảo đảm chuyển trạng thái phiên bản và yêu cầu chuyển trạng thái nhiệm vụ nằm cùng một giao dịch, khoá lạc quan bằng `row_version`, xung đột trả 409.
- Ghi nhận cờ `excluded_period_id` là trường chỉ do saga khoá kỳ đặt (BR-08-05), domain chỉ đọc để loại khỏi tính điểm.
- Viết kiểm thử bảng chuyển trạng thái đầy đủ (mọi cặp nguồn–đích hợp lệ và không hợp lệ) và kiểm thử thuộc tính cho tổng hợp số lượng.

## 4. Ngoài phạm vi

- API HTTP và phát EVT-07, EVT-08 (CORE-03-03, CORE-03-07); bảng chuyển trạng thái nhiệm vụ và EVT-06 (CORE-02-13).
- Đặt cờ "không tính trong kỳ" khi khoá kỳ (saga DV-06-06).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/product/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/product/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh; `ApplicationModules.verify()` và ArchUnit xác nhận MOD-03 chỉ gọi MOD-02 qua gói `api`.
- Độ phủ dòng lớp `domain` và `application` của phần mới ≥ 80%.
- Kiểm thử chứng minh hai máy trạng thái độc lập: phiên bản `TU_CHOI` đưa nhiệm vụ về `DANG_THUC_HIEN` nhưng không đổi trạng thái các phiên bản khác.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
