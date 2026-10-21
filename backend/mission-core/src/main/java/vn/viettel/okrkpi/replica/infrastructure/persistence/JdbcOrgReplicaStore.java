package vn.viettel.okrkpi.replica.infrastructure.persistence;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.replica.domain.AppliedEvent;
import vn.viettel.okrkpi.replica.domain.AssignmentSnapshot;
import vn.viettel.okrkpi.replica.domain.EntityType;
import vn.viettel.okrkpi.replica.domain.OrgReplicaStore;
import vn.viettel.okrkpi.replica.domain.PersonSnapshot;
import vn.viettel.okrkpi.replica.domain.UnitSnapshot;

/**
 * Ghi {@code org_replica} bằng JDBC trên {@code orgReplicaDataSource}. Upsert theo mẫu TL-03
 * "Event-carried State Transfer": {@code ON CONFLICT ... DO UPDATE ... WHERE
 * excluded.source_version > <bảng>.source_version}, nên sự kiện cũ đến muộn không ghi đè.
 */
@Repository
public class JdbcOrgReplicaStore implements OrgReplicaStore {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  @Autowired
  public JdbcOrgReplicaStore(OrgReplicaJdbc jdbc) {
    this(jdbc.template(), Clock.systemUTC());
  }

  public JdbcOrgReplicaStore(JdbcTemplate jdbc) {
    this(jdbc, Clock.systemUTC());
  }

  JdbcOrgReplicaStore(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public boolean upsertUnit(UnitSnapshot u, AppliedEvent e) {
    return jdbc.update(
            """
            insert into org_replica.org_unit_v as r (id, valid_from, valid_to, code, name, unit_type,
              party_parent_id, admin_parent_id, subject_group_id, source_version, source_event_id,
              replicated_at)
            values (?, ?, coalesce(cast(? as date), 'infinity'), ?, ?, ?, ?, ?, ?, ?, ?, now())
            on conflict (tenant_id, id, valid_from) do update set
              valid_to = excluded.valid_to, code = excluded.code, name = excluded.name,
              unit_type = excluded.unit_type, party_parent_id = excluded.party_parent_id,
              admin_parent_id = excluded.admin_parent_id,
              subject_group_id = excluded.subject_group_id,
              source_version = excluded.source_version,
              source_event_id = excluded.source_event_id, replicated_at = now()
            where excluded.source_version > r.source_version
            """,
            u.id(),
            date(u.validFrom()),
            dateOrInfinity(u.validTo()),
            u.code(),
            u.name(),
            u.unitType(),
            u.partyParentId(),
            u.adminParentId(),
            u.subjectGroupId(),
            u.sourceVersion(),
            e.eventId())
        > 0;
  }

  @Override
  public boolean upsertAssignment(AssignmentSnapshot a, AppliedEvent e) {
    return jdbc.update(
            """
            insert into org_replica.assignment_v as r (id, valid_from, valid_to, person_id,
              org_unit_id, position_id, job_title_id, kind, branch, is_head, source_version,
              source_event_id, replicated_at)
            values (?, ?, coalesce(cast(? as date), 'infinity'), ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            on conflict (tenant_id, id, valid_from) do update set
              valid_to = excluded.valid_to, person_id = excluded.person_id,
              org_unit_id = excluded.org_unit_id, position_id = excluded.position_id,
              job_title_id = excluded.job_title_id, kind = excluded.kind, branch = excluded.branch,
              is_head = excluded.is_head, source_version = excluded.source_version,
              source_event_id = excluded.source_event_id, replicated_at = now()
            where excluded.source_version > r.source_version
            """,
            a.id(),
            date(a.validFrom()),
            dateOrInfinity(a.validTo()),
            a.personId(),
            a.orgUnitId(),
            a.positionId(),
            a.jobTitleId(),
            a.kind(),
            a.branch(),
            a.head(),
            a.sourceVersion(),
            e.eventId())
        > 0;
  }

  @Override
  public boolean upsertPerson(PersonSnapshot p, AppliedEvent e) {
    return jdbc.update(
            """
            insert into org_replica.person_v as r (id, full_name, is_party_member,
              employment_status, source_version, source_event_id, replicated_at)
            values (?, ?, ?, ?, ?, ?, now())
            on conflict (tenant_id, id) do update set
              full_name = excluded.full_name, is_party_member = excluded.is_party_member,
              employment_status = excluded.employment_status,
              source_version = excluded.source_version,
              source_event_id = excluded.source_event_id, replicated_at = now()
            where excluded.source_version > r.source_version
            """,
            p.id(),
            p.fullName(),
            p.partyMember(),
            p.employmentStatus(),
            p.sourceVersion(),
            e.eventId())
        > 0;
  }

  @Override
  public Optional<UUID> closureParent(String tree, UUID unitId) {
    List<UUID> rows =
        jdbc.queryForList(
            "select ancestor_id from org_replica.unit_closure_v"
                + " where tree = ? and descendant_id = ? and depth = 1",
            UUID.class,
            tree,
            unitId);
    return rows.stream().findFirst();
  }

  @Override
  public void ensureClosureSelf(String tree, UUID unitId) {
    jdbc.update(
        "insert into org_replica.unit_closure_v (tree, ancestor_id, descendant_id, depth)"
            + " values (?, ?, ?, 0) on conflict do nothing",
        tree,
        unitId,
        unitId);
  }

  @Override
  public boolean closureContains(String tree, UUID ancestor, UUID candidate) {
    Boolean found =
        jdbc.queryForObject(
            "select exists (select 1 from org_replica.unit_closure_v"
                + " where tree = ? and ancestor_id = ? and descendant_id = ?)",
            Boolean.class,
            tree,
            ancestor,
            candidate);
    return Boolean.TRUE.equals(found);
  }

  @Override
  public void detachSubtree(String tree, UUID unitId) {
    jdbc.update(
        """
        delete from org_replica.unit_closure_v c
        where c.tree = ?
          and c.descendant_id in (select s.descendant_id from org_replica.unit_closure_v s
                                  where s.tree = ? and s.ancestor_id = ?)
          and c.ancestor_id not in (select s.descendant_id from org_replica.unit_closure_v s
                                    where s.tree = ? and s.ancestor_id = ?)
        """,
        tree,
        tree,
        unitId,
        tree,
        unitId);
  }

  @Override
  public void attachSubtree(String tree, UUID unitId, UUID parentId) {
    jdbc.update(
        """
        insert into org_replica.unit_closure_v (tree, ancestor_id, descendant_id, depth)
        select ?, p.ancestor_id, s.descendant_id, p.depth + s.depth + 1
        from org_replica.unit_closure_v p, org_replica.unit_closure_v s
        where p.tree = ? and p.descendant_id = ? and s.tree = ? and s.ancestor_id = ?
        on conflict do nothing
        """,
        tree,
        tree,
        parentId,
        tree,
        unitId);
  }

  @Override
  public long checkpoint(EntityType type, AppliedEvent e) {
    Instant now = clock.instant();
    long lag = Math.max(0, Duration.between(e.occurredAt(), now).toMillis());
    jdbc.update(
        """
        insert into org_replica.replica_checkpoint as c (entity_type, last_event_id,
          last_occurred_at, last_applied_at, lag_ms)
        values (?, ?, ?, ?, ?)
        on conflict (tenant_id, entity_type) do update set
          last_event_id = excluded.last_event_id, last_occurred_at = excluded.last_occurred_at,
          last_applied_at = excluded.last_applied_at, lag_ms = excluded.lag_ms
        where excluded.last_occurred_at >= c.last_occurred_at
        """,
        type.name(),
        e.eventId(),
        Timestamp.from(e.occurredAt()),
        Timestamp.from(now),
        (int) Math.min(Integer.MAX_VALUE, lag));
    return lag;
  }

  private static Date date(LocalDate d) {
    return Date.valueOf(d);
  }

  /** {@code null} là vô thời hạn; câu SQL đổi thành {@code 'infinity'}. */
  private static Date dateOrInfinity(LocalDate d) {
    return d == null ? null : Date.valueOf(d);
  }
}
