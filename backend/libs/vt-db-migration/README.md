# vt-db-migration – di trú lược đồ theo schema (NEN-030)

Thư viện này đặt nền cho mọi migration PostgreSQL của hệ thống Mission theo TL-03: mỗi schema một bộ Flyway riêng, chạy bằng tài khoản `mig_<schema>`, ứng dụng không bao giờ có quyền DDL, và CI tự chặn migration sai quy ước đặt tên, sai kiểu chuẩn hay thiếu chú thích phân loại cột. Thư viện gồm ba phần: bộ chạy `MigrationRunner`, bộ kiểm phiên bản lúc khởi động `SchemaVersionGuard`, và bộ lint `ConventionLinter` dùng lại qua lớp kiểm thử `MigrationConventionsTest`.

## Quy ước thư mục và đánh số

Migration của một schema nằm trong mô-đun sở hữu schema đó:

| Chủ sở hữu | Thư mục |
|---|---|
| Mô-đun của mission-core (MOD-01..MOD-08, `org_replica`) | `backend/mission-core/src/main/resources/db/migration/<schema>/` |
| Microservice Java | `backend/services/<ten>/src/main/resources/db/migration/<schema>/` |
| Phần dùng chung mọi schema (outbox, inbox, idempotency – NEN-032) | `db/common/` trong thư viện cung cấp nó |

Mỗi schema có bảng lịch sử `flyway_schema_history` trong chính schema đó. Flyway nạp hai vị trí theo thứ tự `classpath:db/common` rồi `classpath:db/migration/<schema>`, nên hai nơi chung một dãy phiên bản. Để không đụng số, migration dùng chung lấy dải `V0_x__` (`V0_1__outbox_event.sql`, `V0_2__inbox_processed.sql`…) và luôn viết tên schema bằng placeholder `${flyway:defaultSchema}`; migration của từng schema bắt đầu từ `V1__` (suy luận về dải số, TL-03 không quy định). Tên tệp theo mẫu `V<phiên bản>__<mô tả_snake_case>.sql`; Flyway kiểm tên khi chạy (`validateMigrationNaming`).

Migration chỉ được thêm mới, không sửa tệp đã gộp vào main (BACKEND.md mục 3 quy ước 7). Thay đổi phá vỡ theo mô hình mở rộng rồi thu hẹp (expand and contract): bản N thêm cột cho phép NULL và ghi cả hai cột, job nền điền dữ liệu cũ, bản N+1 đọc cột mới, bản N+2 xoá cột cũ. Ràng buộc trên bảng lớn thêm bằng `NOT VALID` rồi `VALIDATE CONSTRAINT` ở migration sau (TL-03 "Flyway và quy trình thay đổi lược đồ").

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

`./mvnw -pl libs/vt-db-migration -am verify` chạy: kiểm thử đơn vị tham số dòng lệnh và nguồn mật khẩu; `ConventionLinterIT` với mười lăm bộ migration mẫu sai trong `src/test/resources/db/test-bad/`, mỗi bộ làm đúng một luật thất bại; `SampleMigrationConventionsIT` với bộ mẫu đúng (gồm bảng phân vùng và migration dùng chung `V0_1`); `MigrationRunnerIT` với hai CSDL, CSDL lỗi không chặn CSDL kia và mã thoát là 1; `SchemaVersionGuardIT` cho các trường hợp đủ, thiếu phiên bản và chưa chạy migration.
