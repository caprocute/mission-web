package vn.viettel.okrkpi.okr.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link OkrReadRepository} trên {@code okrDataSource} (vai trò {@code svc_okr}, RLS theo tenant).
 */
public class JdbcOkrReadRepository implements OkrReadRepository {

  private static final String OBJECTIVE_COLUMNS =
      "o.id, o.period_id, o.period_bucket, o.owner_type, o.owner_unit_id, o.owner_person_id, o.code,"
          + " o.title, o.description, o.parent_objective_id, o.tier, o.status, o.over_limit_reason,"
          + " o.version_no, o.ai_assisted, o.ai_suggestion_id, o.approved_by, o.approved_at,"
          + " o.created_by, o.created_at, o.updated_at, o.row_version";

  /** KR kèm check-in {@code DA_GUI} gần nhất (LATERAL theo chỉ mục của kr_checkin). */
  private static final String KEY_RESULT_SELECT =
      "SELECT k.id, k.period_id, k.period_bucket, k.objective_id, k.code, k.title, k.metric_type,"
          + " k.unit, k.baseline_value, k.target_value, k.current_value, k.direction, k.weight,"
          + " k.due_date, k.verifier_person_id, k.data_source, k.confidence, k.status, k.risk_flag,"
          + " k.checkin_frequency, k.content_source, k.ai_assisted, k.ai_suggestion_id,"
          + " k.formula_text, k.row_version, k.numerator_label, k.denominator_label,"
          + " c.value AS c_value, c.confidence AS c_confidence,"
          + " c.checkin_at AS c_at, c.blocker_note AS c_blocker"
          + " FROM okr.key_result k"
          + " LEFT JOIN LATERAL (SELECT value, confidence, checkin_at, blocker_note"
          + "   FROM okr.kr_checkin ci WHERE ci.period_bucket = k.period_bucket"
          + "   AND ci.tenant_id = k.tenant_id AND ci.key_result_id = k.id AND ci.status = 'DA_GUI'"
          + "   ORDER BY ci.checkin_at DESC LIMIT 1) c ON true";

  private static final String BASIS_COLUMNS =
      "b.id, b.basis_kind, b.doc_number, b.title, b.issued_by_unit_id, b.issued_on,"
          + " b.source_doc_ref, b.parent_basis_id, b.valid_from, b.valid_to";

  private static final String LINK_COLUMNS =
      "id, period_id, from_type, from_id, to_type, to_id, link_kind, contribution_weight, row_version";

  /**
   * BR-02-10 (CORE-01-07): chỉ phiên bản hiệu lực của mục tiêu. Bỏ phiên bản đã có phiên bản mới
   * hơn được duyệt, và bỏ phiên bản điều chỉnh còn nháp hoặc chờ duyệt khi phiên bản cũ đang hiệu
   * lực (đọc được theo mã mục tiêu).
   */
  static final String EFFECTIVE_VERSION =
      " AND NOT EXISTS (SELECT 1 FROM okr.objective v WHERE v.period_bucket = o.period_bucket"
          + " AND v.tenant_id = o.tenant_id AND v.period_id = o.period_id"
          + " AND v.owner_unit_id = o.owner_unit_id AND v.code = o.code AND v.id <> o.id"
          + " AND v.status = 'DA_DUYET' AND (v.version_no > o.version_no"
          + " OR o.status IN ('NHAP', 'CHO_DUYET')))";

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcOkrReadRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public List<ObjectiveRow> objectives(
      TenantId tenant, ObjectiveFilter f, String afterCode, UUID afterId, int limit) {
    MapSqlParameterSource p =
        new MapSqlParameterSource("t", tenant.value()).addValue("limit", limit);
    StringBuilder sql =
        new StringBuilder(
            "SELECT " + OBJECTIVE_COLUMNS + " FROM okr.objective o WHERE o.tenant_id = :t");
    period(sql, p, "o", f.periodId(), f.buckets());
    if (f.unitIds() != null) {
      sql.append(" AND o.owner_unit_id IN (:units)");
      p.addValue("units", f.unitIds());
    }
    if (f.ownerType() != null) {
      sql.append(" AND o.owner_type = :ownerType");
      p.addValue("ownerType", f.ownerType());
    }
    if (f.ownerPersonId() != null) {
      sql.append(" AND o.owner_person_id = :person");
      p.addValue("person", f.ownerPersonId());
    }
    if (f.statuses() != null && !f.statuses().isEmpty()) {
      sql.append(" AND o.status IN (:statuses)");
      p.addValue("statuses", f.statuses());
    }
    like(sql, p, f.query(), "o.code", "o.title");
    sql.append(EFFECTIVE_VERSION);
    if (afterCode != null) {
      sql.append(" AND (o.code, o.id) > (:afterCode, :afterId)");
      p.addValue("afterCode", afterCode).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY o.code, o.id LIMIT :limit");
    return jdbc.query(sql.toString(), p, JdbcOkrReadRepository::objective);
  }

  @Override
  public long countObjectives(TenantId tenant, UUID periodId, List<Integer> buckets, UUID unitId) {
    MapSqlParameterSource p =
        new MapSqlParameterSource("t", tenant.value()).addValue("unit", unitId);
    StringBuilder sql =
        new StringBuilder(
            "SELECT count(*) FROM okr.objective o WHERE o.tenant_id = :t"
                + " AND o.owner_unit_id = :unit AND o.owner_type = 'TAP_THE' AND o.status <> 'DONG'"
                + EFFECTIVE_VERSION);
    period(sql, p, "o", periodId, buckets);
    Long n = jdbc.queryForObject(sql.toString(), p, Long.class);
    return n == null ? 0 : n;
  }

  @Override
  public Optional<ObjectiveRow> objective(TenantId tenant, UUID id) {
    return objectivesByIds(tenant, List.of(id)).stream().findFirst();
  }

  @Override
  public List<ObjectiveRow> objectivesByIds(TenantId tenant, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT "
            + OBJECTIVE_COLUMNS
            + " FROM okr.objective o WHERE o.tenant_id = :t AND o.id IN (:ids)",
        new MapSqlParameterSource("t", tenant.value()).addValue("ids", ids),
        JdbcOkrReadRepository::objective);
  }

  @Override
  public List<KeyResultRow> keyResultsOf(
      TenantId tenant, List<Integer> buckets, Collection<UUID> objectiveIds) {
    if (objectiveIds.isEmpty()) {
      return List.of();
    }
    MapSqlParameterSource p =
        new MapSqlParameterSource("t", tenant.value()).addValue("ids", objectiveIds);
    StringBuilder sql =
        new StringBuilder(
            KEY_RESULT_SELECT + " WHERE k.tenant_id = :t AND k.objective_id IN (:ids)");
    if (buckets != null) {
      sql.append(" AND k.period_bucket IN (:buckets)");
      p.addValue("buckets", buckets);
    }
    sql.append(" ORDER BY k.code, k.id");
    return jdbc.query(sql.toString(), p, JdbcOkrReadRepository::keyResult);
  }

  @Override
  public List<KeyResultRow> keyResults(
      TenantId tenant, KeyResultFilter f, String afterCode, UUID afterId, int limit) {
    MapSqlParameterSource p =
        new MapSqlParameterSource("t", tenant.value()).addValue("limit", limit);
    StringBuilder sql = new StringBuilder(KEY_RESULT_SELECT);
    keyResultWhere(sql, p, f);
    if (afterCode != null) {
      sql.append(" AND (k.code, k.id) > (:afterCode, :afterId)");
      p.addValue("afterCode", afterCode).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY k.code, k.id LIMIT :limit");
    return jdbc.query(sql.toString(), p, JdbcOkrReadRepository::keyResult);
  }

  @Override
  public long countKeyResults(TenantId tenant, KeyResultFilter f) {
    MapSqlParameterSource p = new MapSqlParameterSource("t", tenant.value());
    StringBuilder sql = new StringBuilder("SELECT count(*) FROM (" + KEY_RESULT_SELECT);
    keyResultWhere(sql, p, f);
    sql.append(") x");
    Long n = jdbc.queryForObject(sql.toString(), p, Long.class);
    return n == null ? 0 : n;
  }

  private static void keyResultWhere(
      StringBuilder sql, MapSqlParameterSource p, KeyResultFilter f) {
    sql.append(
        " JOIN okr.objective o ON o.period_bucket = k.period_bucket"
            + " AND o.tenant_id = k.tenant_id AND o.id = k.objective_id WHERE k.tenant_id = :t");
    period(sql, p, "k", f.periodId(), f.buckets());
    if (f.unitIds() != null) {
      sql.append(" AND o.owner_unit_id IN (:units)");
      p.addValue("units", f.unitIds());
    }
    if (f.ownerPersonId() != null) {
      // Chủ sở hữu KR: người sở hữu mục tiêu cá nhân, mục tiêu tập thể thì người soạn (suy luận,
      // cùng quy ước với OkrCheckinRepository.CheckinTarget#owner), cộng KR được giao check-in
      // thay còn hiệu lực theo ngày Việt Nam (CORE-01-12).
      sql.append(
          " AND (o.owner_person_id = :person"
              + " OR (o.owner_person_id IS NULL AND o.created_by = :person)"
              + " OR EXISTS (SELECT 1 FROM okr.kr_checkin_delegate d"
              + " WHERE d.tenant_id = k.tenant_id AND d.key_result_id = k.id"
              + " AND d.delegate_person_id = :person AND d.revoked_at IS NULL"
              + " AND (d.valid_until IS NULL"
              + " OR d.valid_until >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)))");
      p.addValue("person", f.ownerPersonId());
    }
    if (f.objectiveId() != null) {
      sql.append(" AND k.objective_id = :objective");
      p.addValue("objective", f.objectiveId());
    }
    if (f.riskFlag() != null) {
      sql.append(" AND k.risk_flag = :risk");
      p.addValue("risk", f.riskFlag());
    }
    if (f.approvedOnly() || f.needCheckin()) {
      sql.append(" AND o.status = 'DA_DUYET'");
    }
    if (f.needCheckin()) {
      // Chu kỳ check-in theo tần suất của KR (TUAN 7 ngày, HAI_TUAN 14 ngày, THANG 1 tháng; suy
      // luận).
      sql.append(
          " AND (c.checkin_at IS NULL OR c.checkin_at < now() - CASE k.checkin_frequency"
              + " WHEN 'TUAN' THEN interval '7 days' WHEN 'HAI_TUAN' THEN interval '14 days'"
              + " ELSE interval '1 month' END)");
    }
    like(sql, p, f.query(), "k.code", "k.title");
  }

  @Override
  public Optional<KeyResultRow> keyResult(TenantId tenant, UUID id) {
    return keyResultsByIds(tenant, List.of(id)).stream().findFirst();
  }

  @Override
  public List<KeyResultRow> keyResultsByIds(TenantId tenant, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        KEY_RESULT_SELECT + " WHERE k.tenant_id = :t AND k.id IN (:ids) ORDER BY k.code, k.id",
        new MapSqlParameterSource("t", tenant.value()).addValue("ids", ids),
        JdbcOkrReadRepository::keyResult);
  }

  @Override
  public List<ObjectiveBasis> basesOf(
      TenantId tenant, List<Integer> buckets, Collection<UUID> objectiveIds) {
    if (objectiveIds.isEmpty()) {
      return List.of();
    }
    MapSqlParameterSource p =
        new MapSqlParameterSource("t", tenant.value()).addValue("ids", objectiveIds);
    StringBuilder sql =
        new StringBuilder(
            "SELECT ob.objective_id, "
                + BASIS_COLUMNS
                + " FROM okr.objective_basis ob JOIN okr.basis b ON b.id = ob.basis_id"
                + " WHERE ob.tenant_id = :t AND ob.objective_id IN (:ids)");
    if (buckets != null) {
      sql.append(" AND ob.period_bucket IN (:buckets)");
      p.addValue("buckets", buckets);
    }
    sql.append(" ORDER BY b.title, b.id");
    return jdbc.query(
        sql.toString(),
        p,
        (rs, i) -> new ObjectiveBasis(rs.getObject("objective_id", UUID.class), basis(rs, i)));
  }

  @Override
  public List<BasisRow> basesByIds(TenantId tenant, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT "
            + BASIS_COLUMNS
            + " FROM okr.basis b WHERE b.tenant_id = :t AND b.id IN (:ids)"
            + " ORDER BY b.title, b.id",
        new MapSqlParameterSource("t", tenant.value()).addValue("ids", ids),
        JdbcOkrReadRepository::basis);
  }

  @Override
  public List<BasisRow> bases(
      TenantId tenant,
      String kind,
      LocalDate validOn,
      String query,
      String afterTitle,
      UUID afterId,
      int limit) {
    MapSqlParameterSource p =
        new MapSqlParameterSource("t", tenant.value()).addValue("limit", limit);
    StringBuilder sql =
        new StringBuilder("SELECT " + BASIS_COLUMNS + " FROM okr.basis b WHERE b.tenant_id = :t");
    if (kind != null) {
      sql.append(" AND b.basis_kind = :kind");
      p.addValue("kind", kind);
    }
    if (validOn != null) {
      sql.append(
          " AND (b.valid_from IS NULL OR b.valid_from <= :on) AND (b.valid_to IS NULL OR b.valid_to >= :on)");
      p.addValue("on", validOn);
    }
    like(sql, p, query, "b.doc_number", "b.title");
    if (afterTitle != null) {
      sql.append(" AND (b.title, b.id) > (:afterTitle, :afterId)");
      p.addValue("afterTitle", afterTitle).addValue("afterId", afterId);
    }
    sql.append(" ORDER BY b.title, b.id LIMIT :limit");
    return jdbc.query(sql.toString(), p, JdbcOkrReadRepository::basis);
  }

  @Override
  public List<LinkRow> linksTouching(TenantId tenant, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT "
            + LINK_COLUMNS
            + " FROM okr.okr_link WHERE tenant_id = :t AND (from_id IN (:ids) OR to_id IN (:ids))"
            + " ORDER BY id",
        new MapSqlParameterSource("t", tenant.value()).addValue("ids", ids),
        JdbcOkrReadRepository::link);
  }

  @Override
  public List<LinkRow> links(
      TenantId tenant,
      String fromType,
      UUID fromId,
      String toType,
      UUID toId,
      String linkKind,
      UUID periodId,
      UUID afterId,
      int limit) {
    MapSqlParameterSource p =
        new MapSqlParameterSource("t", tenant.value()).addValue("limit", limit);
    StringBuilder sql =
        new StringBuilder("SELECT " + LINK_COLUMNS + " FROM okr.okr_link WHERE tenant_id = :t");
    eq(sql, p, "from_type", fromType);
    eq(sql, p, "from_id", fromId);
    eq(sql, p, "to_type", toType);
    eq(sql, p, "to_id", toId);
    eq(sql, p, "link_kind", linkKind);
    eq(sql, p, "period_id", periodId);
    if (afterId != null) {
      sql.append(" AND id > :afterId");
      p.addValue("afterId", afterId);
    }
    sql.append(" ORDER BY id LIMIT :limit");
    return jdbc.query(sql.toString(), p, JdbcOkrReadRepository::link);
  }

  private static void eq(StringBuilder sql, MapSqlParameterSource p, String column, Object value) {
    if (value != null) {
      sql.append(" AND ").append(column).append(" = :").append(column);
      p.addValue(column, value);
    }
  }

  private static void period(
      StringBuilder sql,
      MapSqlParameterSource p,
      String alias,
      UUID periodId,
      List<Integer> buckets) {
    if (buckets != null) {
      sql.append(" AND ").append(alias).append(".period_bucket IN (:buckets)");
      p.addValue("buckets", buckets);
    }
    if (periodId != null) {
      sql.append(" AND ").append(alias).append(".period_id = :period");
      p.addValue("period", periodId);
    }
  }

  private static void like(
      StringBuilder sql,
      MapSqlParameterSource p,
      String query,
      String codeColumn,
      String titleColumn) {
    if (query == null || query.isBlank()) {
      return;
    }
    // Không phân biệt dấu theo common.yaml Q: so sánh trên dạng bỏ dấu (unaccent không bắt buộc có;
    // dùng translate cho bảng chữ tiếng Việt, suy luận).
    sql.append(" AND (")
        .append(fold(codeColumn))
        .append(" LIKE :q OR ")
        .append(fold(titleColumn))
        .append(" LIKE :q)");
    p.addValue("q", "%" + VietnameseFold.fold(query.strip()) + "%");
  }

  private static String fold(String column) {
    return "translate(lower(coalesce("
        + column
        + ", '')), '"
        + VietnameseFold.FROM
        + "', '"
        + VietnameseFold.TO
        + "')";
  }

  private static ObjectiveRow objective(ResultSet rs, int i) throws SQLException {
    return new ObjectiveRow(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getString("owner_type"),
        rs.getObject("owner_unit_id", UUID.class),
        rs.getObject("owner_person_id", UUID.class),
        rs.getString("code"),
        rs.getString("title"),
        rs.getString("description"),
        rs.getObject("parent_objective_id", UUID.class),
        rs.getInt("tier"),
        rs.getString("status"),
        rs.getString("over_limit_reason"),
        rs.getInt("version_no"),
        rs.getBoolean("ai_assisted"),
        rs.getObject("ai_suggestion_id", UUID.class),
        rs.getObject("approved_by", UUID.class),
        instant(rs, "approved_at"),
        rs.getObject("created_by", UUID.class),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        rs.getLong("row_version"));
  }

  private static KeyResultRow keyResult(ResultSet rs, int i) throws SQLException {
    Instant at = instant(rs, "c_at");
    return new KeyResultRow(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getObject("objective_id", UUID.class),
        rs.getString("code"),
        rs.getString("title"),
        rs.getString("metric_type"),
        rs.getString("unit"),
        rs.getBigDecimal("baseline_value"),
        rs.getBigDecimal("target_value"),
        rs.getBigDecimal("current_value"),
        rs.getString("direction"),
        rs.getBigDecimal("weight"),
        rs.getObject("due_date", LocalDate.class),
        rs.getObject("verifier_person_id", UUID.class),
        rs.getString("data_source"),
        rs.getString("confidence"),
        rs.getString("status"),
        rs.getString("risk_flag"),
        rs.getString("checkin_frequency"),
        rs.getString("content_source"),
        rs.getBoolean("ai_assisted"),
        rs.getObject("ai_suggestion_id", UUID.class),
        rs.getString("formula_text"),
        rs.getLong("row_version"),
        at == null
            ? null
            : new LastCheckin(
                rs.getBigDecimal("c_value"),
                rs.getString("c_confidence"),
                at,
                rs.getString("c_blocker")),
        rs.getString("numerator_label"),
        rs.getString("denominator_label"));
  }

  private static BasisRow basis(ResultSet rs, int i) throws SQLException {
    return new BasisRow(
        rs.getObject("id", UUID.class),
        rs.getString("basis_kind"),
        rs.getString("doc_number"),
        rs.getString("title"),
        rs.getObject("issued_by_unit_id", UUID.class),
        rs.getObject("issued_on", LocalDate.class),
        rs.getString("source_doc_ref"),
        rs.getObject("parent_basis_id", UUID.class),
        rs.getObject("valid_from", LocalDate.class),
        rs.getObject("valid_to", LocalDate.class));
  }

  private static LinkRow link(ResultSet rs, int i) throws SQLException {
    return new LinkRow(
        rs.getObject("id", UUID.class),
        rs.getObject("period_id", UUID.class),
        rs.getString("from_type"),
        rs.getObject("from_id", UUID.class),
        rs.getString("to_type"),
        rs.getObject("to_id", UUID.class),
        rs.getString("link_kind"),
        rs.getBigDecimal("contribution_weight"),
        rs.getLong("row_version"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }
}
