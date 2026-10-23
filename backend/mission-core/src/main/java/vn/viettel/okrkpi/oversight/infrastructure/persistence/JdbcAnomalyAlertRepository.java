package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.oversight.application.port.AnomalyAlertRepository;
import vn.viettel.okrkpi.oversight.domain.alert.AlertRules;
import vn.viettel.okrkpi.oversight.domain.alert.AnomalyAlert;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@code oversight.anomaly_alert} và {@code oversight.alert_action} bằng JDBC trên {@code
 * oversightDataSource}. Cờ chặn minh chứng là hành động {@code TU_DONG} còn {@code is_active}; tra
 * minh chứng bị chặn bằng {@code jsonb_exists_any} trên {@code evidence_chain} (toán tử {@code ?|}
 * trùng ký hiệu tham số JDBC).
 */
public class JdbcAnomalyAlertRepository implements AnomalyAlertRepository {

  private static final TypeReference<List<String>> LIST = new TypeReference<>() {};
  private static final String COLUMNS =
      "id, period_id, period_bucket, signal_code, severity, subject_type, subject_id,"
          + " evidence_chain::text AS evidence_chain, detected_at, source_event_id, model_version,"
          + " status, row_version";

  private static final String RANK = "CASE severity WHEN 'CAO' THEN 0 WHEN 'VUA' THEN 1 ELSE 2 END";
  private static final String ACTION_JOIN =
      "x.period_bucket = a.period_bucket AND x.tenant_id = a.tenant_id AND x.anomaly_alert_id = a.id";

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public JdbcAnomalyAlertRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public void lock(String key) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param(key)
        .query()
        .listOfRows();
  }

  @Override
  public Optional<AnomalyAlert> findByKey(
      TenantId tenant,
      int bucket,
      UUID periodId,
      String signalCode,
      String subjectType,
      UUID subjectId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM oversight.anomaly_alert WHERE period_bucket = ? AND tenant_id = ?"
                + " AND period_id = ? AND signal_code = ? AND subject_type = ? AND subject_id = ?"
                + " ORDER BY detected_at LIMIT 1")
        .params(bucket, tenant.value(), periodId, signalCode, subjectType, subjectId)
        .query(this::map)
        .optional();
  }

  @Override
  public boolean existsBySource(TenantId tenant, int bucket, UUID sourceEventId) {
    return jdbc.sql(
                "SELECT count(*) FROM oversight.anomaly_alert WHERE period_bucket = ? AND tenant_id = ?"
                    + " AND source_event_id = ?")
            .params(bucket, tenant.value(), sourceEventId)
            .query(Long.class)
            .single()
        > 0;
  }

  @Override
  public void insert(TenantId tenant, AnomalyAlert a, UUID actor) {
    jdbc.sql(
            """
            INSERT INTO oversight.anomaly_alert
              (id, tenant_id, created_by, updated_by, period_id, period_bucket, signal_code, severity,
               subject_type, subject_id, evidence_chain, detected_at, source_event_id, model_version,
               status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """)
        .params(
            a.id(),
            tenant.value(),
            actor,
            actor,
            a.periodId(),
            a.periodBucket(),
            a.signalCode(),
            a.severity(),
            a.subjectType(),
            a.subjectId(),
            write(a.evidence()),
            Timestamp.from(a.detectedAt()),
            a.sourceEventId(),
            a.modelVersion(),
            a.status())
        .update();
  }

  @Override
  public void updateEvidence(TenantId tenant, AnomalyAlert a, UUID actor) {
    jdbc.sql(
            "UPDATE oversight.anomaly_alert SET evidence_chain = ?::jsonb, severity = ?,"
                + " updated_at = now(), updated_by = ?, row_version = row_version + 1"
                + " WHERE period_bucket = ? AND tenant_id = ? AND id = ?")
        .params(write(a.evidence()), a.severity(), actor, a.periodBucket(), tenant.value(), a.id())
        .update();
  }

  @Override
  public boolean updateStatus(TenantId tenant, AnomalyAlert a, String status, UUID actor) {
    return jdbc.sql(
                "UPDATE oversight.anomaly_alert SET status = ?, updated_at = now(), updated_by = ?,"
                    + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ?"
                    + " AND id = ? AND row_version = ?")
            .params(status, actor, a.periodBucket(), tenant.value(), a.id(), a.rowVersion())
            .update()
        == 1;
  }

  @Override
  public void insertAction(
      TenantId tenant,
      AnomalyAlert a,
      String action,
      String note,
      boolean active,
      UUID actor,
      Instant at) {
    jdbc.sql(
            """
            INSERT INTO oversight.alert_action
              (id, tenant_id, created_by, updated_by, period_id, period_bucket, anomaly_alert_id, action,
               note, is_active, acted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            Ids.newUuidV7(),
            tenant.value(),
            actor,
            actor,
            a.periodId(),
            a.periodBucket(),
            a.id(),
            action,
            note,
            active,
            Timestamp.from(at))
        .update();
  }

  @Override
  public int deactivateBlocks(TenantId tenant, AnomalyAlert a, UUID actor) {
    return jdbc.sql(
            "UPDATE oversight.alert_action SET is_active = false, updated_at = now(), updated_by = ?,"
                + " row_version = row_version + 1 WHERE period_bucket = ? AND tenant_id = ?"
                + " AND anomaly_alert_id = ? AND is_active")
        .params(actor, a.periodBucket(), tenant.value(), a.id())
        .update();
  }

  @Override
  public List<AnomalyAlert> openUnitAlerts(
      TenantId tenant, int bucket, UUID periodId, String signalCode, Collection<UUID> units) {
    if (units.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM oversight.anomaly_alert WHERE period_bucket = ? AND tenant_id = ?"
                + " AND period_id = ? AND signal_code = ? AND subject_type = 'DON_VI'"
                + " AND status NOT IN ('DONG', 'TU_DONG_DONG') AND subject_id = ANY (?)"
                + " ORDER BY detected_at, id")
        .params(bucket, tenant.value(), periodId, signalCode, units.toArray(UUID[]::new))
        .query(this::map)
        .list();
  }

  @Override
  public Set<UUID> blockedEvidence(TenantId tenant, Collection<UUID> evidenceIds) {
    if (evidenceIds.isEmpty()) {
      return Set.of();
    }
    String[] codes =
        evidenceIds.stream().map(id -> AlertRules.EVIDENCE_PREFIX + id).toArray(String[]::new);
    List<String> chains =
        jdbc.sql(
                """
                SELECT a.evidence_chain::text
                  FROM oversight.alert_action x
                  JOIN oversight.anomaly_alert a
                    ON a.period_bucket = x.period_bucket AND a.tenant_id = x.tenant_id
                   AND a.id = x.anomaly_alert_id
                 WHERE x.tenant_id = ? AND x.is_active AND x.action = 'TU_DONG'
                   AND jsonb_exists_any(a.evidence_chain, ?)
                """)
            .params(tenant.value(), codes)
            .query(String.class)
            .list();
    Set<UUID> wanted = new HashSet<>(evidenceIds);
    Set<UUID> out = new HashSet<>();
    for (String c : chains) {
      for (UUID id : AlertRules.evidenceIds(read(c))) {
        if (wanted.contains(id)) {
          out.add(id);
        }
      }
    }
    return out;
  }

  @Override
  public List<AnomalyAlert> openAlerts(
      TenantId tenant, UUID periodId, String signalCode, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM oversight.anomaly_alert WHERE tenant_id = ? AND period_id = ?"
                + " AND signal_code = ? AND status NOT IN ('DONG', 'TU_DONG_DONG')"
                + " ORDER BY detected_at, id LIMIT ?")
        .params(tenant.value(), periodId, signalCode, limit)
        .query(this::map)
        .list();
  }

  @Override
  public List<AnomalyAlert> pendingHighDigest(TenantId tenant, String digestNote, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM oversight.anomaly_alert a WHERE a.tenant_id = ? AND a.severity = 'CAO'"
                + " AND a.status = 'MOI' AND NOT EXISTS (SELECT 1 FROM oversight.alert_action x"
                + " WHERE x.period_bucket = a.period_bucket AND x.tenant_id = a.tenant_id"
                + " AND x.anomaly_alert_id = a.id AND x.action = 'TU_DONG' AND x.note = ?)"
                + " ORDER BY a.detected_at, a.id LIMIT ?")
        .params(tenant.value(), digestNote, limit)
        .query(this::map)
        .list();
  }

  @Override
  public Optional<AnomalyAlert> find(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM oversight.anomaly_alert WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query(this::map)
        .optional();
  }

  @Override
  public List<AnomalyAlert> page(TenantId tenant, Filter f, After after, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT * FROM (SELECT "
                + COLUMNS
                + ", "
                + RANK
                + " AS rank FROM oversight.anomaly_alert WHERE tenant_id = ?");
    List<Object> params = new ArrayList<>(List.of(tenant.value()));
    if (f.periodId() != null) {
      sql.append(" AND period_id = ?");
      params.add(f.periodId());
    }
    any(sql, params, "signal_code", f.signals());
    any(sql, params, "severity", f.severities());
    any(sql, params, "status", f.statuses());
    sql.append(") a");
    if (after != null) {
      sql.append(" WHERE (a.rank > ? OR (a.rank = ? AND (a.detected_at, a.id) < (?, ?)))");
      params.addAll(
          List.of(after.rank(), after.rank(), Timestamp.from(after.detectedAt()), after.id()));
    }
    sql.append(" ORDER BY a.rank, a.detected_at DESC, a.id DESC LIMIT ?");
    params.add(limit);
    return jdbc.sql(sql.toString()).params(params).query(this::map).list();
  }

  @Override
  public List<Tally> window(TenantId tenant, Instant since, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + ", EXISTS (SELECT 1 FROM oversight.alert_action x WHERE "
                + ACTION_JOIN
                + " AND x.action = 'XAC_NHAN_HOP_LE') AS valid,"
                + " EXISTS (SELECT 1 FROM oversight.alert_action x WHERE "
                + ACTION_JOIN
                + " AND x.action = 'GIAO_KIEM_TRA') AS assigned,"
                + " EXISTS (SELECT 1 FROM oversight.alert_action x WHERE "
                + ACTION_JOIN
                + " AND x.action = 'KET_LUAN' AND x.verdict = 'CO_VI_PHAM') AS violation"
                + " FROM oversight.anomaly_alert a WHERE a.tenant_id = ? AND a.detected_at >= ?"
                + " ORDER BY a.detected_at DESC LIMIT ?")
        .params(tenant.value(), Timestamp.from(since), limit)
        .query(
            (rs, row) ->
                new Tally(
                    map(rs, row),
                    rs.getBoolean("valid"),
                    rs.getBoolean("assigned"),
                    rs.getBoolean("violation")))
        .list();
  }

  @Override
  public List<Action> actions(TenantId tenant, AnomalyAlert a) {
    return jdbc.sql(
            "SELECT id, action, created_by, assigned_to, verdict, note, is_active, acted_at"
                + " FROM oversight.alert_action WHERE period_bucket = ? AND tenant_id = ?"
                + " AND anomaly_alert_id = ? ORDER BY acted_at, id")
        .params(a.periodBucket(), tenant.value(), a.id())
        .query(
            (rs, row) ->
                new Action(
                    rs.getObject("id", UUID.class),
                    rs.getString("action"),
                    rs.getObject("created_by", UUID.class),
                    rs.getObject("assigned_to", UUID.class),
                    rs.getString("verdict"),
                    rs.getString("note"),
                    rs.getBoolean("is_active"),
                    rs.getTimestamp("acted_at").toInstant()))
        .list();
  }

  @Override
  public boolean markViewed(TenantId tenant, AnomalyAlert a, UUID actor) {
    return jdbc.sql(
                "UPDATE oversight.anomaly_alert SET status = 'DANG_XEM', updated_at = now(),"
                    + " updated_by = ? WHERE period_bucket = ? AND tenant_id = ? AND id = ?"
                    + " AND status = 'MOI'")
            .params(actor, a.periodBucket(), tenant.value(), a.id())
            .update()
        == 1;
  }

  @Override
  public void insertAction(
      TenantId tenant,
      AnomalyAlert a,
      String action,
      UUID assignedTo,
      String verdict,
      String note,
      UUID actor,
      Instant at) {
    jdbc.sql(
            """
            INSERT INTO oversight.alert_action
              (id, tenant_id, created_by, updated_by, period_id, period_bucket, anomaly_alert_id, action,
               assigned_to, verdict, note, is_active, acted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?)
            """)
        .params(
            Ids.newUuidV7(),
            tenant.value(),
            actor,
            actor,
            a.periodId(),
            a.periodBucket(),
            a.id(),
            action,
            assignedTo,
            verdict,
            note,
            Timestamp.from(at))
        .update();
  }

  @Override
  public boolean reopenFiled(TenantId tenant, UUID alertId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM oversight.reopen_request WHERE tenant_id = ?"
                + " AND basis_ref = ?)")
        .params(tenant.value(), "CANH_BAO:" + alertId)
        .query(Boolean.class)
        .single();
  }

  private static void any(
      StringBuilder sql, List<Object> params, String column, Collection<String> values) {
    if (values != null && !values.isEmpty()) {
      sql.append(" AND ").append(column).append(" = ANY (?)");
      params.add(values.toArray(String[]::new));
    }
  }

  private AnomalyAlert map(ResultSet rs, int row) throws SQLException {
    return new AnomalyAlert(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getString("signal_code"),
        rs.getString("severity"),
        rs.getString("subject_type"),
        rs.getObject("subject_id", UUID.class),
        read(rs.getString("evidence_chain")),
        rs.getTimestamp("detected_at").toInstant(),
        rs.getObject("source_event_id", UUID.class),
        rs.getString("model_version"),
        rs.getString("status"),
        rs.getLong("row_version"));
  }

  private String write(List<String> evidence) {
    try {
      return json.writeValueAsString(evidence);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<String> read(String text) {
    try {
      return json.readValue(text, LIST);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
