# vt-db-migration – di trú lược đồ theo schema (NEN-030)

Thư viện này đặt nền cho mọi migration PostgreSQL của hệ thống Mission theo TL-03: mỗi schema một bộ Flyway riêng, chạy bằng tài khoản `mig_<schema>`, ứng dụng không bao giờ có quyền DDL, và CI tự chặn migration sai quy ước đặt tên, sai kiểu chuẩn hay thiếu chú thích phân loại cột. Thư viện gồm ba phần: bộ chạy `MigrationRunner`, bộ kiểm phiên bản lúc khởi động `SchemaVersionGuard`, và bộ lint `ConventionLinter` dùng lại qua lớp kiểm thử `MigrationConventionsTest`.

## Quy ước thư mục và đánh số

Migration của một schema nằm trong mô-đun sở hữu schema đó:

| Chủ sở hữu | Thư mục |
|---|---|
| Mô-đun của mission-core (MOD-01..MOD-08, `org_replica`) | `backend/mission-core/src/main/resources/db/migration/<schema>/` |
| Microservice Java | `backend/services/<ten>/src/main/resources/db/migration/<schema>/` |
| Phần dùng chung mọi schema (hàm RLS, hàm phân vùng – NEN-033; outbox, inbox, idempotency – NEN-032) | `db/common/` của `vt-db-migration` |

Mỗi schema có bảng lịch sử `flyway_schema_history` trong chính schema đó. Flyway nạp hai vị trí theo thứ tự `classpath:db/common` rồi `classpath:db/migration/<schema>`, nên hai nơi chung một dãy phiên bản. Để không đụng số, migration dùng chung lấy dải `V0_x__` (`V0_1__outbox_event.sql`, `V0_2__inbox_processed.sql`…) và luôn viết tên schema bằng placeholder `${flyway:defaultSchema}`; migration của từng schema bắt đầu từ `V1__` (suy luận về dải số, TL-03 không quy định). Tên tệp theo mẫu `V<phiên bản>__<mô tả_snake_case>.sql`; Flyway kiểm tên khi chạy (`validateMigrationNaming`).

Migration chỉ được thêm mới, không sửa tệp đã gộp vào main (BACKEND.md mục 3 quy ước 7). Thay đổi phá vỡ theo mô hình mở rộng rồi thu hẹp (expand and contract): bản N thêm cột cho phép NULL và ghi cả hai cột, job nền điền dữ liệu cũ, bản N+1 đọc cột mới, bản N+2 xoá cột cũ. Ràng buộc trên bảng lớn thêm bằng `NOT VALID` rồi `VALIDATE CONSTRAINT` ở migration sau (TL-03 "Flyway và quy trình thay đổi lược đồ").

## Vai trò CSDL theo schema (NEN-031)

Trước khi chạy migration, mỗi CSDL được khởi tạo vai trò và quyền bằng `vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap`, chạy bằng tài khoản quản trị cụm (không qua Flyway của ứng dụng). Bố trí `td` tạo 15 schema của CSDL dữ liệu tenant (`config`, `policy`, `okr`, `task`, `product`, `appraisal`, `dossier`, `oversight`, `org_replica`, `org`, `scoring`, `evidence`, `docgen`, `modulith`, `keyring`), bố trí `cell_shared` tạo `notify`, `integ`.

```bash
PG_ADMIN_USER=... PG_ADMIN_PASSWORD=... java -cp <classpath> \
  vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap --db=jdbc:postgresql://pg-biz-rw/td_hcm --layout=td
java -cp <classpath> vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap \
  --print-sql --database=td_hcm --layout=td      # chỉ in SQL để DBA duyệt
```

Mỗi schema có bốn vai trò theo TL-03 "Không truy cập chéo và quyền tối thiểu". `mig_<schema>` sở hữu schema và mọi đối tượng, chỉ Flyway dùng. `svc_<schema>` là tài khoản ứng dụng, chỉ có DML (không DDL), được đặt `statement_timeout = 5s`, `idle_in_transaction_session_timeout = 10s` và `search_path` cố định. `ro_<schema>` chỉ đọc. `cdc_<schema>` có `REPLICATION` và chỉ đọc `outbox_event` (cấp khi bảng đã tồn tại, nên chạy lại bootstrap sau migration). Mọi vai trò đều `NOINHERIT NOBYPASSRLS NOSUPERUSER NOCREATEDB NOCREATEROLE`; quyền trên đối tượng mới do `mig_<schema>` tạo được cấp tự động bằng `ALTER DEFAULT PRIVILEGES`, và lần chạy lại cấp bù cho đối tượng đã có. CSDL thu hồi `CONNECT` của `PUBLIC` và chỉ cấp cho vai trò thuộc bố trí của nó; schema `public` bị thu hồi mọi quyền. Hàm trong schema chỉ `svc_<schema>` được `EXECUTE` (suy luận theo TL-03 L185 về hàm `SECURITY DEFINER`).

Ngoại lệ theo TL-03 chỉ có ba. Mọi `svc_<mô-đun>` của mission-core (tám mô-đun MOD-01..MOD-08) đọc được `org_replica`, còn chỉ `svc_org_replica` ghi. Mọi `svc_<mô-đun>` của mission-core có DML trên `modulith` (Event Publication Registry). Mọi `svc_` của CSDL dữ liệu tenant chỉ có `SELECT, INSERT` trên `keyring`, riêng `svc_keyring` mới đổi trạng thái DEK. Ngoại lệ ở mức bảng (ví dụ `REVOKE UPDATE, DELETE ON task.task_state_log FROM svc_task`) viết trong migration của bảng đó.

Giới hạn kết nối nằm ở `src/main/resources/db/bootstrap/role-limits.properties`: mặc định `svc` 400, `ro` 20, `cdc` 5, `mig` 5, lấy theo mẫu SQL TL-03 vì chương hiệu năng chỉ định cỡ PgBouncer (suy luận); ghi đè theo schema bằng `svc.<schema>=<số>`.

Mật khẩu không có trong mã hay SQL sinh ra. Production để CloudNativePG (managed roles) và External Secrets cấp; ở dev, kiểm thử, nếu có biến `<VAI_TRO>_PASSWORD` (ví dụ `SVC_TASK_PASSWORD`) thì bootstrap đặt mật khẩu qua `PGConnection.alterUserPassword`, băm SCRAM phía client nên mật khẩu dạng rõ không đi qua nhật ký máy chủ.

Vai trò PostgreSQL là toàn cụm, nên ở cell bridge mọi CSDL `td_<mã>` trên cùng cụm `pg-biz` dùng chung `svc_task`… Cô lập giữa các tỉnh vì vậy dựa vào việc định tuyến tới đúng CSDL (NEN-036) và RLS, không dựa vào vai trò (suy luận; TL-03 không nói rõ vai trò riêng theo CSDL).

## RLS chuẩn và phân vùng theo kỳ (NEN-033)

Thư viện mang sẵn hai migration dùng chung trong `db/common`, được Flyway áp vào mọi schema trước migration riêng của schema đó.

`V0_1__fn_enable_tenant_rls.sql` tạo hàm `<schema>.fn_enable_tenant_rls(regclass)`. Migration của bảng có `tenant_id` gọi `SELECT task.fn_enable_tenant_rls('task.task');` thay vì tự viết chính sách: hàm bật `ENABLE` và `FORCE ROW LEVEL SECURITY` và tạo `rls_<bảng>__tenant FOR ALL TO svc_<schema>, ro_<schema>` với `USING` và `WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)`. Gọi lại không lỗi. Chỉ chủ sở hữu `mig_<schema>` được gọi. Biểu thức có `nullif` theo TL-01 (TL-03 bỏ `nullif`) để chuỗi rỗng không gây lỗi ép kiểu mà cho NULL, tức mặc định từ chối; tên chính sách theo quy ước CSDL của TL-03, không theo `tenant_isolation` của TL-01.

`V0_2__fn_create_period_partitions.sql` tạo hàm `<schema>.fn_create_period_partitions(p_parent regclass, p_period_bucket int, p_hash_partitions int DEFAULT NULL)`, `SECURITY DEFINER`, `search_path` cố định, do `mig_<schema>` sở hữu và chỉ `svc_<schema>` có `EXECUTE`, để mission-core tạo phân vùng khi quản trị tenant tạo kỳ mà không cần quyền DDL (CORE-08-02 gọi hàm này). Hàm chỉ nhận bảng cha của chính schema, phân vùng LIST theo `period_bucket`; kiểm `period_bucket` có dạng năm×10 + quý (0 cho kỳ năm); tạo `<bảng>_p<period_bucket>`, và ở CSDL pool chia tiếp 16 phân vùng con `<bảng>_p<period_bucket>_h00..h15` theo `HASH (tenant_id)`. Trên mọi phân vùng mới, hàm thu hồi quyền trực tiếp của `svc_`, `ro_`, `cdc_` và `PUBLIC`, rồi bật RLS kèm chính sách `rls_<phân vùng>__tenant`, nên truy vấn thẳng vào phân vùng con bị từ chối, và nếu có ai cấp nhầm quyền thì RLS vẫn lọc (bảo vệ hai lớp, TL-03 L704). Gọi lại với cùng tham số không lỗi và trả 0. Không có phân vùng mặc định: chèn dòng của kỳ chưa mở phân vùng lỗi `23514 no partition of relation` ngay.

Mô hình thuê bao của CSDL được ghi bằng tham số mức CSDL `app.tenancy_model` (`DatabaseBootstrap --tenancy=pool`, tức `ALTER DATABASE td_pool SET app.tenancy_model = 'pool'`). Hàm đọc giá trị này từ `pg_db_role_setting` chứ không từ biến phiên, nên vai trò ứng dụng không đổi được; `p_hash_partitions` ghi đè khi cần (0 là không chia băm). Đây là thiết kế tự chọn vì TL-03 hẹn định nghĩa hàm ở chương 14 nhưng không có (suy luận).

Bốn quy tắc bắt buộc cho mã và migration:

1. Ngữ cảnh tenant chỉ được đặt bằng `select set_config('app.tenant_id', ?, true)` (hoặc `SET LOCAL`) trong giao dịch tường minh, kể cả khi chỉ đọc (`@Transactional(readOnly = true)`). PgBouncer chạy chế độ giao dịch, nên `SET` mức phiên sẽ rò sang giao dịch của tenant khác dùng lại kết nối; truy vấn tự động cam kết không có ngữ cảnh và nhận kết quả rỗng.
2. Mọi chỉ mục ở bảng của CSDL pool bắt đầu bằng `tenant_id` (TL-03 "Chỉ mục").
3. Truy vấn bảng theo kỳ luôn kèm `period_bucket` để PostgreSQL loại phân vùng lúc lập kế hoạch; ở pool kèm thêm `tenant_id` thì chỉ chạm một phân vùng con.
4. Vì cả ba mô hình dùng chung một lược đồ và pool chia `HASH (tenant_id)`, khoá chính và ràng buộc duy nhất của bảng theo kỳ phải chứa cả `period_bucket` và `tenant_id`, ví dụ `PRIMARY KEY (period_bucket, tenant_id, id)`; PostgreSQL từ chối khoá duy nhất thiếu cột khoá phân vùng. TL-03 ghi khoá chính `(period_bucket, id)`, chỉ đúng khi không chia băm (điểm lệch, ghi cho NEN-004).

Chủ sở hữu `mig_<schema>` cũng chịu RLS vì `FORCE`, và không có chính sách nào cho nó, nên migration dữ liệu (điền dữ liệu cũ khi expand and contract) không đọc, ghi được bảng có RLS bằng `mig_`; job điền dữ liệu chạy bằng `svc_<schema>` theo từng tenant, đúng như TL-01 yêu cầu cho job nhiều tenant (suy luận).

Cấu hình PgBouncer tham chiếu ở `src/main/resources/pgbouncer/pgbouncer.ini.template`: `pool_mode = transaction`, `max_prepared_statements = 200` (cần PgBouncer từ 1.21, Phụ lục B chọn 1.23), `server_reset_query` rỗng, `server_check_query = select 1`, `default_pool_size = 60`, `max_client_conn = 4000`, và ở bridge một mục `[databases]` cho mỗi `td_<mã>` với `max_db_connections = 30`. NEN-020 (dev), NEN-034 (kiểm thử rò rỉ) và INF-11 (CloudNativePG Pooler) dùng tệp này làm chuẩn.

## Bảng kỹ thuật dùng chung (NEN-032)

Thư mục `db/common-business` chứa migration chỉ áp cho schema nghiệp vụ (mọi schema trừ `modulith` và `keyring`, xem `DatabaseLayout.TECHNICAL_SCHEMAS`): `V0_20` tạo `outbox_event` phân vùng RANGE theo ngày bằng pg_partman (tạo trước 7 ngày, giữ 3 ngày, gỡ hẳn phân vùng cũ, không chỉ mục phụ, `cdc_<schema>` chỉ SELECT); `V0_21` tạo `inbox_processed` (không phân vùng) và `inbox_aggregate_version`, kèm hàm `fn_purge_inbox_processed(p_older_than default 14 ngày, p_batch_size)` xoá một lô mỗi lần gọi; `V0_22` tạo `idempotency_key` phân vùng theo `created_day`, giữ 48 giờ theo HD-02; `V0_23` tạo `fn_run_partition_maintenance()` bảo trì các bảng pg_partman của chính schema. Schema `modulith` có `event_publication` theo lược đồ Spring Modulith 1.4 cộng `tenant_id` (mặc định lấy từ `app.tenant_id`) và hàm `fn_purge_event_publication` (bản ghi hoàn tất quá 7 ngày); schema `keyring` có `dek` và phải được migrate trước mọi schema có cột `_enc`. `db/common` có thêm `V0_3__fn_protect_partitions.sql` để bảo vệ hai lớp mọi phân vùng con do pg_partman tạo.

pg_partman cần quyền quản trị để cài, nên `DatabaseBootstrap` (NEN-031) tạo schema `partman`, `CREATE EXTENSION pg_partman SCHEMA partman` và cấp quyền tối thiểu cho từng `mig_<schema>` nghiệp vụ. Bảo trì không dùng `partman.run_maintenance_proc()` toàn CSDL mà gọi `SELECT <schema>.fn_run_partition_maintenance()` (svc_<schema> có EXECUTE) cho từng schema, vì thủ tục toàn CSDL tạo phân vùng thiếu RLS lớp thứ hai. Hai hàm dọn và hàm bảo trì là `SECURITY DEFINER`; vì `mig_` cũng chịu `FORCE` RLS, bảng inbox và event_publication có thêm chính sách `rls_<bảng>__purge` chỉ cho `mig_` thấy dòng đã cũ hơn một ngày và cấm chèn sửa. Lịch gọi các hàm này là việc của ứng dụng hoặc CronJob.

Kiểm thử dùng `postgres:17-alpine` kèm tệp cài pg_partman 5.1.0 chép vào container (`src/test/resources/pg_partman`, lớp `TestPostgres`); `SharedTablesIT` kiểm lint, gỡ phân vùng outbox cũ sau bảo trì, CHECK topic, cô lập hai tenant trên inbox và idempotency, dọn inbox không theo tenant, quyền `cdc_`, tenant mặc định của `event_publication` và quyền đổi trạng thái DEK.

## Bộ chạy migration

`vn.viettel.okrkpi.platform.db.migration.MigrationRunner` là lớp `main` cho Kubernetes Job di trú (Helm hook `pre-upgrade`, Argo CD sync wave âm – INF-01, INF-02) và cho kiểm thử.

```bash
java -cp <classpath gồm migration> vn.viettel.okrkpi.platform.db.migration.MigrationRunner \
  --schemas=okr,task --db-list=/etc/mission/td-databases.txt --parallel=5
```

Với mỗi CSDL, bộ chạy áp lần lượt từng schema bằng tài khoản `mig_<schema>`; mật khẩu lấy từ biến môi trường `MIG_<SCHEMA>_PASSWORD` hoặc tệp `<MIG_SECRETS_DIR>/mig_<schema>` (Secret do External Secrets Operator đồng bộ từ OpenBao). Schema phải có sẵn và thuộc sở hữu `mig_<schema>` (tạo ở NEN-031); bộ chạy không tạo schema, không bao giờ `clean`. Ở cell bridge, các CSDL được xử lý song song theo nhóm (mặc định 5); một CSDL lỗi không chặn CSDL khác, mã thoát là 1 để chặn khởi động phiên bản mới cho tenant đó (TL-03 L4596). Mã thoát 2 là tham số sai.

## Ứng dụng không chạy DDL: SchemaVersionGuard

Ứng dụng Spring Boot đặt `spring.flyway.enabled=false`, `spring.jpa.hibernate.ddl-auto=validate`, và khai phiên bản tối thiểu của từng schema nó dùng:

```properties
vt.db.schema-guard.schemas[0].name=task
vt.db.schema-guard.schemas[0].min-version=3
```

Khi dựng ngữ cảnh, `SchemaVersionGuardAutoConfiguration` đọc `<schema>.flyway_schema_history`; nếu phiên bản thấp hơn hoặc chưa chạy migration, ngữ cảnh không khởi động được với lỗi `SchemaVersionTooLowException` nêu rõ schema, phiên bản hiện tại và mức cần, nên pod không bao giờ sẵn sàng với lược đồ cũ (TL-03 "Ứng dụng không có quyền DDL"). Tắt bằng `vt.db.schema-guard.enabled=false` (chỉ dùng trong kiểm thử).

## Lint quy ước

Mô-đun có migration thêm một lớp kiểm thử tích hợp kế thừa lớp cơ sở trong test-jar:

```xml
<dependency>
  <groupId>vn.viettel.okrkpi</groupId>
  <artifactId>vt-db-migration</artifactId>
  <version>${project.version}</version>
  <type>test-jar</type>
  <scope>test</scope>
</dependency>
```

```java
class TaskMigrationConventionsIT extends MigrationConventionsTest {
  @Override protected List<String> schemas() { return List.of("task"); }
}
```

Lớp cơ sở áp toàn bộ migration lên PostgreSQL 17 Testcontainers, đọc `pg_catalog` và làm hỏng build khi có vi phạm, mỗi dòng ghi luật, đối tượng (`schema.bảng.cột` hoặc tên ràng buộc) và cách sửa. Các luật:

| Luật | Kiểm | Nguồn TL-03 |
|---|---|---|
| `IDENTIFIER_LOWERCASE` | không định danh nào có chữ hoa (trừ bảng Flowable `ACT_*`, `FLW_*`) | Quy ước đặt tên |
| `NAMING_PRIMARY_KEY` … `NAMING_FUNCTION` | `pk_<bảng>`, `fk_<bảng>__<bảng đích>`, `uq_<bảng>__`, `ck_<bảng>__`, `ex_<bảng>__`, `ix_<bảng>__`, `rls_<bảng>__`, `tg_<bảng>__`, `fn_` | Quy ước đặt tên |
| `TYPE_STANDARD` | kiểu thuộc danh sách chuẩn: `uuid`, `varchar(n)` (bắt buộc có độ dài), `text`, `timestamptz`, `date`, `daterange`, `tstzrange`, `numeric(6,3)`, `numeric(6,4)`, `numeric(18,4)`, `int`, `bigint`, `boolean`, `jsonb`, `bytea`, `vector(1024)` | Kiểu dữ liệu chuẩn |
| `FLAG_COLUMN` | cột `is_`/`has_` là `boolean NOT NULL DEFAULT false` | Quy ước đặt tên |
| `TIMESTAMP_COLUMN` | cột `_at` là `timestamptz` | Quy ước đặt tên |
| `CLASSIFICATION_COMMENT` | mọi cột có `COMMENT` JSON hợp lệ với `cls` thuộc C0..C3 | Phân loại dữ liệu C0 đến C3 |
| `CLASSIFICATION_C2_ENCRYPTED` | cột `"cls":"C2"` là `bytea`, hậu tố `_enc`, bảng có `dek_id` | Phân loại dữ liệu C0 đến C3 |

Phân vùng con, bảng lịch sử Flyway và đối tượng thuộc extension được bỏ qua (đối tượng của phân vùng con do PostgreSQL sinh từ bảng cha). Khi thật sự phải dùng kiểu ngoài danh sách, ghi lý do vào khoá `type_exception` của chú thích cột, ví dụ `'{"cls":"C1","type_exception":"smallint theo định dạng của hệ ngoài"}'`; người duyệt migration đọc lý do này (suy luận về nơi ghi lý do).

Lớp cơ sở còn ghi danh sách cột C2, C3 ra `target/generated-resources/cdc/<schema>-column-exclude-list.txt`, mỗi dòng `schema.bảng.cột`, để DV-15-02 sinh tham số `column.exclude.list` của connector Debezium phân tích; nhờ vậy thêm một cột C2 mới không thể vô tình rò sang ClickHouse.

## CI ba bước (TL-03)

TL-03 yêu cầu kiểm migration trong CI bằng ba bước. Bước 1, áp trên PostgreSQL 17 trống, chính là lớp `MigrationConventionsTest` chạy trong `./mvnw verify`. Bước 2, áp trên ảnh chụp lược đồ của bản đang chạy, chỉ bật khi có bản phát hành đầu tiên: pipeline (INF-05) khôi phục `pg_dump --schema-only` của bản phát hành gần nhất vào Testcontainers rồi chạy `MigrationRunner` (suy luận về cách làm). Bước 3, kiểm chú thích phân loại, RLS và quyền: phần chú thích và quy ước nằm ở lint này; phần RLS và quyền do NEN-031, NEN-034 bổ sung.

## Kiểm thử của thư viện

`./mvnw -pl libs/vt-db-migration -am verify` chạy: kiểm thử đơn vị tham số dòng lệnh và nguồn mật khẩu; `ConventionLinterIT` với mười lăm bộ migration mẫu sai trong `src/test/resources/db/test-bad/`, mỗi bộ làm đúng một luật thất bại; `SampleMigrationConventionsIT` với bộ mẫu đúng (gồm bảng phân vùng và migration dùng chung `V0_1`); `MigrationRunnerIT` với hai CSDL, CSDL lỗi không chặn CSDL kia và mã thoát là 1; `SchemaVersionGuardIT` cho các trường hợp đủ, thiếu phiên bản và chưa chạy migration; `TenantRlsPartitionIT` ở hai CSDL bridge và pool (cô lập hai tenant qua bảng cha, chặn truy cập phân vùng con, thiếu ngữ cảnh thì đọc rỗng ghi lỗi, kỳ chưa mở phân vùng thì lỗi, `EXPLAIN (FORMAT JSON)` chỉ quét một phân vùng); `CommonMigrationConventionsIT` kiểm chính migration dùng chung theo lint; `DatabaseBootstrapIT` dựng 15 schema, chạy bootstrap ba lần (idempotent), kiểm ma trận quyền và in bảng vai trò × schema × quyền ra `target/role-matrix.md`.
