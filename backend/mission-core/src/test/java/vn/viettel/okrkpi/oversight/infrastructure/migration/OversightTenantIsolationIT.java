package vn.viettel.okrkpi.oversight.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;

/**
 * Schema {@code oversight} (CORE-07-01) ở CSDL pool: rò rỉ tenant (RLS) do lớp cơ sở sinh trên bảy
 * bảng, cộng ràng buộc: bốn mắt khi mở lại kỳ (người duyệt khác người yêu cầu, mỗi người một phê
 * duyệt, chỉ thêm), phạm vi mở lại, khử trùng EVT-20 và hành động xử lý cảnh báo theo hợp đồng.
 */
class OversightTenantIsolationIT extends TenantIsolationCatalogTest {

  private static final OffsetDateTime AT = OffsetDateTime.parse("2027-04-10T08:00:00Z");

  @Override
  protected List<String> schemas() {
    return List.of("oversight");
  }

  @Override
  protected Map<String, RowFixture> rowFixtures() {
    Map<String, RowFixture> f = new LinkedHashMap<>();
    f.put(
        "oversight.complaint",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "complaint");
          r.put("complainant_person_id", id(tenant, "person"));
          r.put("profile_id", id(tenant, "profile"));
          r.put("kind", "KHIEU_NAI");
          r.put("target_stage", "QUYET_DINH");
          r.put("subject_line", "Khiếu nại kết quả xếp loại");
          r.put("targets_json", jsonb("[]"));
          r.put("content_enc", new byte[] {1});
          r.put("dek_id", id(tenant, "dek"));
          r.put("filed_at", AT);
          r.put("response_deadline", LocalDate.parse("2027-05-10"));
          r.put("handler_unit_id", id(tenant, "org"));
          return r;
        });
    f.put(
        "oversight.complaint_response",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "complaint_response");
          r.put("complaint_id", id(tenant, "complaint"));
          r.put("record_kind", "YEU_CAU");
          // ck_complaint_response__request (V6): yêu cầu giải trình phải có bên được yêu cầu và hạn.
          r.put("party_kind", "NGUOI");
          r.put("party_id", id(tenant, "party"));
          r.put("due_date", LocalDate.parse("2027-05-05"));
          r.put("responder_id", id(tenant, "responder"));
          r.put("response_enc", new byte[] {2});
          r.put("dek_id", id(tenant, "dek"));
          r.put("responded_at", AT);
          return r;
        });
    f.put(
        "oversight.reopen_request",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "reopen_request");
          r.putAll(requestColumns(tenant, "MLK-2027-" + (100 + seed)));
          return r;
        });
    f.put(
        "oversight.reopen_approval",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "reopen_approval");
          r.put("reopen_request_id", id(tenant, "reopen_request"));
          r.put("approver_id", id(tenant, "approver"));
          r.put("approver_role", "decider");
          r.put("decision", "DONG_Y");
          r.put("mfa_acr", "aal2");
          r.put("decided_at", AT);
          r.put("sign_request_id", id(tenant, "sign"));
          return r;
        });
    f.put(
        "oversight.reopen_progress",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "reopen_progress");
          r.put("reopen_request_id", id(tenant, "reopen_request"));
          r.put("profile_id", id(tenant, "profile-" + seed));
          r.put("forms_json", jsonb("{}"));
          return r;
        });
    f.put(
        "oversight.post_audit",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "post_audit");
          r.put("audit_kind", "DOT_XUAT");
          r.put("target_type", "HO_SO");
          r.put("target_id", id(tenant, "profile"));
          r.put("inspector_id", id(tenant, "inspector"));
          r.put("opened_at", AT);
          return r;
        });
    f.put(
        "oversight.anomaly_alert",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "anomaly_alert");
          r.put("signal_code", "TU_CHAM_CAO");
          r.put("severity", "VUA");
          r.put("subject_type", "CA_NHAN");
          r.put("subject_id", id(tenant, "person"));
          r.put("evidence_chain", jsonb("[]"));
          r.put("detected_at", AT);
          r.put("source_event_id", id(tenant, "evt20-" + seed));
          r.put("model_version", "QT-1.0");
          return r;
        });
    f.put(
        "oversight.alert_action",
        (tenant, seed) -> {
          Map<String, Object> r = periodRow(tenant, "alert_action");
          r.put("anomaly_alert_id", id(tenant, "anomaly_alert"));
          r.put("action", "GHI_CHU");
          r.put("acted_at", AT);
          return r;
        });
    return f;
  }

  @Test
  void pheDuyetMoLaiBonMat() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    UUID approver = UUID.randomUUID();
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      st.execute(requestSql(request, requester, "'TOAN_KY'", "'{}'", "MLK-2027-001"));
      assertThatThrownBy(() -> st.execute(approvalSql(request, requester, "'decider'")))
          .hasMessageContaining("khác người yêu cầu");
      st.execute(approvalSql(request, approver, "'decider'"));
      assertThatThrownBy(() -> st.execute(approvalSql(request, approver, "'second_approver'")))
          .hasMessageContaining("duplicate key")
          .hasMessageContaining("reopen_r");
      assertThatThrownBy(() -> st.execute(approvalSql(request, UUID.randomUUID(), "'owner'")))
          .hasMessageContaining("ck_reopen_approval__role");
      assertThatThrownBy(
              () ->
                  st.execute(
                      "UPDATE oversight.reopen_approval SET decision = 'TU_CHOI'"
                          + " WHERE reopen_request_id = '"
                          + request
                          + "'"))
          .hasMessageContaining("permission denied");
      assertThatThrownBy(
              () ->
                  st.execute(
                      "DELETE FROM oversight.reopen_approval WHERE reopen_request_id = '"
                          + request
                          + "'"))
          .hasMessageContaining("permission denied");
    }
  }

  @Test
  void phamViMoLaiVaSoPheDuyet() throws SQLException {
    UUID t = UUID.randomUUID();
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  st.execute(
                      requestSql(
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          "'DON_VI'",
                          "'{}'",
                          "MLK-2027-002")))
          .hasMessageContaining("ck_reopen_request__scope_ids");
      st.execute(
          requestSql(
              UUID.randomUUID(),
              UUID.randomUUID(),
              "'HO_SO'",
              "ARRAY[gen_random_uuid()]",
              "MLK-2027-003"));
      assertThatThrownBy(
              () ->
                  st.execute(
                      requestSql(
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          "'TOAN_KY'",
                          "'{}'",
                          "MLK-2027-003")))
          .hasMessageContaining("duplicate key")
          .hasMessageContaining("code_key");
      assertThatThrownBy(
              () ->
                  st.execute(
                      "UPDATE oversight.reopen_request SET approvals_required = 1 WHERE code = 'MLK-2027-003'"))
          .hasMessageContaining("ck_reopen_request__approvals");
    }
  }

  @Test
  void canhBaoKhuTrungVaHanhDongTheoHopDong() throws SQLException {
    UUID t = UUID.randomUUID();
    UUID alert = UUID.randomUUID();
    UUID source = UUID.randomUUID();
    try (Connection c = svc(t);
        Statement st = c.createStatement()) {
      st.execute(alertSql(alert, source));
      assertThatThrownBy(() -> st.execute(alertSql(UUID.randomUUID(), source)))
          .hasMessageContaining("duplicate key")
          .hasMessageContaining("source_event");
      st.execute(actionSql(alert, "'KET_LUAN'", "'CO_VI_PHAM'", "false"));
      st.execute(actionSql(alert, "'TU_DONG'", "NULL", "true"));
      assertThatThrownBy(() -> st.execute(actionSql(alert, "'GHI_CHU'", "NULL", "true")))
          .hasMessageContaining("ck_alert_action__active");
      assertThatThrownBy(() -> st.execute(actionSql(alert, "'KET_LUAN'", "NULL", "false")))
          .hasMessageContaining("ck_alert_action__concluded");
      assertThatThrownBy(() -> st.execute(actionSql(alert, "'XOA'", "NULL", "false")))
          .hasMessageContaining("ck_alert_action__action");
    }
  }

  // ---------------------------------------------------------------- hỗ trợ

  private Connection svc(UUID tenant) throws SQLException {
    try (Connection admin =
        DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      ensurePeriodPartitionsCreated(jdbcUrl(), admin, schemas());
    }
    Connection c = DriverManager.getConnection(jdbcUrl(), "svc_oversight", TEST_PASSWORD);
    try (Statement st = c.createStatement()) {
      st.execute("SET app.tenant_id = '" + tenant + "'");
    }
    return c;
  }

  private static String common(UUID id) {
    return "'"
        + id
        + "', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), "
        + SAMPLE_PERIOD_BUCKET;
  }

  private static String requestSql(UUID id, UUID requester, String scope, String ids, String code) {
    return "INSERT INTO oversight.reopen_request (id, created_by, updated_by, period_id, period_bucket,"
        + " code, scope, scope_ids, reason, basis_ref, audit_kind, requested_by, open_until) VALUES ("
        + common(id)
        + ", '"
        + code
        + "', "
        + scope
        + ", "
        + ids
        + ", 'Kết luận kiểm tra', 'KL-01/2027', 'DOT_XUAT', '"
        + requester
        + "', now() + interval '7 days')";
  }

  private static String approvalSql(UUID request, UUID approver, String role) {
    return "INSERT INTO oversight.reopen_approval (id, created_by, updated_by, period_id,"
        + " period_bucket, reopen_request_id, approver_id, approver_role, decision, mfa_acr,"
        + " decided_at, sign_request_id) VALUES ("
        + common(UUID.randomUUID())
        + ", '"
        + request
        + "', '"
        + approver
        + "', "
        + role
        + ", 'DONG_Y', 'aal2', now(), gen_random_uuid())";
  }

  private static String alertSql(UUID id, UUID source) {
    return "INSERT INTO oversight.anomaly_alert (id, created_by, updated_by, period_id, period_bucket,"
        + " signal_code, severity, subject_type, subject_id, evidence_chain, detected_at,"
        + " source_event_id, model_version) VALUES ("
        + common(id)
        + ", 'CAO_BANG', 'CAO', 'DON_VI', gen_random_uuid(), '[]', now(), '"
        + source
        + "', 'QT-1.0')";
  }

  private static String actionSql(UUID alert, String action, String verdict, String active) {
    return "INSERT INTO oversight.alert_action (id, created_by, updated_by, period_id, period_bucket,"
        + " anomaly_alert_id, action, verdict, is_active, acted_at) VALUES ("
        + common(UUID.randomUUID())
        + ", '"
        + alert
        + "', "
        + action
        + ", "
        + verdict
        + ", "
        + active
        + ", now())";
  }

  private static Map<String, Object> requestColumns(UUID tenant, String code) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("code", code);
    r.put("scope", "TOAN_KY");
    r.put("reason", "Kết luận kiểm tra");
    r.put("basis_ref", "KL-01/2027");
    r.put("audit_kind", "DOT_XUAT");
    r.put("requested_by", id(tenant, "requester"));
    r.put("open_until", AT.plusDays(7));
    return r;
  }

  private static Map<String, Object> periodRow(UUID tenant, String table) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("id", id(tenant, table));
    r.put("created_by", id(tenant, "user"));
    r.put("updated_by", id(tenant, "user"));
    r.put("period_bucket", SAMPLE_PERIOD_BUCKET);
    r.put("period_id", id(tenant, "period"));
    return r;
  }

  private static PGobject jsonb(String value) {
    try {
      PGobject o = new PGobject();
      o.setType("jsonb");
      o.setValue(value);
      return o;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static UUID id(UUID tenant, String name) {
    return UUID.nameUUIDFromBytes((tenant + "/" + name).getBytes(StandardCharsets.UTF_8));
  }
}
