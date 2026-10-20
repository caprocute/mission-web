# CORE-04-11 · QT-10 Cờ "không hoàn thành hai năm liền" và đề xuất bố trí lại sang MOD-02

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-04-11` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | S |
| Phụ thuộc | CORE-04-10 |
| Truy vết | QT-10 bước 7 → SCR-1001 → API `POST /api/v1/appraisals/annual/{id}/reassignment-proposal` (`createAnnualReassignmentProposal`) → MOD-04 → `appraisal.annual_rating.reassign_flag` → `TaskApi` MOD-02 → EVT-04 (do MOD-02 phát), EVT-21 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hệ thống tự gắn cờ cho cán bộ có hai quý liên tiếp xếp loại KHTNV (BR-10-04, QĐ khoản 15.6), cảnh báo người có thẩm quyền (MSG-10-02) và cho phép lập đề xuất bố trí lại công việc thành một việc giao cho cơ quan tổ chức trong MOD-02 với hạn 30 ngày. Việc này bảo đảm quy định về bố trí lại không bị bỏ sót.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-10: "Mô tả các bước" (dòng 367, bước 7 dòng 377), "Quy tắc nghiệp vụ" (dòng 385, BR-10-04), "SCR-1001. Xếp loại năm" (dòng 414, chức năng lập đề xuất bố trí lại, MSG-10-02).
- TL-03 bảng `annual_rating` (dòng 2917: `consecutive_khtnv`, `relocation_proposed`, `reassign_flag`).
- `contracts/openapi/modules/appraisals.yaml`: `createAnnualReassignmentProposal`; `contracts/openapi/modules/tasks.yaml` (dạng việc nhận được).
- Bản trích `tl02b §QT-10 BE-Q10-03`.

## 3. Phạm vi công việc

- Khi lập hoặc tính lại hồ sơ năm, tính `consecutive_khtnv` từ các quý; hai quý liên tiếp KHTNV thì đặt `reassign_flag = true` (và `relocation_proposed` theo cùng giá trị cho tới khi TL-03 bỏ cột trùng), phát EVT-21 với MSG-10-02.
- `createAnnualReassignmentProposal`: `Idempotency-Key`, kiểm OPA (cơ quan tổ chức hoặc lãnh đạo trong phạm vi), chỉ cho hồ sơ có cờ; gọi cổng `TaskApi` của MOD-02 tạo việc có căn cứ khoản 15.6, hạn 30 ngày (tham số); MOD-02 phát EVT-04.
- Không tạo việc trùng cho cùng hồ sơ năm; lần gọi thứ hai trả việc đã có.
- Ghi sổ cái qua outbox việc lập đề xuất.
- Điểm lệch cần lưu ý: tên hàng trong khung kế hoạch ghi "hai năm liền" trong khi TL-02b BR-10-04 ghi "hai quý liên tiếp KHTNV"; làm theo TL-02b.

## 4. Ngoài phạm vi

- Xử lý việc bố trí lại trong MOD-02; DMN năm (DV-07-07).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/appraisal/**`
- `deploy/policies/` (chỉ quy tắc Rego cho đề xuất bố trí lại)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; ArchUnit xác nhận MOD-04 gọi MOD-02 chỉ qua gói `api`.
- Kiểm thử: KHTNV ở quý II và III đặt cờ, KHTNV ở quý I và III không đặt; gọi đề xuất hai lần chỉ tạo một việc.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
