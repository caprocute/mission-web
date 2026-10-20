package vn.viettel.okrkpi.platform.db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseLayout;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.platform.db.migration.lint.ConventionLinter;

/**
 * Bảng kỹ thuật dùng chung (NEN-032, TL-03 "Cách trình bày và các bảng dùng chung") trên CSDL dữ
 * liệu tenant đã bootstrap đầy đủ: {@code keyring}, {@code modulith} và hai schema nghiệp vụ mẫu
 * {@code task}, {@code appraisal}, với pg_partman 5.1.0 trên PostgreSQL 17.
 */
class SharedTablesIT {

  private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container("td_chung");

  private static final List<String> SCHEMAS = List.of("keyring", "modulith", "task", "appraisal");
  private static final String PWD = "mat-khau-thu-nghiem";
  private static final UUID TENANT_A = UUID.fromString("01927a00-0000-7000-8000-00000000000a");
  private static final UUID TENANT_B = UUID.fromString("01927a00-0000-7000-8000-00000000000b");

  @BeforeAll
  static void prepare() throws SQLException {
    POSTGRES.start();
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles(DatabaseLayout.TENANT_DATA)) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", PWD);
    }
    try (Connection admin = admin()) {
      new DatabaseBootstrap(RoleLimits.load(), env).apply(admin, DatabaseLayout.TENANT_DATA);
    }
    SchemaMigrator migrator = new SchemaMigrator(MigrationOptions.defaults());
    for (String schema : SCHEMAS) {
      migrator.migrate(
          POSTGRES.getJdbcUrl(),
          schema,
          new MigrationCredentials.Credentials(SchemaNames.migrationRole(schema), PWD));
    }
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void moiSchemaDatQuyUocLint() throws SQLException {
    try (Connection admin = admin()) {
      for (String schema : SCHEMAS) {
        assertThat(ConventionLinter.lint(admin, schema)).as(schema).isEmpty();
      }
    }
  }

  @Test
  void bangDungChungChiCoOSchemaNghiepVu() throws SQLException {
    try (Connection admin = admin()) {
      for (String schema : List.of("task", "appraisal")) {
        for (String table :
            List.of(
                "outbox_event", "inbox_processed", "inbox_aggregate_version", "idempotency_key")) {
          assertThat(exists(admin, schema + "." + table)).as(schema + "." + table).isTrue();
        }
      }
      assertThat(exists(admin, "keyring.outbox_event")).isFalse();
      assertThat(exists(admin, "modulith.idempotency_key")).isFalse();
      assertThat(exists(admin, "keyring.dek")).isTrue();
      assertThat(exists(admin, "modulith.event_publication")).isTrue();
      assertThat(
              scalar(
                  admin,
                  "SELECT count(*) FROM partman.part_config WHERE parent_table IN"
                      + " ('task.outbox_event','task.idempotency_key',"
                      + " 'appraisal.outbox_event','appraisal.idempotency_key')"))
          .isEqualTo(4);
      assertThat(
              scalar(
                  admin,
                  "SELECT count(*) FROM partman.part_config WHERE retention = '3 days'"
                      + " AND NOT retention_keep_table AND infinite_time_partitions"
                      + " AND parent_table = 'task.outbox_event'"))
          .isEqualTo(1);
    }
  }

  @Test
  void topicSaiDinhDangBiChan() throws SQLException {
    try (Connection svc = as("svc_task")) {
      assertThatThrownBy(() -> insertOutbox(svc, TENANT_A, "Task.Assigned", "now()"))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
    }
  }

  @Test
  void baoTriGoPhanVungOutboxCuVaTaoTruocPhanVungMoi() throws SQLException {
    String oldPartition;
    // search_path của svc_task là task nên regclass in tên không kèm schema.
    try (Connection svc = as("svc_task")) {
      insertOutbox(svc, TENANT_A, "task.task.assigned.v1", "now() - interval '4 days'");
      oldPartition =
          inTenant(
              svc,
              TENANT_A,
              () ->
                  string(
                      svc,
                      "SELECT tableoid::regclass::text FROM task.outbox_event"
                          + " WHERE created_at < now() - interval '3 days'"));
      assertThat(oldPartition).startsWith("outbox_event_p");
      oldPartition = "task." + oldPartition;
      String partition = oldPartition;
      // svc_task không truy cập thẳng phân vùng con (quyền bị thu hồi), kể cả với ngữ cảnh tenant.
      assertThatThrownBy(
              () -> inTenant(svc, TENANT_A, () -> scalar(svc, "SELECT count(*) FROM " + partition)))
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
      assertThat(scalar(svc, "SELECT task.fn_run_partition_maintenance()")).isEqualTo(2);
      assertThat(
              inTenant(
                  svc,
                  TENANT_A,
                  () ->
                      scalar(
                          svc,
                          "SELECT count(*) FROM task.outbox_event"
                              + " WHERE created_at < now() - interval '3 days'")))
          .isZero();
    }
    try (Connection admin = admin()) {
      assertThat(exists(admin, oldPartition)).isFalse();
      // Còn đủ phân vùng tạo trước cho 7 ngày tới, và mọi phân vùng con đều có RLS + chính sách.
      assertThat(
              scalar(
                  admin,
                  "SELECT count(*) FROM pg_partition_tree('task.outbox_event') t"
                      + " JOIN pg_class c ON c.oid = t.relid WHERE t.level = 1"
                      + " AND c.relname >= 'outbox_event_p' || to_char(now() + interval '7 days', 'YYYYMMDD')"
                      + " AND c.relname <> 'outbox_event_default'"))
          .isGreaterThanOrEqualTo(1);
      assertThat(
              scalar(
                  admin,
                  "SELECT count(*) FROM pg_partition_tree('task.outbox_event') t"
                      + " JOIN pg_class c ON c.oid = t.relid WHERE t.level = 1"
                      + " AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity"
                      + " OR has_table_privilege('svc_task', c.oid, 'SELECT')"
                      + " OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid))"))
          .isZero();
    }
  }

  @Test
  void haiTenantKhongThayInboxVaIdempotencyCuaNhau() throws SQLException {
    UUID eventId = UUID.randomUUID();
    try (Connection svc = as("svc_appraisal")) {
      inTenant(
          svc,
          TENANT_A,
          () -> {
            insertInbox(svc, eventId, TENANT_A, "now()");
            insertIdempotency(svc, TENANT_A, "k-" + eventId, "XONG");
            return 0;
          });
      assertThat(
              inTenant(
                  svc,
                  TENANT_B,
                  () ->
                      scalar(
                          svc,
                          "SELECT (SELECT count(*) FROM appraisal.inbox_processed WHERE event_id = '"
                              + eventId
                              + "') + (SELECT count(*) FROM appraisal.idempotency_key WHERE idem_key = 'k-"
                              + eventId
                              + "')")))
          .isZero();
      assertThat(
              inTenant(
                  svc,
                  TENANT_A,
                  () ->
                      scalar(
                          svc,
                          "SELECT (SELECT count(*) FROM appraisal.inbox_processed WHERE event_id = '"
                              + eventId
                              + "') + (SELECT count(*) FROM appraisal.idempotency_key WHERE idem_key = 'k-"
                              + eventId
                              + "')")))
          .isEqualTo(2);
      // B không ghi hộ được dòng của A (WITH CHECK).
      assertThatThrownBy(
              () ->
                  inTenant(
                      svc,
                      TENANT_B,
                      () -> {
                        insertInbox(svc, UUID.randomUUID(), TENANT_A, "now()");
                        return 0;
                      }))
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
      assertThatThrownBy(
              () ->
                  inTenant(
                      svc,
                      TENANT_A,
                      () -> {
                        insertIdempotency(svc, TENANT_A, "k-sai", "LOI");
                        return 0;
                      }))
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
    }
  }

  @Test
  void donInboxCuTheoLoKhongTheoTenant() throws SQLException {
    UUID oldEvent = UUID.randomUUID();
    UUID newEvent = UUID.randomUUID();
    try (Connection svc = as("svc_task")) {
      inTenant(
          svc,
          TENANT_A,
          () -> {
            insertInbox(svc, oldEvent, TENANT_A, "now() - interval '15 days'");
            insertInbox(svc, newEvent, TENANT_A, "now() - interval '2 days'");
            return 0;
          });
      inTenant(
          svc,
          TENANT_B,
          () -> {
            insertInbox(svc, UUID.randomUUID(), TENANT_B, "now() - interval '20 days'");
            return 0;
          });
      // Không cần ngữ cảnh tenant; xoá dòng cũ của cả hai tenant, giữ dòng 2 ngày.
      assertThat(scalar(svc, "SELECT task.fn_purge_inbox_processed()")).isEqualTo(2);
      assertThat(scalar(svc, "SELECT task.fn_purge_inbox_processed()")).isZero();
      assertThatThrownBy(
              () -> scalar(svc, "SELECT task.fn_purge_inbox_processed(interval '1 hour')"))
          .isInstanceOf(SQLException.class);
      assertThat(
              inTenant(
                  svc,
                  TENANT_A,
                  () ->
                      scalar(
                          svc,
                          "SELECT count(*) FROM task.inbox_processed WHERE event_id IN ('"
                              + oldEvent
                              + "','"
                              + newEvent
                              + "')")))
          .isEqualTo(1);
    }
  }

  @Test
  void quyenCdcChiDocOutbox() throws SQLException {
    try (Connection admin = admin()) {
      assertThat(privilege(admin, "cdc_task", "task.outbox_event", "SELECT")).isTrue();
      assertThat(privilege(admin, "cdc_task", "task.inbox_processed", "SELECT")).isFalse();
      assertThat(privilege(admin, "cdc_task", "task.outbox_event", "INSERT")).isFalse();
      assertThat(privilege(admin, "svc_task", "appraisal.outbox_event", "INSERT")).isFalse();
    }
  }

  @Test
  void modulithLayTenantTuNguCanhVaDonBanGhiHoanTat() throws SQLException {
    UUID done = UUID.randomUUID();
    UUID pending = UUID.randomUUID();
    try (Connection svc = as("svc_okr")) {
      inTenant(
          svc,
          TENANT_A,
          () -> {
            insertPublication(svc, done, "now() - interval '8 days'");
            insertPublication(svc, pending, null);
            return 0;
          });
      assertThat(
              inTenant(
                  svc,
                  TENANT_A,
                  () ->
                      string(
                          svc,
                          "SELECT tenant_id::text FROM modulith.event_publication WHERE id = '"
                              + done
                              + "'")))
          .isEqualTo(TENANT_A.toString());
      assertThat(
              inTenant(
                  svc,
                  TENANT_B,
                  () -> scalar(svc, "SELECT count(*) FROM modulith.event_publication")))
          .isZero();
      // Thiếu ngữ cảnh: tenant_id NULL bị NOT NULL chặn, không bao giờ ghi vô chủ.
      assertThatThrownBy(() -> insertPublication(svc, UUID.randomUUID(), null))
          .isInstanceOf(SQLException.class);
      assertThat(scalar(svc, "SELECT modulith.fn_purge_event_publication()")).isEqualTo(1);
      assertThat(
              inTenant(
                  svc,
                  TENANT_A,
                  () -> scalar(svc, "SELECT count(*) FROM modulith.event_publication")))
          .isEqualTo(1);
    }
  }

  @Test
  void keyringChoSvcChiDocVaChenChiSvcKeyringDoiTrangThai() throws SQLException {
    UUID dek = UUID.randomUUID();
    try (Connection svc = as("svc_task")) {
      inTenant(
          svc,
          TENANT_A,
          () -> {
            try (PreparedStatement ps =
                svc.prepareStatement(
                    "INSERT INTO keyring.dek (id, tenant_id, purpose, valid_month, wrapped_dek,"
                        + " kek_ref) VALUES (?, ?, 'KHIEU_NAI', date '2027-01-01', ?, 'transit/keys/t@v1')")) {
              ps.setObject(1, dek);
              ps.setObject(2, TENANT_A);
              ps.setBytes(3, new byte[] {1, 2, 3});
              ps.executeUpdate();
            }
            return 0;
          });
      assertThatThrownBy(
              () ->
                  inTenant(
                      svc,
                      TENANT_A,
                      () ->
                          scalar(
                              svc,
                              "WITH u AS (UPDATE keyring.dek SET status = 'RETIRED' RETURNING 1) SELECT count(*) FROM u")))
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
      assertThat(inTenant(svc, TENANT_B, () -> scalar(svc, "SELECT count(*) FROM keyring.dek")))
          .isZero();
    }
    try (Connection keyring = as("svc_keyring")) {
      assertThat(
              inTenant(
                  keyring,
                  TENANT_A,
                  () ->
                      scalar(
                          keyring,
                          "WITH u AS (UPDATE keyring.dek SET status = 'DECRYPT_ONLY' WHERE id = '"
                              + dek
                              + "' RETURNING 1) SELECT count(*) FROM u")))
          .isEqualTo(1);
    }
  }

  // ---- tiện ích ----

  @FunctionalInterface
  private interface SqlWork<T> {
    T run() throws SQLException;
  }

  /** Chạy trong giao dịch tường minh có app.tenant_id cục bộ giao dịch (TL-03 L706-734). */
  private static <T> T inTenant(Connection c, UUID tenant, SqlWork<T> work) throws SQLException {
    c.setAutoCommit(false);
    try {
      try (PreparedStatement ps =
          c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        ps.setString(1, tenant.toString());
        ps.execute();
      }
      T result = work.run();
      c.commit();
      return result;
    } catch (SQLException e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
  }

  private static void insertOutbox(Connection c, UUID tenant, String topic, String createdAt)
      throws SQLException {
    inTenant(
        c,
        tenant,
        () -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO task.outbox_event (id, tenant_id, aggregate_type, aggregate_id,"
                      + " aggregate_version, event_type, topic, event_key, payload, schema_version,"
                      + " created_at) VALUES (?, ?, 'Task', ?, 1, 'NhiemVuDaGiao', ?, ?, ?, 1, "
                      + createdAt
                      + ")")) {
            UUID aggregate = UUID.randomUUID();
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            ps.setObject(3, aggregate);
            ps.setString(4, topic);
            ps.setString(5, tenant + ":" + aggregate);
            ps.setBytes(6, new byte[] {0});
            ps.executeUpdate();
          }
          return 0;
        });
  }

  private static void insertInbox(Connection c, UUID eventId, UUID tenant, String processedAt)
      throws SQLException {
    String schema = c.getMetaData().getUserName().substring("svc_".length());
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO "
                + schema
                + ".inbox_processed (event_id, consumer, processed_at, tenant_id, source_topic,"
                + " kafka_partition, kafka_offset) VALUES (?, 'mod-04.score-computed', "
                + processedAt
                + ", ?, 'task.task.assigned.v1', 0, 1)")) {
      ps.setObject(1, eventId);
      ps.setObject(2, tenant);
      ps.executeUpdate();
    }
  }

  private static void insertIdempotency(Connection c, UUID tenant, String key, String status)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO appraisal.idempotency_key (tenant_id, actor_id, idem_key, request_hash,"
                + " status) VALUES (?, ?, ?, ?, ?)")) {
      ps.setObject(1, tenant);
      ps.setObject(2, UUID.randomUUID());
      ps.setString(3, key);
      ps.setBytes(4, new byte[32]);
      ps.setString(5, status);
      ps.executeUpdate();
    }
  }

  private static void insertPublication(Connection c, UUID id, String completedAt)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO modulith.event_publication (id, listener_id, event_type, serialized_event,"
                + " publication_date, completion_date) VALUES (?, 'okr.listener', 'okr.Event', '{}',"
                + " now(), "
                + (completedAt == null ? "NULL" : completedAt)
                + ")")) {
      ps.setObject(1, id);
      ps.executeUpdate();
    }
  }

  private static Connection admin() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection as(String role) throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), role, PWD);
  }

  private static boolean exists(Connection c, String relation) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
      ps.setString(1, relation);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  private static boolean privilege(Connection c, String role, String table, String privilege)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT has_table_privilege(?, ?, ?)")) {
      ps.setString(1, role);
      ps.setString(2, table);
      ps.setString(3, privilege);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  private static long scalar(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static String string(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }
}
