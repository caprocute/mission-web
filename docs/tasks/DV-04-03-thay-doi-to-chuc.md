# DV-04-03 · QT-01/SCR-0103 Thay đổi tổ chức có tiền thân, phát EVT-14, EVT-15, EVT-16

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-04-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-04-02, NEN-011 |
| Truy vết | QT-01 → SCR-0103 → `POST /api/v1/org/units/{id}/changes` (`changeOrgUnit`) → SVC-04 → bảng `org.org_change_order`, `org.org_unit`, `org.org_unit_relation`, `org.assignment`, `org.outbox_event` → EVT-14 `org.unit.changed.v1`, EVT-15 `org.assignment.changed.v1`, EVT-16 `org.person.changed.v1`; BR-01-05, BR-01-07; MSG-01-09, MSG-01-10, MSG-01-11 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Ghi nhận các quyết định thay đổi tổ chức (thành lập, đổi tên, sáp nhập, hợp nhất, chia tách, giải thể) với ngày hiệu lực và căn cứ, giữ nguyên lịch sử, tự chuyển phân công sang đơn vị mới khi sáp nhập, và phát sự kiện để mission-core, iam, analytics cập nhật bản sao. Khi xong, mọi thay đổi tổ chức đều có quyết định gốc, có đơn vị tiền thân khi sáp nhập, và hệ thống khác nhận sự kiện trong vài giây.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-01 SCR-0103 bảng chức năng (dòng 686–691), BR-01-05, BR-01-07 (dòng 541–556), MSG-01-09..11 (dòng 696–698), sự kiện (dòng 503, 746).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `org.org_change_order` (dòng 1281–1293), `org.org_unit_relation` (dòng 1142–1157), mục "Tác động khi đính chính lùi ngày" (dòng 4084) và "Event-carried State Transfer cho dữ liệu tổ chức" (dòng 4289).
- Baseline `00-co-so-thiet-ke-chung.md` mục 7 (EVT-14, EVT-15, EVT-16), mục 8.
- Hợp đồng: `contracts/openapi/modules/org.yaml` `changeOrgUnit` (enum `THANH_LAP`, `DOI_TEN`, `SAP_NHAP`, `HOP_NHAT`, `CHIA_TACH`, `GIAI_THE`, trường `moveAssignmentsTo`); lược đồ Avro EVT-14..16 trong `contracts/events/avro/`.
- Wiki: `wiki/04-ma-dinh-danh-va-truy-vet.md`, `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Hiện thực `changeOrgUnit` (Idempotency-Key): tạo `org_change_order` (`order_no`, `order_date`, `effective_on`, `signed_doc_ref`, `payload`), áp thay đổi trong một giao dịch bằng thành phần ghi phiên bản của DV-04-01, đặt trạng thái `DA_AP_DUNG`.
- Sáp nhập, hợp nhất bắt buộc khai báo ít nhất một đơn vị tiền thân (BR-01-07, MSG-01-09); chia tách và giải thể đóng hiệu lực đơn vị cũ bằng phiên bản có `valid_to`.
- Khi có `moveAssignmentsTo`, đóng phân công ở đơn vị cũ tại ngày hiệu lực và tạo phân công tương ứng ở đơn vị mới; trả số phân công đã chuyển để giao diện hiện MSG-01-11.
- Mỗi đơn vị mới hoặc đổi thuộc đúng một trong 9 khối PL 2A (BR-01-05).
- Khi ngày hiệu lực rơi vào kỳ đã khoá, vẫn ghi nhưng trả cảnh báo để hiện MSG-01-10, không tự tính lại kỳ (TL-03 dòng 4084).
- Ghi outbox trong cùng giao dịch: EVT-14 cho mỗi đơn vị đổi, EVT-15 cho mỗi phân công đổi, EVT-16 khi thông tin người đổi; phong bì sự kiện theo NEN-011 (`eventId`, `tenantId`, `occurredAt`, `actorId`, `traceparent`, `classification`), không mang trường C2 dạng rõ.
- Kiểm thử Testcontainers PostgreSQL + Kafka + Apicurio: sáp nhập hai đơn vị chuyển đủ phân công và phát đúng số sự kiện; thiếu tiền thân bị chặn; giao dịch lỗi không để lại sự kiện mồ côi.
- Điểm lệch cần lưu ý: TL-02a ghi quan hệ tiền thân là `PREDECESSOR` trong `org.org_unit_relation`, nhưng TL-03 chỉ cho `relation_type` `TRUC_THUOC`, `CHI_DAO`, `PHOI_HOP`; giá trị `PREDECESSOR`/tiền thân cũng không có trong CHECK. Không tự thêm giá trị; lưu đơn vị tiền thân trong `org_change_order.payload` (đủ cho truy vết, suy luận) và ghi đề nghị bổ sung vào wiki trang 14 để người phụ trách chốt.

## 4. Ngoài phạm vi

- Bản sao `org_replica` và consumer ở mission-core (CORE-00-03); consumer ở iam (DV-02-06).
- Đổi người duyệt trong tiến trình Flowable khi điều động (DV-06-03).

## 5. Tệp được phép sửa

- `backend/services/org-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/org-service -am verify` chạy xanh gồm Testcontainers Kafka, kiểm thử hợp đồng; độ phủ dòng `domain`, `application` ≥ 80%.
- Đo độ trễ outbox tới topic trong kiểm thử, ghi số đo (mục tiêu ECST p99 ≤ 5 giây theo TL-03).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
