package vn.viettel.okrkpi.okr.infrastructure.persistence;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegatePort;
import vn.viettel.okrkpi.okr.application.port.OkrCheckinDelegateRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * CORE-01-12: uỷ quyền check-in thay trên {@code okrDataSource}; đồng thời là cổng kiểm người được
 * check-in của CORE-01-08. Ngày hiệu lực tính theo giờ Việt Nam.
 */
public class JdbcOkrCheckinDelegates
    implements OkrCheckinDelegateRepository, OkrCheckinDelegatePort {

  private final NamedParameterJdbcTemplate jdbc;
  private final Clock clock;

  public JdbcOkrCheckinDelegates(NamedParameterJdbcTemplate jdbc, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  private static MapSqlParameterSource t(TenantId tenant) {
    return new MapSqlParameterSource("t", tenant.value());
  }

  @Override
  public boolean isDelegate(TenantId tenant, UUID keyResultId, UUID personId) {
    return open(tenant, keyResultId)
        .filter(d -> d.personId().equals(personId))
        .filter(d -> d.validOn(LocalDate.now(clock)))
        .isPresent();
  }

  @Override
  public Optional<Delegation> open(TenantId tenant, UUID keyResultId) {
    return jdbc
        .query(
            "SELECT id, key_result_id, delegate_person_id, valid_until, reason, created_by,"
                + " created_at FROM okr.kr_checkin_delegate WHERE tenant_id = :t"
                + " AND key_result_id = :k AND revoked_at IS NULL",
            t(tenant).addValue("k", keyResultId),
            (rs, i) -> {
              Date until = rs.getDate("valid_until");
              return new Delegation(
                  rs.getObject("id", UUID.class),
                  rs.getObject("key_result_id", UUID.class),
                  rs.getObject("delegate_person_id", UUID.class),
                  until == null ? null : until.toLocalDate(),
                  rs.getString("reason"),
                  rs.getObject("created_by", UUID.class),
                  rs.getObject("created_at", Timestamp.class).toInstant());
            })
        .stream()
        .findFirst();
  }

  @Override
  public void appoint(
      TenantId tenant,
      UUID keyResultId,
      UUID personId,
      LocalDate validUntil,
      String reason,
      UUID actor) {
    jdbc.update(
        "INSERT INTO okr.kr_checkin_delegate (id, created_by, updated_by, key_result_id,"
            + " delegate_person_id, valid_until, reason) VALUES (:id, :actor, :actor, :k, :p,"
            + " :until, :reason)",
        t(tenant)
            .addValue("id", UUID.randomUUID())
            .addValue("actor", actor)
            .addValue("k", keyResultId)
            .addValue("p", personId)
            .addValue("until", validUntil == null ? null : Date.valueOf(validUntil))
            .addValue("reason", reason));
  }

  @Override
  public void end(TenantId tenant, UUID delegationId, UUID actor) {
    jdbc.update(
        "UPDATE okr.kr_checkin_delegate SET revoked_at = now(), revoked_by = :actor,"
            + " updated_at = now(), updated_by = :actor, row_version = row_version + 1"
            + " WHERE tenant_id = :t AND id = :id AND revoked_at IS NULL",
        t(tenant).addValue("id", delegationId).addValue("actor", actor));
  }

  @Override
  public long touchKeyResult(
      TenantId tenant, int bucket, UUID keyResultId, long rowVersion, UUID actor) {
    jdbc.update(
        "UPDATE okr.key_result SET row_version = row_version + 1, updated_at = now(),"
            + " updated_by = :actor WHERE period_bucket = :b AND tenant_id = :t AND id = :id",
        t(tenant).addValue("b", bucket).addValue("id", keyResultId).addValue("actor", actor));
    return rowVersion + 1;
  }

  @Override
  public List<KeyResultCode> keyResultCodes(TenantId tenant, int bucket, UUID objectiveId) {
    return jdbc.query(
        "SELECT id, code FROM okr.key_result WHERE period_bucket = :b AND tenant_id = :t"
            + " AND objective_id = :o ORDER BY code",
        t(tenant).addValue("b", bucket).addValue("o", objectiveId),
        (rs, i) -> new KeyResultCode(rs.getObject("id", UUID.class), rs.getString("code")));
  }
}
