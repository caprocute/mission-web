# CORE-04-13 · MOD-04 Đầu vào theo điểm công việc và phạm vi phụ trách, bảng sự việc, bắt buộc lý do khi mức khác mức gợi ý (DC-01, DC-03, DC-05)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-04-13` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | L |
| Phụ thuộc | NEN-044, CORE-04-06, CORE-02-27, CORE-02-28, CORE-01-14 |
| Truy vết | ADR-33 DC-01, DC-03, DC-05 → API `GET /api/v1/appraisals/inputs` (`listAppraisalScoringInputs`), `appraisals.yaml` (`incidents`) → MOD-04 → bảng `appraisal.incident`, `appraisal.rating_proposal`, `appraisal.rating_explanation` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

SVC-07 nhận được đủ dữ liệu thô để tính điểm theo điểm công việc và điểm quản lý theo phạm vi phụ trách, điều kiện loại trừ tự bật từ các sự việc đã có kết luận, và mọi mức do con người đưa ra khác mức gợi ý đều có lý do.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-03-ho-so-tap-khach-hang-va-diem-cam-loi.md` (ADR-33): "DC-01", "DC-03", "DC-05".
- Báo cáo phân tích `/mnt/project-files/phan-tich-ueb/phan-tich-ueb-hap-thu-vao-mission.md` mục "Đề xuất cụ thể".
- Thẻ CORE-04-06 (API đọc lô `inputs`); TL-03 schema `appraisal` (dòng 2613 trở đi: `score_snapshot.auto_rating_code`, `rating_proposal.stage`).

## 3. Phạm vi công việc

- Mở rộng `listAppraisalScoringInputs`: `workItems` (giới hạn 200 việc lá mỗi hồ sơ, con trỏ con khi vượt) đọc qua `TaskApi`; `responsibilityScope` theo tổ chức tại thời điểm khoá kỳ, đọc qua `OkrApi`, `TaskApi` và org replica; chỉ trả khi gói của kỳ khai báo biến tương ứng.
- Migration bảng `appraisal.incident` (loại sự việc, ngày kết luận, văn bản, tác động, cột chung, RLS; nội dung chi tiết là C2 mã hoá trường) và API của hợp đồng.
- Ràng buộc lý do khi mức của dòng `rating_proposal` khác `auto_rating_code` của ảnh chụp đã thấy (phối hợp với máy trạng thái CORE-04-03).

- Không viết hằng số, nhánh mã hay tên riêng của hồ sơ TWĐ trong mã lõi; mọi giá trị của khách hàng đi qua cấu hình (YC-TK-11, ADR-33). Mặc định của điểm cắm phải giữ đúng hành vi hiện tại.

## 4. Ngoài phạm vi

- Công thức (thuộc gói); phía SVC-07 (DV-07-03, DV-07-05).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/appraisal/**`
- `backend/mission-core/src/main/resources/db/migration/appraisal/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; `ApplicationModules.verify()` xác nhận MOD-04 chỉ đọc MOD-01, MOD-02 qua gói `api`.
- Kiểm thử: phần giảm của cấp dưới không bị tính hai lần trong `responsibilityScope`; gói không khai báo `work_items` thì không phát sinh truy vấn thêm; một trang 200 hồ sơ có `workItems` p95 ≤ 500 ms trên dữ liệu kiểm thử.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
