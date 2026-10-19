# NEN-031 · TL-03 Vai trò CSDL và quyền mặc định theo schema (`mig_`, `svc_`, `ro_`, `cdc_`) cùng các ngoại lệ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-031` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-030 |
| Truy vết | ADR-02, ADR-22 → TL-03 "Không truy cập chéo và quyền tối thiểu", "Bố trí theo mô hình silo/bridge/pool" → 15 schema của CSDL `td_<mã>` (`config`, `policy`, `okr`, `task`, `product`, `appraisal`, `dossier`, `oversight`, `org_replica`, `org`, `scoring`, `evidence`, `docgen`, `modulith`, `keyring`) và 2 schema `cell_shared` (`notify`, `integ`) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Viết bộ tạo vai trò CSDL tham số hoá theo schema đúng TL-03: bốn vai trò mỗi schema, quyền mặc định để mọi đối tượng do `mig_<schema>` tạo ra tự cấp đúng quyền cho `svc_` và `ro_`, không vai trò của module khác có USAGE, không vai trò nào vượt RLS, cùng các ngoại lệ đã ghi trong TL-03. Bộ này chạy bằng tài khoản quản trị cụm (không qua Flyway của ứng dụng) và được kiểm bằng kiểm thử quyền tự động, để nguyên tắc "không truy cập chéo" không phụ thuộc vào sự cẩn thận của từng người.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Không truy cập chéo và quyền tối thiểu" (L149–179, SQL mẫu L153–177), "Ứng dụng không có quyền DDL" (L181–185), "Bố trí theo mô hình silo" (L645–657), "bridge" (L659–663), "pool" (L665–682), bảng `org_replica` (L1330–1403, ngoại lệ `svc_org_replica`), `modulith.event_publication` và `keyring.dek` (L894–927), "Kết nối và PgBouncer" (L4570–4574).
- `/mnt/project-files/kien-truc-okr-kpi/src/_ghi-chu-TL-03.md` L7, L11 (danh sách vai trò, bố trí CSDL).
- Kết quả NEN-030 (`vt-db-migration`).

## 3. Phạm vi công việc

- Viết `backend/libs/vt-db-migration/src/main/resources/db/bootstrap/create-schema-roles.sql` (hoặc thủ tục tham số hoá) tạo cho một schema: `mig_<schema>` (sở hữu, `NOINHERIT`), `svc_<schema>` (`NOINHERIT NOBYPASSRLS`, `CONNECTION LIMIT` tham số), `ro_<schema>` (chỉ đọc, `NOBYPASSRLS`), `cdc_<schema>` (`REPLICATION`, chỉ SELECT `outbox_event` và bảng khai báo CDC); `CREATE SCHEMA ... AUTHORIZATION mig_<schema>`, `REVOKE ALL ... FROM PUBLIC`, `ALTER DEFAULT PRIVILEGES FOR ROLE mig_<schema>` cấp DML cho `svc_`, SELECT cho `ro_`.
- Đặt tham số vai trò ứng dụng: `statement_timeout = '5s'`, `idle_in_transaction_session_timeout = '10s'` cho `svc_<schema>` (TL-03 L4570–4574 và "Đặt ngữ cảnh tenant"), `search_path` cố định.
- Ngoại lệ theo TL-03: `svc_org_replica` là vai trò duy nhất ghi `org_replica`, mọi `svc_<module>` của mission-core có SELECT trên `org_replica`; mọi `svc_<module>` có DML trên `modulith.event_publication`; `svc_<module>` chỉ SELECT, INSERT trên `keyring.dek`, `svc_keyring` mới đổi trạng thái DEK.
- Bộ áp toàn phần `bootstrap-database.sh` (hoặc lớp Java trong `MigrationRunner`) cho một CSDL `td_<mã>` (15 schema) và `cell_shared` (2 schema), idempotent; mật khẩu không nằm trong script mà do CloudNativePG/External Secrets cấp (production) hoặc biến môi trường (dev, kiểm thử).
- Số `CONNECTION LIMIT` từng vai trò lấy từ TL-03 chương "Hiệu năng và mở rộng" nếu có; chỗ TL không ghi thì dùng giá trị mẫu L153–177 (ví dụ 400 cho `svc_task`, 20 cho `ro_`, 5 cho `cdc_`) và ghi "(suy luận)" trong tệp tham số.
- Kiểm thử quyền Testcontainers: sau bootstrap, `svc_task` không tạo được bảng, không đọc được `appraisal`, không đổi được trạng thái `keyring.dek`; `ro_okr` không ghi được; `rolbypassrls = false` cho mọi vai trò trừ vai trò sao lưu nếu có; đối tượng mới do `mig_task` tạo tự có quyền cho `svc_task`.
- Điểm lệch cần lưu ý: hạ tầng dev hiện dùng một role trùng tên schema (`infra/dev/postgres/init/01-schemas.sh`) và một CSDL `mission`; task không sửa dev (NEN-020 dùng lại bộ này), chỉ ghi khác biệt vào báo cáo.

## 4. Ngoài phạm vi

- Áp vào hạ tầng dev: NEN-020; cụm CloudNativePG production và xoay mật khẩu OpenBao: INF-11, INF-13.
- Vai trò của CSDL riêng `flowable`, `ledger`, `iam`, `esign`, `ai`: các task DV-06-01, DV-09-01, DV-10-01, DV-14-xx dùng lại mẫu này.

## 5. Tệp được phép sửa

- `backend/libs/vt-db-migration/**` (thư mục `db/bootstrap`, mã chạy, kiểm thử)
- `wiki/17-thiet-ke-csdl-va-da-khach-thue.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-db-migration -am verify` chạy xanh với ma trận kiểm thử quyền ở mục 3 (in bảng vai trò × schema × quyền vào báo cáo).
- Chạy bootstrap hai lần liên tiếp không lỗi (idempotent).
- Không có mật khẩu trong tệp; `grep -ni "password '" ` trên thư mục chỉ thấy placeholder.
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 17 (nếu đã có) mục vai trò CSDL.

## 7. Báo cáo

Theo AGENTS.md mục 6.
