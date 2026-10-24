# DV-02-07 · EVT-14 Consumer EVT-14 cập nhật Organizations Keycloak khi tổ chức thay đổi

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-02-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | S |
| Phụ thuộc | DV-02-01, DV-04-03; DV-02-06 (dịch vụ `iam` và consumer EVT-16 dùng chung khung), DV-02-02 (cách mapper lấy `org_unit_ids`) (bổ sung) |
| Truy vết | QT-01 → EVT-14 DonViDaThayDoi (`org.unit.changed.v1`, SVC-04 phát) → SVC-02 iam (Keycloak Admin REST, mạng quản trị) → dữ liệu nhận bản sao "tư cách thành viên Organization từ EVT-16, EVT-14" (TL-03 bảng SVC-02) → `iam.inbox_processed` (suy luận); ADR-08 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Baseline mục 7 ghi SVC-02 là bên tiêu thụ EVT-14, và TL-03 ghi Keycloak nhận bản sao tư cách thành viên Organization từ EVT-16 và EVT-14, nhưng DV-02-06 mới làm EVT-16. Task này thêm consumer EVT-14 vào dịch vụ `iam`: khi đơn vị được tạo, đổi tên, sáp nhập, chia tách hay giải thể, các thuộc tính mà Keycloak dùng để dựng claim (`org_unit_ids`) và nhóm hay thuộc tính Organization tương ứng được cập nhật, phiên của người thuộc đơn vị bị giải thể được làm mới. Khi xong, token cấp sau một thay đổi tổ chức không còn mang mã đơn vị đã hết hiệu lực.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` SVC-02 (L995: Organizations đại diện tenant, mapper claim `tenant_id`, `org_unit_ids`, `flows`, `acr`; L999: đồng bộ qua Admin API), ADR-08 (L3462).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng SVC-02 (L316–326: nhận EVT-14, EVT-16), danh mục EVT-14 (L4454: `unitId`, `versionId`, `sourceVersion`, trạng thái đầy đủ, `validFrom`, `validTo`, `changeOrderId`), "Event-carried State Transfer" (L4291), "Inbox idempotent" (L4269).
- Baseline `00-co-so-thiet-ke-chung.md` mục 7 (EVT-14 dòng 150).
- Lược đồ Avro EVT-14 trong `contracts/events/avro/` (NEN-007); thẻ `docs/tasks/DV-02-06-dong-bo-nguoi-dung.md`, `DV-02-02-claim-ngu-canh.md`, `DV-04-03-thay-doi-to-chuc.md`.

## 3. Phạm vi công việc

- Thêm listener EVT-14 vào `backend/services/iam/` dùng chung cấu hình consumer của DV-02-06 (NEN-012): khoá `tenantId:aggregateId`, khử trùng `inbox_processed`, bỏ sự kiện cũ theo `sourceVersion` qua `inbox_aggregate_version`.
- Xác định Organization theo `tenant_id` của sự kiện; chỉ cập nhật dữ liệu mà mapper của DV-02-02 thật sự đọc (thuộc tính hiển thị đơn vị, nhóm theo đơn vị nếu DV-02-02 chọn mô hình nhóm, suy luận); không chép cây tổ chức đầy đủ vào Keycloak.
- Đơn vị hết hiệu lực (`validTo` đã qua, giải thể hoặc bị sáp nhập kèm `changeOrderId`): gỡ mã đơn vị khỏi thuộc tính của người liên quan nếu mapper đọc từ thuộc tính người dùng, và đăng xuất các phiên đang mở của họ để token mới lấy ngữ cảnh mới; đơn vị có hiệu lực tương lai thì không làm gì cho tới `validFrom` (ghi lịch hoặc xử lý khi EVT-15 tương ứng đến, ghi lựa chọn vào báo cáo).
- Gọi Admin API qua client credentials riêng trong mạng quản trị, Resilience4j (timeout 3 s, circuit breaker, thử lại có backoff), lỗi vĩnh viễn vào DLQ theo quy ước NEN-012.
- Metric độ trễ đồng bộ và số thông điệp DLQ qua OpenTelemetry; không ghi tên đơn vị hay người dùng vào nhật ký ở mức INFO (chỉ mã).
- Điểm lệch cần lưu ý: phân công người vào đơn vị đi theo EVT-15, mà baseline không ghi SVC-02 tiêu thụ EVT-15; nếu mapper cần danh sách đơn vị theo người thì nguồn phải là thuộc tính do EVT-16 mang (trạng thái nhân sự đầy đủ) hoặc mapper tra org-service lúc cấp token; ghi câu hỏi vào `wiki/14`, không tự tiêu thụ EVT-15.

## 4. Ngoài phạm vi

- Consumer EVT-16 (DV-02-06); protocol mapper và API chọn ngữ cảnh (DV-02-02); phát EVT-14 (DV-04-03).

## 5. Tệp được phép sửa

- `backend/services/iam/**`
- `deploy/charts/iam/**` (chỉ cấu hình consumer mới)
- `wiki/07-xac-thuc-phan-quyen.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/iam -am verify` chạy xanh với Testcontainers Kafka + Apicurio + Keycloak 26: đổi tên đơn vị cập nhật đúng dữ liệu Keycloak; giải thể đơn vị làm phiên của thành viên bị đăng xuất và token mới không còn mã đơn vị đó; nhận trùng và sự kiện cũ không gây ghi lại.
- Độ trễ từ lúc phát EVT-14 tới khi Keycloak cập nhật p99 ≤ 5 giây trong kiểm thử (TL-03 ECST).
- `helm lint deploy/charts/iam` không lỗi; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
