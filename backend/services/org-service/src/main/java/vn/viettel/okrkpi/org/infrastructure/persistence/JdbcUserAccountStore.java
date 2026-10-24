package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.port.UserAccountStore;

/** DV-04-09 · {@code org.user_account}: tên đăng nhập, trạng thái, dấu vết khoá và đặt lại mật khẩu. */
@Repository
public class JdbcUserAccountStore implements UserAccountStore {

  private static final String COLS = "person_id, username, status, locked_at, lock_reason, password_reset_at, "
      + "source, created_at, row_version";

  private final JdbcClient jdbc;

  public JdbcUserAccountStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Phiên bản hiện hành (hoặc gần nhất) của người, phân công ưu tiên CHINH còn hiệu lực ở ngày :d.
  private static final String PEOPLE = "SELECT p.id, p.full_name, pa.org_unit_id, coalesce(u.name, '') AS unit_name, "
      + "jt.name AS job_title_name, (SELECT count(*) FROM org.assignment a2 WHERE a2.tenant_id = p.tenant_id "
      + "  AND a2.person_id = p.id AND a2.kind <> 'CHINH' AND a2.recorded_to = 'infinity' "
      + "  AND a2.valid_period @> CAST(:d AS date)) AS concurrent "
      + "FROM (SELECT DISTINCT ON (p1.id) p1.tenant_id, p1.id, p1.full_name FROM org.person p1 "
      + "  WHERE p1.tenant_id = :tenant AND p1.recorded_to = 'infinity' %s "
      + "  ORDER BY p1.id, (p1.valid_period @> CAST(:d AS date)) DESC, p1.valid_from DESC) p "
      + "LEFT JOIN LATERAL (SELECT a.org_unit_id, a.job_title_id FROM org.assignment a WHERE a.tenant_id = p.tenant_id "
      + "  AND a.person_id = p.id AND a.recorded_to = 'infinity' AND a.valid_period @> CAST(:d AS date) "
      + "  ORDER BY (CASE WHEN a.kind = 'CHINH' THEN 0 ELSE 1 END), a.valid_from DESC LIMIT 1) pa ON true "
      + "LEFT JOIN LATERAL (SELECT u1.name FROM org.org_unit u1 WHERE u1.tenant_id = p.tenant_id "
      + "  AND u1.id = pa.org_unit_id AND u1.recorded_to = 'infinity' "
      + "  ORDER BY (u1.valid_period @> CAST(:d AS date)) DESC, u1.valid_from DESC LIMIT 1) u ON true "
      + "LEFT JOIN org.job_title jt ON jt.id = pa.job_title_id ";

  @Override
  public List<PersonRow> people(Collection<UUID> unitIds, LocalDate day) {
    if (unitIds == null) {
      return jdbc.sql(String.format(PEOPLE, ""))
          .param("tenant", Sql.tenant()).param("d", day)
          .query(JdbcUserAccountStore::person).list();
    }
    if (unitIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(String.format(PEOPLE, "") + "WHERE EXISTS (SELECT 1 FROM org.assignment a3 "
            + "WHERE a3.tenant_id = p.tenant_id AND a3.person_id = p.id AND a3.recorded_to = 'infinity' "
            + "AND a3.valid_period @> CAST(:d AS date) AND a3.org_unit_id = ANY(CAST(:units AS uuid[])))")
        .param("tenant", Sql.tenant()).param("d", day).param("units", Sql.uuidArray(unitIds))
        .query(JdbcUserAccountStore::person).list();
  }

  @Override
  public Optional<PersonRow> person(UUID personId, LocalDate day) {
    return jdbc.sql(String.format(PEOPLE, "AND p1.id = :id"))
        .param("tenant", Sql.tenant()).param("d", day).param("id", personId)
        .query(JdbcUserAccountStore::person).optional();
  }

  @Override
  public List<JobTitleRow> jobTitles(LocalDate day) {
    return jdbc.sql("SELECT id, code, name, level FROM org.job_title WHERE tenant_id = :tenant "
            + "AND valid_from <= :d AND valid_to > :d ORDER BY level NULLS LAST, name")
        .param("tenant", Sql.tenant()).param("d", day)
        .query((rs, n) -> new JobTitleRow(Sql.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
            rs.getInt("level")))
        .list();
  }

  private static PersonRow person(ResultSet rs, int n) throws SQLException {
    return new PersonRow(Sql.uuid(rs, "id"), rs.getString("full_name"), Sql.uuid(rs, "org_unit_id"),
        rs.getString("unit_name"), rs.getString("job_title_name"), rs.getInt("concurrent"));
  }

  @Override
  public Optional<AccountRow> find(UUID personId) {
    return jdbc.sql("SELECT " + COLS + " FROM org.user_account WHERE tenant_id = :tenant AND person_id = :id")
        .param("tenant", Sql.tenant()).param("id", personId)
        .query(JdbcUserAccountStore::map).optional();
  }

  @Override
  public List<AccountRow> findAll(Collection<UUID> personIds) {
    if (personIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("SELECT " + COLS + " FROM org.user_account WHERE tenant_id = :tenant "
            + "AND person_id = ANY(CAST(:ids AS uuid[]))")
        .param("tenant", Sql.tenant()).param("ids", Sql.uuidArray(personIds))
        .query(JdbcUserAccountStore::map).list();
  }

  @Override
  public void insert(UUID personId, String username, String status, String source, Actor actor) {
    jdbc.sql("INSERT INTO org.user_account (tenant_id, person_id, username, status, locked_at, lock_reason, source, "
            + "created_by, updated_by) VALUES (:tenant, :id, :username, :status, "
            + "CASE WHEN :status = 'KHOA' THEN now() END, CASE WHEN :status = 'KHOA' THEN :reason END, :source, "
            + ":actor, :actor) ON CONFLICT (tenant_id, person_id) DO NOTHING")
        .param("tenant", Sql.tenant()).param("id", personId).param("username", username).param("status", status)
        .param("reason", "Tài khoản đang bị khoá trên hệ thống đăng nhập").param("source", source)
        .param("actor", actor.actorId())
        .update();
  }

  @Override
  public boolean setStatus(UUID personId, String status, String lockReason, int expectedVersion, Actor actor) {
    return jdbc.sql("UPDATE org.user_account SET status = :status, "
            + "locked_at = CASE WHEN :status = 'KHOA' THEN now() ELSE locked_at END, "
            + "locked_by = CASE WHEN :status = 'KHOA' THEN :actor ELSE locked_by END, "
            + "lock_reason = CASE WHEN :status = 'KHOA' THEN :reason ELSE NULL END, "
            + "updated_at = now(), updated_by = :actor, row_version = row_version + 1 "
            + "WHERE tenant_id = :tenant AND person_id = :id AND row_version = :expected")
        .param("status", status).param("reason", lockReason).param("actor", actor.actorId())
        .param("tenant", Sql.tenant()).param("id", personId).param("expected", expectedVersion)
        .update() == 1;
  }

  @Override
  public void markPasswordReset(UUID personId, Actor actor) {
    jdbc.sql("UPDATE org.user_account SET password_reset_at = now(), password_reset_by = :actor, updated_at = now(), "
            + "updated_by = :actor, row_version = row_version + 1 WHERE tenant_id = :tenant AND person_id = :id")
        .param("actor", actor.actorId()).param("tenant", Sql.tenant()).param("id", personId)
        .update();
  }

  @Override
  public boolean touch(UUID personId, int expectedVersion, Actor actor) {
    return jdbc.sql("UPDATE org.user_account SET updated_at = now(), updated_by = :actor, "
            + "row_version = row_version + 1 WHERE tenant_id = :tenant AND person_id = :id "
            + "AND row_version = :expected")
        .param("actor", actor.actorId()).param("tenant", Sql.tenant()).param("id", personId)
        .param("expected", expectedVersion)
        .update() == 1;
  }

  private static AccountRow map(ResultSet rs, int n) throws SQLException {
    return new AccountRow(Sql.uuid(rs, "person_id"), rs.getString("username"), rs.getString("status"),
        Sql.ts(rs, "locked_at"), rs.getString("lock_reason"), Sql.ts(rs, "password_reset_at"),
        rs.getString("source"), Sql.ts(rs, "created_at"), rs.getInt("row_version"));
  }
}
