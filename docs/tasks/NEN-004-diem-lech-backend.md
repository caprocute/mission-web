# NEN-004 · YC-TK-10 Ghi điểm lệch và câu hỏi mở phía máy chủ vào wiki trang 14, lập đề xuất chốt

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-004` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | S |
| Phụ thuộc | NEN-001 (nên làm sau hoặc song song NEN-002, NEN-003 để trỏ được sang trang 16–20) |
| Truy vết | YC-TK-10 → BS-02 HD-01..HD-07 → TL-01, TL-03, TL-04, TL-05 và các tệp `_ghi-chu-TL-0*.md` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Gom vào `wiki/14-quyet-dinh-va-cau-hoi-mo.md` mọi điểm lệch giữa các tài liệu thiết kế có ảnh hưởng tới mã máy chủ, CSDL và hạ tầng, mỗi điểm kèm một đề xuất chốt có lý do, để người phụ trách quyết một lần thay vì từng agent tự chọn. Khi xong, trang 14 có mục "Điểm lệch phía máy chủ" và "Câu hỏi mở phía máy chủ" dạng bảng; các task NEN, CORE, DV trỏ vào đúng dòng thay vì tự suy luận lại.

## 2. Đầu vào bắt buộc đọc

- `wiki/14-quyet-dinh-va-cau-hoi-mo.md` (toàn trang, nhất là "Điểm lệch giữa các nguồn", "Điểm chốt khi viết hợp đồng OpenAPI", "Câu hỏi mở cần người phụ trách trả lời").
- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-02-hop-dong-API-truoc.md` bảng HD-01..HD-07 (L50–60) và danh sách sửa TL chờ phát hành (L166–168); `BS-01-giao-dien-cau-hinh-quy-trinh-Flowable.md`.
- `/mnt/project-files/kien-truc-okr-kpi/src/_ghi-chu-TL-01.md`, `_ghi-chu-TL-02a.md`, `_ghi-chu-TL-02b.md`, `_ghi-chu-TL-03.md`, `_ghi-chu-TL-04.md`, `_ghi-chu-TL-05.md`.
- Các mục TL được dẫn trong bảng điểm lệch dưới đây (đọc đúng dòng để trích chính xác).

## 3. Phạm vi công việc

- Thêm mục "Điểm lệch phía máy chủ" vào trang 14, mỗi dòng gồm: chủ đề, nguồn A nói gì (tệp + dòng), nguồn B nói gì, đề xuất chốt, lý do, task bị ảnh hưởng. Tối thiểu phải có các điểm sau, kiểm lại từng dòng trên TL trước khi ghi:
  - Xung đột `If-Match`: TL-01 "Idempotency" L2880 trả 412, baseline mục 13 và HD-01 chốt 409 (thiếu header 428) – ghi là đã chốt.
  - Thời hạn `Idempotency-Key`: TL-01 L2876 24 giờ, TL-03 `idempotency_key` 48 giờ, HD-02 chốt 48 giờ – đã chốt.
  - DLQ: TL-01 L1246 `<topic>.dlq` so với TL-01 L1281 và TL-03 "Inbox idempotent" `<topic>.dlq.<consumer>`, kèm `<topic>.retry.<consumer>`.
  - Topic kỹ thuật `iam.auth.event.v1` không theo tiền tố `sys.`; `sys.evidence.object.stored.v1` và `evidence.object.stored.v1` cùng xuất hiện.
  - Connector Debezium outbox một cho mỗi schema (TL-01, `_ghi-chu-TL-01` L15) hay một cho mỗi CSDL (TL-03 L4253).
  - Mức tương thích Apicurio `BACKWARD` (TL-01 "Phong bì sự kiện", `docs/quy-dinh/BACKEND.md`) hay `BACKWARD_TRANSITIVE` (TL-03 "Phong bì sự kiện").
  - Cột `outbox_event`: TL-01 "Outbox" (`aggregate_key`, `schema_id`, `occurred_at`) khác TL-03 L787–819 (`aggregate_id`, `aggregate_version`, `event_key`, `schema_version`, `created_at`).
  - Tên claim tenant trong JWT: TL-01 `tenant_id` (`okrkpi.tenant.claim`), TL-03 L758 `tid`, realm dev `infra/dev/keycloak/realm-mission.json` dùng `tenant`.
  - Tên chính sách RLS `rls_<bảng>__tenant` (TL-03) so với `tenant_isolation` và `nullif(...)` (TL-01 "Lan truyền ngữ cảnh tenant từ JWT tới RLS").
  - Năm cụm PostgreSQL (TL-03 L631–643) so với sáu cụm (TL-04, `_ghi-chu-TL-04` L12–26) và một CSDL `mission` ở dev; CSDL theo tenant `td_<mã>` so với dev.
  - Cửa sổ step-up: hợp đồng `common.yaml` và TL-01 ghi `auth_time` ≤ 5 phút, BR-00-02 và tham số Policy Pack `mfa_stepup_window_minutes` ghi 15 phút.
  - URI `type` của lỗi: TL-01 L2904–2921 `https://okrkpi.vn/problems/<slug>` (hợp đồng dùng) so với TL-02a "Thông báo, xác nhận và lỗi" `https://okr.viettel.vn/errors/BR-xx-yy`.
  - Khoá Valkey: TL-01 "Bộ đệm phân tán Valkey" `{<tenant>}:<module>:...` (thẻ băm theo tenant) so với TL-03 "Quy ước khoá" `t:<tenant>:<module>:...` (không đặt thẻ băm trên tenant).
  - Tên bảng: TL-01 `org.position_assignment`, `task.assignment` so với baseline và TL-03 `org.assignment`, `task.task_assignee`.
  - Vai trò trong token: TL-01 Phụ lục C (`staff`, `org_officer`, `policy_approver`) so với baseline mục 10 và HD-05; HD-07 `actor_role` dùng `staff`.
  - Phiên bản Formly (TL-01 Phụ lục B 6.x, AGENTS.md chốt 10) và vai trò `wf_template_author` (BS-01) chưa có trong `docs/phan-quyen/ma-tran.json`.
- Thêm mục "Câu hỏi mở phía máy chủ" gồm những chỗ nguồn thiếu: DDL `period_state` cho `okr`/`product`, định nghĩa đầy đủ `fn_create_period_partitions`, số `CONNECTION LIMIT` từng vai trò, cột trùng nghĩa trong `config`, `okr`, `task` (TL-03 phần 1 mục câu hỏi mở), lược đồ lưu trữ tác vụ dài `/jobs/{id}` không có bảng riêng trong TL-03.
- Với mỗi điểm, đề xuất chốt theo nguyên tắc: hợp đồng OpenAPI và quyết định HD thắng với API; TL-03 thắng với chi tiết CSDL; TL mới hơn thắng khi cùng mức chi tiết; ghi "(suy luận)" cho đề xuất không có nguồn trực tiếp.
- Đánh dấu rõ dòng nào "đã chốt" (có HD hoặc AGENTS.md) và dòng nào "chờ người phụ trách", không tự chuyển dòng chờ sang đã chốt.
- Thêm vào trang 14 liên kết sang trang 16–20 (nếu NEN-002, NEN-003 đã gộp) và cập nhật dòng "Nguồn" của trang.

## 4. Ngoài phạm vi

- Sửa TL gốc trong `/mnt/project-files/` (do người phụ trách phát hành).
- Sửa mã hay hợp đồng theo đề xuất: từng task liên quan làm sau khi người phụ trách chốt.

## 5. Tệp được phép sửa

- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- Mỗi dòng điểm lệch có ít nhất hai trích dẫn tệp + dòng kiểm chứng được bằng `sed -n '<dòng>p'`; báo cáo liệt kê lệnh đã dùng để kiểm ngẫu nhiên tối thiểu năm dòng.
- Không xoá dấu vết các điểm lệch phía web đã có; nội dung cũ giữ nguyên, chỉ thêm mục mới.
- Không có bí mật, địa chỉ máy chủ trong trang; giữ nguyên mã định danh.
- Báo cáo cuối task theo AGENTS.md mục 6, kèm danh sách các dòng "chờ người phụ trách" để thread điều phối gửi người dùng chốt.

## 7. Báo cáo

Theo AGENTS.md mục 6.
