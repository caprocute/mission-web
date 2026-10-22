package vn.viettel.okrkpi.okr.infrastructure.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import vn.viettel.okrkpi.okr.application.port.OkrMilestoneRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-01-14: mốc của KR trên {@code okrDataSource} (RLS theo tenant). */
public class JdbcOkrMilestoneRepository implements OkrMilestoneRepository {

  private static final String COLUMNS =
      "id, seq, due_date, recognition_condition, achieved_at, evidence_ref, row_version";

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcOkrMilestoneRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  private static MapSqlParameterSource p(TenantId tenant, UUID keyResultId) {
    return new MapSqlParameterSource("t", tenant.value()).addValue("k", keyResultId);
  }

  private static MilestoneRow row(ResultSet rs, int i) throws SQLException {
    Timestamp achieved = rs.getTimestamp("achieved_at");
    return new MilestoneRow(
        rs.getObject("id", UUID.class),
        rs.getInt("seq"),
        rs.getDate("due_date").toLocalDate(),
        rs.getString("recognition_condition"),
        achieved == null ? null : achieved.toInstant(),
        rs.getString("evidence_ref"),
        rs.getLong("row_version"));
  }

  @Override
  public List<MilestoneRow> milestones(TenantId tenant, UUID keyResultId) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM okr.kr_milestone WHERE tenant_id = :t AND key_result_id = :k"
            + " ORDER BY seq",
        p(tenant, keyResultId),
        JdbcOkrMilestoneRepository::row);
  }

  @Override
  public void replaceMilestones(
      TenantId tenant, UUID keyResultId, List<MilestoneDraft> drafts, UUID actor) {
    jdbc.update(
        "DELETE FROM okr.kr_milestone WHERE tenant_id = :t AND key_result_id = :k",
        p(tenant, keyResultId));
    for (MilestoneDraft d : drafts) {
      jdbc.update(
          "INSERT INTO okr.kr_milestone (id, created_by, updated_by, key_result_id, seq, due_date,"
              + " recognition_condition) VALUES (:id, :actor, :actor, :k, :seq, :due, :cond)",
          p(tenant, keyResultId)
              .addValue("id", d.id() == null ? UUID.randomUUID() : d.id())
              .addValue("actor", actor)
              .addValue("seq", d.seq())
              .addValue("due", Date.valueOf(d.dueDate()))
              .addValue("cond", d.recognitionCondition()));
    }
  }

  @Override
  public Optional<MilestoneRow> markAchieved(
      TenantId tenant,
      UUID keyResultId,
      UUID milestoneId,
      Instant achievedAt,
      String evidenceRef,
      UUID actor) {
    return jdbc
        .query(
            "UPDATE okr.kr_milestone SET achieved_at = :at, evidence_ref = :ev, updated_at = now(),"
                + " updated_by = :actor, row_version = row_version + 1"
                + " WHERE tenant_id = :t AND key_result_id = :k AND id = :id RETURNING "
                + COLUMNS,
            p(tenant, keyResultId)
                .addValue("at", Timestamp.from(achievedAt))
                .addValue("ev", evidenceRef)
                .addValue("actor", actor)
                .addValue("id", milestoneId),
            JdbcOkrMilestoneRepository::row)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<LocalDate> keyResultDueDate(TenantId tenant, int bucket, UUID keyResultId) {
    return jdbc
        .query(
            "SELECT due_date FROM okr.key_result WHERE period_bucket = :b AND tenant_id = :t"
                + " AND id = :k",
            p(tenant, keyResultId).addValue("b", bucket),
            (rs, i) -> rs.getDate(1).toLocalDate())
        .stream()
        .findFirst();
  }

  @Override
  public long touchKeyResult(
      TenantId tenant, int bucket, UUID keyResultId, long rowVersion, UUID actor) {
    return jdbc
        .query(
            "UPDATE okr.key_result SET row_version = row_version + 1, updated_at = now(),"
                + " updated_by = :actor WHERE period_bucket = :b AND tenant_id = :t AND id = :k"
                + " AND row_version = :v RETURNING row_version",
            p(tenant, keyResultId)
                .addValue("b", bucket)
                .addValue("v", rowVersion)
                .addValue("actor", actor),
            (rs, i) -> rs.getLong(1))
        .stream()
        .findFirst()
        .orElse(0L);
  }
}
