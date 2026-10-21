package vn.viettel.okrkpi.org.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.port.SyncPersonStore;

/** DV-04-07 · {@code org.person} cho bộ so khớp: tra theo chỉ mục mù, họ tên, mã; ghi phiên bản hai trục. */
@Repository
public class JdbcSyncPersonStore implements SyncPersonStore {

  private static final String COLS = "id, full_name, dek_id, birth_date_enc, national_id_enc, employment_status, "
      + "is_party_member";
  private static final String CURRENT_AT = "recorded_to = 'infinity' AND valid_from <= :day AND valid_to > :day";

  private final JdbcClient jdbc;
  private final BitemporalWriter writer;

  public JdbcSyncPersonStore(JdbcClient jdbc, BitemporalWriter writer) {
    this.jdbc = jdbc;
    this.writer = writer;
  }

  @Override
  public List<PersonRow> findByNationalIdIndex(byte[] bidx, LocalDate day) {
    return jdbc.sql("SELECT " + COLS + " FROM org.person WHERE tenant_id = :tenant AND national_id_bidx = :bidx AND "
            + CURRENT_AT)
        .param("tenant", Sql.tenant()).param("bidx", bidx).param("day", day)
        .query(JdbcSyncPersonStore::map).list();
  }

  @Override
  public List<PersonRow> findByFullName(String fullName, LocalDate day) {
    return jdbc.sql("SELECT " + COLS + " FROM org.person WHERE tenant_id = :tenant AND full_name = :name AND "
            + CURRENT_AT)
        .param("tenant", Sql.tenant()).param("name", fullName).param("day", day)
        .query(JdbcSyncPersonStore::map).list();
  }

  @Override
  public Optional<PersonRow> findById(UUID id, LocalDate day) {
    // Người chưa có hiệu lực tại ngày hôm nay (vào làm tương lai) vẫn tra được bằng phiên bản gần nhất.
    return jdbc.sql("SELECT " + COLS + " FROM org.person WHERE tenant_id = :tenant AND id = :id "
            + "AND recorded_to = 'infinity' ORDER BY (valid_from <= :day AND valid_to > :day) DESC, valid_from DESC "
            + "LIMIT 1")
        .param("tenant", Sql.tenant()).param("id", id).param("day", day)
        .query(JdbcSyncPersonStore::map).optional();
  }

  @Override
  public void insert(NewPerson p, Actor actor) {
    jdbc.sql("INSERT INTO org.person (version_id, id, tenant_id, full_name, birth_date_enc, national_id_enc, "
            + "national_id_bidx, is_party_member, employment_status, dek_id, valid_from, created_by, updated_by) "
            + "VALUES (:vid, :id, :tenant, :name, :birth, :nid, :bidx, :party, :status, :dek, :from, :actor, :actor)")
        .param("vid", p.versionId()).param("id", p.id()).param("tenant", Sql.tenant()).param("name", p.fullName())
        .param("birth", p.birthDateEnc()).param("nid", p.nationalIdEnc()).param("bidx", p.nationalIdBidx())
        .param("party", p.partyMember()).param("status", p.employmentStatus()).param("dek", p.dekId())
        .param("from", p.validFrom()).param("actor", actor.actorId())
        .update();
  }

  @Override
  public int changeFrom(UUID id, LocalDate from, String employmentStatus, Boolean partyMember, Actor actor) {
    List<Map<String, Object>> slices = jdbc.sql("SELECT version_id, valid_from, valid_to FROM org.person "
            + "WHERE tenant_id = :tenant AND id = :id AND recorded_to = 'infinity' AND valid_to > :from "
            + "ORDER BY valid_from FOR UPDATE")
        .param("tenant", Sql.tenant()).param("id", id).param("from", from)
        .query().listOfRows();
    if (slices.isEmpty()) {
      throw new vn.viettel.okrkpi.org.domain.OrgRuleViolation("ORG-422-NOT-VALID-AT-DATE", null, null,
          "effectiveFrom", "Cán bộ không có hiệu lực từ ngày đã chọn.");
    }
    int next = writer.nextVersions("person", List.of(id)).get(id);
    for (Map<String, Object> s : slices) {
      UUID versionId = (UUID) s.get("version_id");
      LocalDate vf = ((java.sql.Date) s.get("valid_from")).toLocalDate();
      writer.close("person", List.of(versionId), actor);
      if (vf.isBefore(from)) {
        copy(versionId, vf, from, null, null, next, actor);
        copy(versionId, from, null, employmentStatus, partyMember, next, actor);
      } else {
        copy(versionId, vf, null, employmentStatus, partyMember, next, actor);
      }
    }
    return next;
  }

  /**
   * Chép phiên bản {@code source} thành lát {@code [from, to)} (to null là giữ ngày hết hiệu lực cũ), thay trạng thái
   * hoặc đảng tịch nếu có; cột C2 giữ nguyên bản mã cùng DEK.
   */
  private void copy(UUID source, LocalDate from, LocalDate to, String status, Boolean party, int rowVersion,
      Actor actor) {
    jdbc.sql("INSERT INTO org.person (version_id, id, tenant_id, full_name, gender, birth_date_enc, national_id_enc, "
            + "national_id_bidx, party_member_no_enc, party_member_no_bidx, is_party_member, cbccvc_code, "
            + "employment_status, dek_id, change_order_id, valid_from, valid_to, created_by, updated_by, row_version) "
            + "SELECT :vid, id, tenant_id, full_name, gender, birth_date_enc, national_id_enc, national_id_bidx, "
            + "party_member_no_enc, party_member_no_bidx, coalesce(:party, is_party_member), cbccvc_code, "
            + "coalesce(:status, employment_status), dek_id, change_order_id, :from, "
            + "CASE WHEN CAST(:to AS date) IS NULL THEN valid_to ELSE CAST(:to AS date) END, :actor, :actor, :rv "
            + "FROM org.person WHERE tenant_id = :tenant AND version_id = :source")
        .param("vid", vn.viettel.okrkpi.org.domain.UuidV7.next(java.time.Clock.systemUTC()))
        .param("party", party).param("status", status).param("from", from).param("to", to)
        .param("actor", actor.actorId()).param("rv", rowVersion).param("tenant", Sql.tenant())
        .param("source", source)
        .update();
  }

  @Override
  public int entityVersion(UUID id) {
    Integer v = jdbc.sql("SELECT max(row_version) FROM org.person WHERE tenant_id = :tenant AND id = :id")
        .param("tenant", Sql.tenant()).param("id", id).query(Integer.class).single();
    return v == null ? 0 : v;
  }

  private static PersonRow map(ResultSet rs, int n) throws SQLException {
    return new PersonRow(Sql.uuid(rs, "id"), rs.getString("full_name"), Sql.uuid(rs, "dek_id"),
        rs.getBytes("birth_date_enc"), rs.getBytes("national_id_enc"), rs.getString("employment_status"),
        rs.getBoolean("is_party_member"));
  }
}
