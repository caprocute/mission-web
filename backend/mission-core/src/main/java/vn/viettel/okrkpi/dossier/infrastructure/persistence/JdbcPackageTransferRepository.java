package vn.viettel.okrkpi.dossier.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.EdxmlGateway.RemoteState;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageTransferRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@link PackageTransferRepository} trên {@code dossier.package_transfer} (CORE-06-08). */
public class JdbcPackageTransferRepository implements PackageTransferRepository {

  private static final String COLUMNS =
      "id, period_bucket, dossier_package_id, batch_id, profile_id, person_id, destination,"
          + " status, attempts, next_attempt_at, ack_ref, acknowledged_at, last_error,"
          + " notified_attempts, created_by, updated_at";
  private static final int MAX_ERROR = 500;
  private static final int MAX_ACK = 128;

  private final JdbcClient jdbc;

  public JdbcPackageTransferRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public void insert(
      TenantId tenant,
      int periodBucket,
      UUID packageId,
      UUID batchId,
      String destination,
      Collection<NewTransfer> transfers,
      UUID actor) {
    for (NewTransfer t : transfers) {
      jdbc.sql(
              """
              INSERT INTO dossier.package_transfer (id, tenant_id, created_by, updated_by,
                  period_bucket, dossier_package_id, batch_id, profile_id, person_id, destination,
                  status)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CHUA_GUI')
              """)
          .params(
              t.id(),
              tenant.value(),
              actor,
              actor,
              periodBucket,
              packageId,
              batchId,
              t.profileId(),
              t.personId(),
              destination)
          .update();
    }
  }

  @Override
  public Optional<TransferRow> find(TenantId tenant, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM dossier.package_transfer WHERE tenant_id = ? AND id = ?")
        .params(tenant.value(), id)
        .query(JdbcPackageTransferRepository::row)
        .optional();
  }

  @Override
  public List<TransferRow> ofPackage(TenantId tenant, UUID packageId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM dossier.package_transfer WHERE tenant_id = ? AND dossier_package_id = ?"
                + " ORDER BY id")
        .params(tenant.value(), packageId)
        .query(JdbcPackageTransferRepository::row)
        .list();
  }

  @Override
  public List<TransferRow> ofBatch(TenantId tenant, UUID batchId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM dossier.package_transfer WHERE tenant_id = ? AND batch_id = ? ORDER BY id")
        .params(tenant.value(), batchId)
        .query(JdbcPackageTransferRepository::row)
        .list();
  }

  @Override
  public List<TransferRow> open(TenantId tenant, Duration quiet, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM dossier.package_transfer WHERE tenant_id = ?"
                + " AND status IN ('CHUA_GUI', 'CHO', 'DA_GUI', 'LOI')"
                + " AND NOT (status = 'LOI' AND next_attempt_at IS NULL)"
                + " AND updated_at < now() - make_interval(secs => ?) ORDER BY updated_at LIMIT ?")
        .params(tenant.value(), (double) quiet.toSeconds(), limit)
        .query(JdbcPackageTransferRepository::row)
        .list();
  }

  @Override
  public boolean applyRemote(TenantId tenant, UUID id, RemoteState s) {
    Instant acknowledged =
        "DA_XAC_NHAN".equals(s.status()) && s.acknowledgedAt() == null
            ? Instant.now()
            : s.acknowledgedAt();
    return jdbc.sql(
                """
                UPDATE dossier.package_transfer SET status = ?, attempts = ?, next_attempt_at = ?,
                    ack_ref = ?, acknowledged_at = ?, last_error = ?, updated_at = now(),
                    row_version = row_version + 1
                WHERE tenant_id = ? AND id = ? AND status NOT IN ('DA_XAC_NHAN', 'HUY')
                  AND (status, attempts, next_attempt_at, ack_ref, last_error)
                      IS DISTINCT FROM (?::varchar, ?::int, ?::timestamptz, ?::varchar, ?::varchar)
                """)
            .params(
                s.status(),
                s.attempts(),
                time(s.nextAttemptAt()),
                text(cut(s.ackRef(), MAX_ACK)),
                time(acknowledged),
                text(cut(s.lastError(), MAX_ERROR)),
                tenant.value(),
                id,
                s.status(),
                s.attempts(),
                time(s.nextAttemptAt()),
                text(cut(s.ackRef(), MAX_ACK)),
                text(cut(s.lastError(), MAX_ERROR)))
            .update()
        == 1;
  }

  @Override
  public void close(TenantId tenant, UUID id, String status, String reason) {
    jdbc.sql(
            "UPDATE dossier.package_transfer SET status = ?, last_error = ?, next_attempt_at = NULL,"
                + " updated_at = now(), row_version = row_version + 1 WHERE tenant_id = ? AND id = ?"
                + " AND status IN ('CHUA_GUI', 'CHO', 'DA_GUI', 'LOI')")
        .params(status, cut(reason, MAX_ERROR), tenant.value(), id)
        .update();
  }

  @Override
  public boolean markNotified(TenantId tenant, UUID id, int attempts) {
    return jdbc.sql(
                "UPDATE dossier.package_transfer SET notified_attempts = ?, updated_at = updated_at"
                    + " WHERE tenant_id = ? AND id = ? AND notified_attempts < ?")
            .params(attempts, tenant.value(), id, attempts)
            .update()
        == 1;
  }

  private static TransferRow row(ResultSet rs, int i) throws SQLException {
    return new TransferRow(
        rs.getObject("id", UUID.class),
        rs.getInt("period_bucket"),
        rs.getObject("dossier_package_id", UUID.class),
        rs.getObject("batch_id", UUID.class),
        rs.getObject("profile_id", UUID.class),
        rs.getObject("person_id", UUID.class),
        rs.getString("destination"),
        rs.getString("status"),
        rs.getInt("attempts"),
        JdbcPackageExportRepository.instant(rs.getTimestamp("next_attempt_at")),
        rs.getString("ack_ref"),
        JdbcPackageExportRepository.instant(rs.getTimestamp("acknowledged_at")),
        rs.getString("last_error"),
        rs.getInt("notified_attempts"),
        rs.getObject("created_by", UUID.class),
        JdbcPackageExportRepository.instant(rs.getTimestamp("updated_at")));
  }

  private static Object time(Instant t) {
    return t == null
        ? new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE, null)
        : Timestamp.from(t);
  }

  private static Object text(String s) {
    return s == null ? new SqlParameterValue(Types.VARCHAR, null) : s;
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
