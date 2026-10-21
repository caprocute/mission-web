package vn.viettel.okrkpi.replica.infrastructure.persistence;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.replica.api.OrgTree;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Hiện thực {@link OrgDirectoryApi} trên {@code org_replica}: mỗi lời gọi mở giao dịch chỉ đọc trên
 * {@code orgReplicaTransactionManager} với {@code app.tenant_id} của tenant được hỏi.
 */
@Component
public class JdbcOrgDirectory implements OrgDirectoryApi {

  private static final String ASSIGNMENT_COLUMNS =
      "id, person_id, org_unit_id, position_id, job_title_id, kind, branch, is_head, valid_from,"
          + " valid_to";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readTx;

  @Autowired
  public JdbcOrgDirectory(
      OrgReplicaJdbc jdbc,
      @Qualifier("orgReplicaTransactionManager") PlatformTransactionManager tm) {
    this(jdbc.template(), tm);
  }

  public JdbcOrgDirectory(JdbcTemplate jdbc, PlatformTransactionManager tm) {
    this.jdbc = jdbc;
    this.readTx = new TransactionTemplate(tm);
    this.readTx.setReadOnly(true);
  }

  @Override
  public List<AssignmentView> assignmentsOf(TenantId tenant, UUID personId, LocalDate on) {
    return read(
        tenant,
        () ->
            jdbc.query(
                "select "
                    + ASSIGNMENT_COLUMNS
                    + " from org_replica.assignment_v where person_id = ? and valid_from <= ?"
                    + " and valid_to >= ? order by (branch = 'CHINH') desc, valid_from",
                (rs, i) ->
                    new AssignmentView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("person_id", UUID.class),
                        rs.getObject("org_unit_id", UUID.class),
                        rs.getObject("position_id", UUID.class),
                        rs.getObject("job_title_id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("branch"),
                        rs.getBoolean("is_head"),
                        rs.getObject("valid_from", LocalDate.class),
                        finite(rs.getString("valid_to"), rs.getDate("valid_to"))),
                personId,
                Date.valueOf(on),
                Date.valueOf(on)));
  }

  @Override
  public List<UUID> headsOf(TenantId tenant, UUID orgUnitId, LocalDate on) {
    return read(
        tenant,
        () ->
            jdbc.queryForList(
                "select distinct person_id from org_replica.assignment_v where org_unit_id = ?"
                    + " and is_head and valid_from <= ? and valid_to >= ?",
                UUID.class,
                orgUnitId,
                Date.valueOf(on),
                Date.valueOf(on)));
  }

  @Override
  public Optional<UUID> parentOf(TenantId tenant, OrgTree tree, UUID orgUnitId) {
    return read(
        tenant,
        () ->
            jdbc
                .queryForList(
                    "select ancestor_id from org_replica.unit_closure_v"
                        + " where tree = ? and descendant_id = ? and depth = 1",
                    UUID.class,
                    tree.name(),
                    orgUnitId)
                .stream()
                .findFirst());
  }

  @Override
  public List<UUID> descendantsOf(TenantId tenant, OrgTree tree, UUID orgUnitId, int maxDepth) {
    int depth = maxDepth <= 0 ? Integer.MAX_VALUE : maxDepth;
    return read(
        tenant,
        () ->
            jdbc.queryForList(
                "select descendant_id from org_replica.unit_closure_v where tree = ?"
                    + " and ancestor_id = ? and depth between 1 and ? order by depth, descendant_id",
                UUID.class,
                tree.name(),
                orgUnitId,
                depth));
  }

  @Override
  public List<UUID> ancestorsOf(TenantId tenant, OrgTree tree, UUID orgUnitId) {
    return read(
        tenant,
        () ->
            jdbc.queryForList(
                "select ancestor_id from org_replica.unit_closure_v where tree = ?"
                    + " and descendant_id = ? and depth > 0 order by depth",
                UUID.class,
                tree.name(),
                orgUnitId));
  }

  @Override
  public boolean isDescendant(TenantId tenant, OrgTree tree, UUID ancestor, UUID candidate) {
    return read(
        tenant,
        () ->
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "select exists (select 1 from org_replica.unit_closure_v where tree = ?"
                        + " and ancestor_id = ? and descendant_id = ?)",
                    Boolean.class,
                    tree.name(),
                    ancestor,
                    candidate)));
  }

  private <T> T read(TenantId tenant, Supplier<T> query) {
    try {
      return TenantContext.call(tenant, () -> readTx.execute(status -> query.get()));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@code 'infinity'} của PostgreSQL trả về rỗng (vô thời hạn). */
  private static LocalDate finite(String text, Date value) {
    return text == null || "infinity".equals(text) ? null : value.toLocalDate();
  }
}
