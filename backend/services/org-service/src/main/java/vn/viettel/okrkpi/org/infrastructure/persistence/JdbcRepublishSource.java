package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.port.RepublishSource;
import vn.vtokr.org.v1.DonViDaThayDoi;
import vn.vtokr.org.v1.NhanSuDaThayDoi;
import vn.vtokr.org.v1.PhanCongDaThayDoi;

/**
 * DV-04-08 · Đọc ảnh chụp hai trục thời gian cho {@code org-republish}. Đọc theo luồng ({@code fetchSize}) trong
 * giao dịch chỉ đọc đang mở nên không nạp cả tenant vào bộ nhớ. {@code sourceVersion} là {@code max(row_version)} của
 * thực thể trong các dòng đã ghi nhận đến {@code asOf}, trùng cách sự kiện thường tính (UnitSupport).
 */
@Repository
public class JdbcRepublishSource implements RepublishSource {

  static final int FETCH_SIZE = 2_000;

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcRepublishSource(DataSource dataSource) {
    JdbcTemplate template = new JdbcTemplate(dataSource);
    template.setFetchSize(FETCH_SIZE);
    this.jdbc = new NamedParameterJdbcTemplate(template);
  }

  private static final String UNITS = """
      WITH v AS (
        SELECT u.*, max(u.row_version) OVER (PARTITION BY u.id) AS sv
        FROM org.org_unit u
        WHERE u.tenant_id = :tenant AND u.recorded_from <= :asOf)
      SELECT v.version_id, v.id, v.code, v.name, v.short_name, v.unit_type, v.subject_group_id,
        v.is_collective_subject, v.flows, v.level, v.change_order_id, v.valid_from,
        nullif(v.valid_to, 'infinity'::date) AS valid_to, v.sv,
        (SELECT r.parent_unit_id FROM org.org_unit_relation r
          WHERE r.tenant_id = v.tenant_id AND r.tree = 'DANG' AND r.child_unit_id = v.id
            AND r.recorded_period @> CAST(:asOf AS timestamptz) AND r.valid_period @> %1$s LIMIT 1) AS party_parent,
        (SELECT r.parent_unit_id FROM org.org_unit_relation r
          WHERE r.tenant_id = v.tenant_id AND r.tree = 'CHUYEN_MON' AND r.child_unit_id = v.id
            AND r.recorded_period @> CAST(:asOf AS timestamptz) AND r.valid_period @> %1$s LIMIT 1) AS admin_parent
      FROM v
      WHERE v.recorded_period @> CAST(:asOf AS timestamptz)
      ORDER BY v.id, v.valid_from
      """.formatted("(CASE WHEN v.valid_to = 'infinity'::date THEN greatest(v.valid_from, CAST(:day AS date)) "
      + "ELSE v.valid_from END)");

  private static final String PERSONS = """
      WITH v AS (
        SELECT p.id, p.full_name, p.is_party_member, p.employment_status, p.valid_from, p.valid_period,
          p.recorded_period, max(p.row_version) OVER (PARTITION BY p.id) AS sv
        FROM org.person p
        WHERE p.tenant_id = :tenant AND p.recorded_from <= :asOf)
      SELECT DISTINCT ON (v.id) v.id, v.full_name, v.is_party_member, v.employment_status, v.sv
      FROM v
      WHERE v.recorded_period @> CAST(:asOf AS timestamptz)
      ORDER BY v.id, (v.valid_period @> CAST(:day AS date)) DESC, v.valid_from DESC
      """;

  private static final String ASSIGNMENTS = """
      WITH v AS (
        SELECT a.*, max(a.row_version) OVER (PARTITION BY a.id) AS sv
        FROM org.assignment a
        WHERE a.tenant_id = :tenant AND a.recorded_from <= :asOf)
      SELECT v.id, v.person_id, v.org_unit_id, v.position_id, v.job_title_id, jt.name AS job_title_name, v.kind,
        v.is_head, v.valid_from, nullif(v.valid_to, 'infinity'::date) AS valid_to, v.sv
      FROM v
      LEFT JOIN org.job_title jt ON jt.tenant_id = v.tenant_id AND jt.id = v.job_title_id
      WHERE v.recorded_period @> CAST(:asOf AS timestamptz)
      ORDER BY v.id, v.valid_from
      """;

  @Override
  public void units(Instant asOf, LocalDate asOfDate, Consumer<DonViDaThayDoi> sink) {
    jdbc.query(UNITS, params(asOf, asOfDate), (ResultSet rs) -> {
      long sv = rs.getLong("sv");
      sink.accept(DonViDaThayDoi.newBuilder()
          .setUnitId(Sql.uuid(rs, "id"))
          .setVersionId(Sql.uuid(rs, "version_id"))
          .setSourceVersion(sv)
          .setCode(rs.getString("code"))
          .setName(rs.getString("name"))
          .setShortName(rs.getString("short_name"))
          .setUnitType(rs.getString("unit_type"))
          .setSubjectGroupId(Sql.uuid(rs, "subject_group_id"))
          .setIsCollectiveSubject(rs.getBoolean("is_collective_subject"))
          .setFlows(Sql.strings(rs, "flows"))
          .setLevel(rs.getString("level"))
          .setValidFrom(Sql.date(rs, "valid_from"))
          .setValidTo(Sql.date(rs, "valid_to"))
          .setChangeOrderId(Sql.uuid(rs, "change_order_id"))
          .setAggregateVersion(Math.toIntExact(sv))
          .setPartyParentId(Sql.uuid(rs, "party_parent"))
          .setAdminParentId(Sql.uuid(rs, "admin_parent"))
          .build());
    });
  }

  @Override
  public void persons(Instant asOf, LocalDate asOfDate, Consumer<NhanSuDaThayDoi> sink) {
    jdbc.query(PERSONS, params(asOf, asOfDate), (ResultSet rs) -> {
      long sv = rs.getLong("sv");
      sink.accept(NhanSuDaThayDoi.newBuilder()
          .setPersonId(Sql.uuid(rs, "id"))
          .setDisplayName(rs.getString("full_name"))
          .setSubjectGroupId(null)
          .setIsPartyMember(rs.getBoolean("is_party_member"))
          .setStatus(rs.getString("employment_status"))
          .setSourceVersion(sv)
          .setAggregateVersion(Math.toIntExact(sv))
          .build());
    });
  }

  @Override
  public void assignments(Instant asOf, Consumer<PhanCongDaThayDoi> sink) {
    jdbc.query(ASSIGNMENTS, params(asOf, null), (ResultSet rs) -> sink.accept(assignment(rs)));
  }

  private static PhanCongDaThayDoi assignment(ResultSet rs) throws SQLException {
    long sv = rs.getLong("sv");
    String kind = rs.getString("kind");
    return PhanCongDaThayDoi.newBuilder()
        .setAssignmentId(Sql.uuid(rs, "id"))
        .setPersonId(Sql.uuid(rs, "person_id"))
        .setUnitId(Sql.uuid(rs, "org_unit_id"))
        .setPositionId(Sql.uuid(rs, "position_id"))
        .setJobTitleId(Sql.uuid(rs, "job_title_id"))
        .setKind(kind)
        .setIsPrimary("CHINH".equals(kind))
        .setValidFrom(Sql.date(rs, "valid_from"))
        .setValidTo(Sql.date(rs, "valid_to"))
        .setSourceVersion(sv)
        .setAggregateVersion(Math.toIntExact(sv))
        .setIsHead(rs.getBoolean("is_head"))
        .setJobTitleName(rs.getString("job_title_name"))
        .build();
  }

  private static MapSqlParameterSource params(Instant asOf, LocalDate day) {
    return new MapSqlParameterSource()
        .addValue("tenant", Sql.tenant())
        .addValue("asOf", OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC))
        .addValue("day", day);
  }
}
