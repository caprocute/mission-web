package vn.viettel.okrkpi.org.support;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Dữ liệu mẫu ghi thẳng bằng tài khoản quản trị (bỏ qua RLS) để dựng kịch bản kiểm thử. */
public final class Fixtures {

  public static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-7000-8000-00000000a001");

  private final JdbcTemplate admin;

  public Fixtures() {
    PostgresSupport.start();
    DriverManagerDataSource ds = new DriverManagerDataSource(PostgresSupport.jdbcUrl(),
        PostgresSupport.adminUsername(), PostgresSupport.adminPassword());
    this.admin = new JdbcTemplate(ds);
  }

  public JdbcTemplate admin() {
    return admin;
  }

  public UUID subjectGroup(UUID tenant, String code) {
    UUID id = UUID.randomUUID();
    admin.update("INSERT INTO org.subject_group (id, tenant_id, code, name, flow, valid_from, created_by, updated_by) "
        + "VALUES (?, ?, ?, ?, 'CA_HAI', '2000-01-01', ?, ?)", id, tenant, code, "Khối " + code, SYSTEM_ACTOR,
        SYSTEM_ACTOR);
    return id;
  }

  public void unit(UUID tenant, UUID id, String code, String name, String type, UUID group, LocalDate from,
      LocalDate to) {
    unitVersion(tenant, id, code, name, type, group, from, to, null, null, 0);
  }

  public void unitVersion(UUID tenant, UUID id, String code, String name, String type, UUID group, LocalDate from,
      LocalDate to, OffsetDateTime recordedFrom, OffsetDateTime recordedTo, int rowVersion) {
    admin.update("INSERT INTO org.org_unit (version_id, id, tenant_id, code, name, unit_type, subject_group_id, flows, "
        + "level, valid_from, valid_to, recorded_from, recorded_to, created_by, updated_by, row_version) VALUES "
        + "(?, ?, ?, ?, ?, ?, ?, '{NHA_NUOC}', 'TINH', ?, coalesce(?, 'infinity'::date), coalesce(?, now()), "
        + "coalesce(?, 'infinity'::timestamptz), ?, ?, ?)", UUID.randomUUID(), id, tenant, code, name, type, group,
        from, to, recordedFrom, recordedTo, SYSTEM_ACTOR, SYSTEM_ACTOR, rowVersion);
  }

  public UUID relation(UUID tenant, String tree, UUID parent, UUID child, LocalDate from, LocalDate to) {
    UUID id = UUID.randomUUID();
    relationVersion(tenant, id, tree, parent, child, from, to, null, null);
    return id;
  }

  public void relationVersion(UUID tenant, UUID id, String tree, UUID parent, UUID child, LocalDate from,
      LocalDate to, OffsetDateTime recordedFrom, OffsetDateTime recordedTo) {
    admin.update("INSERT INTO org.org_unit_relation (version_id, id, tenant_id, tree, parent_unit_id, child_unit_id, "
        + "relation_type, valid_from, valid_to, recorded_from, recorded_to, created_by, updated_by) VALUES "
        + "(?, ?, ?, ?, ?, ?, 'TRUC_THUOC', ?, coalesce(?, 'infinity'::date), coalesce(?, now()), "
        + "coalesce(?, 'infinity'::timestamptz), ?, ?)", UUID.randomUUID(), id, tenant, tree, parent, child, from,
        to, recordedFrom, recordedTo, SYSTEM_ACTOR, SYSTEM_ACTOR);
  }

  public UUID jobTitle(UUID tenant) {
    UUID id = UUID.randomUUID();
    admin.update("INSERT INTO org.job_title (id, tenant_id, code, name, level, valid_from, created_by, updated_by) "
        + "VALUES (?, ?, ?, 'Chuyên viên', 5, '2000-01-01', ?, ?)", id, tenant, "CV-" + id.toString().substring(0, 8),
        SYSTEM_ACTOR, SYSTEM_ACTOR);
    return id;
  }

  public UUID person(UUID tenant, String fullName) {
    UUID id = UUID.randomUUID();
    admin.update("INSERT INTO org.person (version_id, id, tenant_id, full_name, employment_status, valid_from, "
        + "created_by, updated_by) VALUES (?, ?, ?, ?, 'DANG_CONG_TAC', '2000-01-01', ?, ?)", UUID.randomUUID(), id,
        tenant, fullName, SYSTEM_ACTOR, SYSTEM_ACTOR);
    return id;
  }

  public UUID assignment(UUID tenant, UUID person, UUID unit, UUID title, String kind, LocalDate from) {
    UUID id = UUID.randomUUID();
    admin.update("INSERT INTO org.assignment (version_id, id, tenant_id, person_id, org_unit_id, job_title_id, kind, "
        + "branch, valid_from, created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, 'CHINH_QUYEN', ?, ?, ?)",
        UUID.randomUUID(), id, tenant, person, unit, title, kind, from, SYSTEM_ACTOR, SYSTEM_ACTOR);
    return id;
  }

  /** Thân JSON của các sự kiện trong outbox theo topic, cũ trước. */
  public java.util.List<String> outboxPayloads(UUID tenant, String topic) {
    return admin.queryForList("SELECT convert_from(payload, 'UTF8') FROM org.outbox_event WHERE tenant_id = ? AND topic = ? "
        + "ORDER BY created_at", String.class, tenant, topic);
  }

  public int outboxCount(UUID tenant, String topic) {
    Integer n = admin.queryForObject("SELECT count(*) FROM org.outbox_event WHERE tenant_id = ? AND topic = ?",
        Integer.class, tenant, topic);
    return n == null ? 0 : n;
  }
}
