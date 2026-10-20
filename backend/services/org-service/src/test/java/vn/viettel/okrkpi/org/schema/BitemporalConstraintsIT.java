package vn.viettel.okrkpi.org.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.PostgresSupport;

/**
 * Ràng buộc hai trục thời gian, RLS và quyền tối thiểu chạy thật bằng {@code svc_org} (TL-03 "Dữ liệu tổ chức hai
 * trục thời gian", "Không truy cập chéo và quyền tối thiểu").
 */
class BitemporalConstraintsIT {

  private static Fixtures fx;
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();
  private static UUID group;
  private static UUID title;

  @BeforeAll
  static void setUp() {
    fx = new Fixtures();
    group = fx.subjectGroup(TENANT, "BT");
    title = fx.jobTitle(TENANT);
  }

  private static Connection svc(UUID tenant) throws SQLException {
    Connection c = PostgresSupport.as("svc_org", PostgresSupport.SVC_PASSWORD);
    c.setAutoCommit(false);
    if (tenant != null) {
      try (PreparedStatement p = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        p.setString(1, tenant.toString());
        p.execute();
      }
    }
    return c;
  }

  private static void insertAssignment(Connection c, UUID versionId, UUID id, UUID person, UUID unit, String kind,
      LocalDate from, LocalDate to) throws SQLException {
    try (PreparedStatement p = c.prepareStatement("INSERT INTO org.assignment (version_id, id, tenant_id, person_id, "
        + "org_unit_id, job_title_id, kind, branch, valid_from, valid_to, created_by, updated_by) VALUES "
        + "(?, ?, ?, ?, ?, ?, ?, 'CHINH_QUYEN', ?, coalesce(?, 'infinity'::date), ?, ?)")) {
      p.setObject(1, versionId);
      p.setObject(2, id);
      p.setObject(3, TENANT);
      p.setObject(4, person);
      p.setObject(5, unit);
      p.setObject(6, title);
      p.setString(7, kind);
      p.setObject(8, from);
      p.setObject(9, to);
      p.setObject(10, Fixtures.SYSTEM_ACTOR);
      p.setObject(11, Fixtures.SYSTEM_ACTOR);
      p.executeUpdate();
    }
  }

  private UUID newUnit(String code) {
    UUID u = UUID.randomUUID();
    fx.unit(TENANT, u, code, "Đơn vị " + code, "CO_QUAN", group, LocalDate.of(2020, 1, 1), null);
    return u;
  }

  @Test
  void haiPhanCongChinhChongLanBiChan() throws SQLException {
    UUID person = fx.person(TENANT, "Nguyễn Văn A");
    UUID u1 = newUnit("BT-A1");
    UUID u2 = newUnit("BT-A2");
    try (Connection c = svc(TENANT)) {
      insertAssignment(c, UUID.randomUUID(), UUID.randomUUID(), person, u1, "CHINH", LocalDate.of(2024, 1, 1), null);
      assertThatThrownBy(() -> insertAssignment(c, UUID.randomUUID(), UUID.randomUUID(), person, u2, "CHINH",
          LocalDate.of(2025, 1, 1), null))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23P01"))
          .hasMessageContaining("ex_assignment__one_primary");
      c.rollback();
    }
  }

  @Test
  void kiemNhiemSongSongDuocPhep() throws SQLException {
    UUID person = fx.person(TENANT, "Trần Thị B");
    try (Connection c = svc(TENANT)) {
      insertAssignment(c, UUID.randomUUID(), UUID.randomUUID(), person, newUnit("BT-B1"), "CHINH",
          LocalDate.of(2024, 1, 1), null);
      insertAssignment(c, UUID.randomUUID(), UUID.randomUUID(), person, newUnit("BT-B2"), "KIEM_NHIEM",
          LocalDate.of(2024, 6, 1), null);
      c.commit();
    }
  }

  @Test
  void dongPhienBanCuRoiChenPhienBanMoiTrongCungGiaoDich() throws SQLException {
    UUID person = fx.person(TENANT, "Lê Văn C");
    UUID unit = newUnit("BT-C1");
    UUID id = UUID.randomUUID();
    UUID v1 = UUID.randomUUID();
    try (Connection c = svc(TENANT)) {
      insertAssignment(c, v1, id, person, unit, "CHINH", LocalDate.of(2024, 1, 1), null);
      c.commit();
    }
    try (Connection c = svc(TENANT)) {
      // Thiếu bước đóng thì chèn phiên bản mới của cùng thực thể bị chặn bởi ex_assignment__version_no_overlap.
      assertThatThrownBy(() -> insertAssignment(c, UUID.randomUUID(), id, person, unit, "CHINH",
          LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)))
          .hasMessageContaining("ex_assignment__");
      c.rollback();
    }
    try (Connection c = svc(TENANT)) {
      try (PreparedStatement p = c.prepareStatement("UPDATE org.assignment SET recorded_to = now(), "
          + "updated_at = now(), updated_by = ?, row_version = row_version + 1 WHERE version_id = ? "
          + "AND recorded_to = 'infinity'")) {
        p.setObject(1, Fixtures.SYSTEM_ACTOR);
        p.setObject(2, v1);
        assertThat(p.executeUpdate()).isEqualTo(1);
      }
      insertAssignment(c, UUID.randomUUID(), id, person, unit, "CHINH", LocalDate.of(2024, 1, 1),
          LocalDate.of(2025, 1, 1));
      c.commit();
    }
    assertThat(fx.admin().queryForObject("SELECT count(*) FROM org.assignment WHERE id = ?", Integer.class, id))
        .isEqualTo(2);
  }

  @Test
  void svcOrgKhongSuaDuocCotNghiepVuVaKhongXoaDuoc() throws SQLException {
    UUID unit = newUnit("BT-D1");
    for (String sql : List.of("UPDATE org.org_unit SET name = 'Đổi tên lén' WHERE id = '" + unit + "'",
        "DELETE FROM org.org_unit WHERE id = '" + unit + "'", "DELETE FROM org.outbox_event",
        "UPDATE org.org_unit SET valid_to = '2020-02-01' WHERE id = '" + unit + "'")) {
      try (Connection c = svc(TENANT)) {
        assertThatThrownBy(() -> c.createStatement().executeUpdate(sql)).as(sql)
            .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
        c.rollback();
      }
    }
  }

  /**
   * Biểu thức RLS của TL-03 là {@code current_setting('app.tenant_id', true)::uuid}: trên kết nối đã từng đặt biến
   * cục bộ giao dịch, giá trị sau đó là chuỗi rỗng nên truy vấn quên đặt tenant bị lỗi ép kiểu (vẫn là từ chối).
   */
  @Test
  void ketNoiTaiSuDungQuenDatTenantBiTuChoi() throws SQLException {
    try (Connection c = svc(TENANT)) {
      c.commit();
      assertThatThrownBy(() -> c.createStatement().executeQuery("SELECT count(*) FROM org.org_unit"))
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("22P02"));
      c.rollback();
    }
  }

  @Test
  void rlsCachLyTenantVaThieuTenantThiRong() throws SQLException {
    UUID otherGroup = fx.subjectGroup(OTHER, "BT-O");
    UUID foreign = UUID.randomUUID();
    fx.unit(OTHER, foreign, "BT-O1", "Đơn vị tenant khác", "CO_QUAN", otherGroup, LocalDate.of(2020, 1, 1), null);
    UUID mine = newUnit("BT-E1");
    try (Connection c = svc(TENANT)) {
      assertThat(count(c, "SELECT count(*) FROM org.org_unit WHERE id = '" + foreign + "'")).isZero();
      assertThat(count(c, "SELECT count(*) FROM org.org_unit WHERE id = '" + mine + "'")).isEqualTo(1);
      // Ghi sang tenant khác bị WITH CHECK chặn.
      assertThatThrownBy(() -> c.createStatement().executeUpdate("INSERT INTO org.subject_group (id, tenant_id, "
          + "code, name, flow, valid_from, created_by, updated_by) VALUES (gen_random_uuid(), '" + OTHER
          + "', 'X', 'X', 'CA_HAI', '2020-01-01', gen_random_uuid(), gen_random_uuid())"))
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
      c.rollback();
    }
    try (Connection c = svc(null)) {
      assertThat(count(c, "SELECT count(*) FROM org.org_unit")).isZero();
      c.rollback();
    }
  }

  /**
   * Kịch bản Hình 7.1 TL-03: V1 phân công vào đơn vị A từ 01/01/2025; ngày 10/03/2025 ghi nhận quyết định điều động
   * sang B có hiệu lực hồi tố 01/03/2025 (đóng V1, chèn V2 = A [01/01, 01/03) và V3 = B [01/03, ∞)). Truy vấn hai
   * trục trả đúng đơn vị theo cả "ngày hiệu lực" và "thời điểm biết".
   */
  @Test
  void truyVanHaiTrucTheoKichBanHinh71() throws SQLException {
    UUID person = fx.person(TENANT, "Phạm Văn D");
    UUID a = newUnit("BT-F1");
    UUID b = newUnit("BT-F2");
    UUID id = UUID.randomUUID();
    OffsetDateTime t1 = OffsetDateTime.parse("2025-01-02T08:00:00+07:00");
    OffsetDateTime t2 = OffsetDateTime.parse("2025-03-10T08:00:00+07:00");
    insertRaw(UUID.randomUUID(), id, person, a, LocalDate.of(2025, 1, 1), null, t1, t2);
    insertRaw(UUID.randomUUID(), id, person, a, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 1), t2, null);
    insertRaw(UUID.randomUUID(), id, person, b, LocalDate.of(2025, 3, 1), null, t2, null);

    try (Connection c = svc(TENANT)) {
      // Ngày 05/03 như biết tại 05/03 (trước khi ghi nhận điều động): vẫn ở A.
      assertThat(unitAsOf(c, person, LocalDate.of(2025, 3, 5), OffsetDateTime.parse("2025-03-05T12:00:00+07:00")))
          .containsExactly(a);
      // Ngày 05/03 như biết hiện nay: đã ở B (hồi tố).
      assertThat(unitAsOf(c, person, LocalDate.of(2025, 3, 5), OffsetDateTime.now())).containsExactly(b);
      // Ngày 15/02 như biết hiện nay: ở A.
      assertThat(unitAsOf(c, person, LocalDate.of(2025, 2, 15), OffsetDateTime.now())).containsExactly(a);
      // Trước khi biết có phân công: rỗng.
      assertThat(unitAsOf(c, person, LocalDate.of(2025, 2, 15), OffsetDateTime.parse("2025-01-01T00:00:00+07:00")))
          .isEmpty();
      c.rollback();
    }
  }

  private void insertRaw(UUID versionId, UUID id, UUID person, UUID unit, LocalDate from, LocalDate to,
      OffsetDateTime recFrom, OffsetDateTime recTo) {
    fx.admin().update("INSERT INTO org.assignment (version_id, id, tenant_id, person_id, org_unit_id, job_title_id, "
        + "kind, branch, valid_from, valid_to, recorded_from, recorded_to, created_by, updated_by) VALUES "
        + "(?, ?, ?, ?, ?, ?, 'CHINH', 'CHINH_QUYEN', ?, coalesce(?, 'infinity'::date), ?, "
        + "coalesce(?, 'infinity'::timestamptz), ?, ?)", versionId, id, TENANT, person, unit, title, from, to,
        Timestamp.from(recFrom.toInstant()), recTo == null ? null : Timestamp.from(recTo.toInstant()),
        Fixtures.SYSTEM_ACTOR, Fixtures.SYSTEM_ACTOR);
  }

  private static List<UUID> unitAsOf(Connection c, UUID person, LocalDate day, OffsetDateTime known)
      throws SQLException {
    try (PreparedStatement p = c.prepareStatement("SELECT org_unit_id FROM org.assignment_asof(?, ?, ?) "
        + "WHERE person_id = ?")) {
      p.setObject(1, TENANT);
      p.setObject(2, day);
      p.setObject(3, known);
      p.setObject(4, person);
      List<UUID> out = new ArrayList<>();
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getObject(1, UUID.class));
        }
      }
      return out;
    }
  }

  private static int count(Connection c, String sql) throws SQLException {
    try (ResultSet rs = c.createStatement().executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
