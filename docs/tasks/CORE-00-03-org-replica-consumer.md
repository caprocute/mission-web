# CORE-00-03 · SVC-05 Schema `org_replica` và consumer EVT-14, EVT-15, EVT-16

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-00-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 (mốc M1) |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-00-02, NEN-012, DV-04-03 |
| Truy vết | baseline mục 8.3 (Event-carried State Transfer) → EVT-14 `org.unit.changed.v1`, EVT-15 `org.assignment.changed.v1`, EVT-16 `org.person.changed.v1`, `org.bootstrap.v1` → bảng `org_replica.org_unit_v`, `person_v`, `assignment_v`, `unit_closure_v`, `replica_checkpoint`, `reconcile_run` → mọi module MOD-01..MOD-08 đọc |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Đưa dữ liệu tổ chức và nhân sự của SVC-04 về bản đọc cục bộ trong mission-core, để tám module kiểm phân quyền theo cây tổ chức, tra người đứng đầu, cấp trên trực tiếp mà không gọi đồng bộ sang org-service trên đường găng. Bản sao cập nhật từ EVT-14/15/16 với độ trễ p99 ≤ 5 giây, bỏ qua sự kiện cũ đến muộn, giữ bảng bao đóng cây để kiểm "đơn vị X là cấp dưới của đơn vị mình" bằng một phép tra chỉ mục, và đo được độ trễ bản sao.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Schema org_replica (bản sao trong mission-core)" (L1330–1403, từ điển dữ liệu sáu bảng, câu upsert theo `source_version` L1403), danh mục sở hữu L462–471 (vai trò `svc_org_replica`, gói `replica`), chương "Dữ liệu tổ chức hai trục thời gian" mục "Bản sao một trục ở mission-core" (L4080–4082) và "Tác động khi đính chính lùi ngày" (L4084–4086), mục "Event-carried State Transfer cho dữ liệu tổ chức" (L4289–4311).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng "Danh mục lược đồ EVT-01 đến EVT-26" dòng EVT-14, EVT-15, EVT-16 (trường mang theo).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Dữ liệu sở hữu" và "Sự kiện phát và nhận" của SVC-05 (L824–832).
- Kết quả NEN-012 (khung consumer), NEN-031 (vai trò `svc_org_replica`), NEN-040 (định dạng dữ liệu cây mà chính sách OPA cần), DV-04-03 (lược đồ sự kiện phát thật).

## 3. Phạm vi công việc

- Migration `backend/mission-core/src/main/resources/db/migration/org_replica/`: sáu bảng đúng từ điển TL-03 (`org_unit_v` PK `(tenant_id, id, valid_from)`; `person_v`; `assignment_v` với các chỉ mục `(tenant_id, person_id, valid_to)`, `(tenant_id, org_unit_id, valid_to)`, chỉ mục một phần `WHERE is_head`; `unit_closure_v` PK `(tenant_id, tree, ancestor_id, descendant_id)`; `replica_checkpoint`; `reconcile_run` với `status` `DANG_CHAY`/`KHOP`/`DA_SUA`/`LOI`), cùng bộ bảng dùng chung NEN-032 (inbox, outbox nếu cần), RLS chuẩn NEN-033, chú thích phân loại (không có cột C2).
- Gói `vn.viettel.okrkpi.replica` (gói kỹ thuật ngoài tám module, chỉ ghi qua DataSource riêng tài khoản `svc_org_replica`; tám module đọc qua cổng `replica.api.OrgDirectoryApi`): consumer `@InboxListener` cho ba topic, upsert `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE excluded.source_version > <bảng>.source_version`.
- Duy trì `unit_closure_v` khi EVT-14 đổi cha (`party_parent_id`, `admin_parent_id`) cho từng cây (`tree`), trong cùng giao dịch với upsert đơn vị; phát hiện và từ chối vòng (sự kiện vào DLQ có lý do).
- `replica_checkpoint` cập nhật `last_event_id`, `last_occurred_at`, `lag_ms`; xuất metric `org_replica_lag_ms` theo tenant và loại thực thể.
- Nạp ban đầu: consumer `org.bootstrap.v1` (TL-03 L4289–4311: lệnh `org-republish` phát toàn bộ trạng thái) dùng cùng logic upsert; nếu DV-04 chưa có lệnh phát thì ghi vào báo cáo.
- Cổng đọc `OrgDirectoryApi`: phân công hiệu lực của người tại ngày, người đứng đầu đơn vị, cấp trên trực tiếp, đơn vị con theo cây, kiểm "là hậu duệ"; cung cấp dữ liệu cây theo định dạng NEN-040 cần cho đầu vào OPA.
- Đính chính lùi ngày: khi EVT-15 có `valid_from` rơi vào kỳ đã khoá, phát sự kiện ứng dụng nội bộ để MOD-04 tạo cảnh báo đối soát (TL-03 L4084); xử lý cảnh báo thuộc task appraisal.
- Điểm lệch cần lưu ý: câu upsert mẫu ở TL-03 "Event-carried State Transfer" dùng cột `assignment_kind`, `is_primary`, còn từ điển `assignment_v` dùng `kind`, `branch`, `is_head`; task theo từ điển dữ liệu và ghi vào báo cáo.

## 4. Ngoài phạm vi

- Phát EVT-14/15/16 và lệnh `org-republish`: DV-04-03 (và task DV-04 liên quan).
- Job đối soát định kỳ ghi `reconcile_run`: DL-12 (task này chỉ tạo bảng).
- Truy vấn "như đã biết tại T" hai trục thời gian: gọi org-service qua Resilience4j trong task cần dùng.

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/resources/db/migration/org_replica/**`
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/replica/**`, cấu hình DataSource `svc_org_replica` trong `backend/mission-core/src/main/resources/application*.yaml`
- `backend/mission-core/src/test/**`

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl mission-core -am verify` chạy xanh: lint migration NEN-030, kiểm thử rò rỉ NEN-034 trên `org_replica`, ArchUnit (module chỉ đọc qua `OrgDirectoryApi`).
- Kiểm thử tích hợp Testcontainers (Kafka, Apicurio, PostgreSQL): sự kiện phiên bản 5 rồi 4 → giữ 5; đổi cha đơn vị → bao đóng đúng; vòng → DLQ; 10.000 sự kiện đo p99 từ lúc phát tới lúc áp dưới 5 giây trong môi trường kiểm thử (báo số đo thật).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
