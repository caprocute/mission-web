package vn.viettel.okrkpi.okr.infrastructure.migration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import vn.viettel.okrkpi.platform.db.migration.TenantIsolationCatalogTest;

/**
 * Kiểm thử rò rỉ tenant (RLS hai lớp) và phân vùng theo kỳ cho các bảng của schema {@code okr}
 * (CORE-01-01).
 *
 * <p>Khoá ngoại tới bảng theo kỳ gồm ba cột ({@code period_bucket}, {@code tenant_id}, mã) mà bộ
 * sinh dòng tự động chỉ nối cột đầu, và {@code ck_objective__owner_person} cần giá trị liệt kê hợp
 * lệ; nên mục tiêu, căn cứ và ba bảng con dùng fixture có mã cố định theo tenant. Bộ sinh xếp bảng
 * theo tên khi không thấy khoá ngoại một cột, nên {@code key_result} đứng trước {@code objective};
 * lớp này chèn trước một mục tiêu cho mỗi tenant để KR trỏ về.
 */
class OkrTenantIsolationIT extends TenantIsolationCatalogTest {

  private static final UUID ACTOR = UUID.fromString("01927a01-0000-7000-8000-0000000000ac");
  private static final UUID PERIOD = UUID.fromString("01927a01-0000-7000-8000-0000000000e1");

  @Override
  protected List<String> schemas() {
    return List.of("okr");
  }

  @Override
  protected Map<String, RowFixture> rowFixtures() {
    return Map.of(
        "okr.basis",
        (tenant, seed) -> {
          Map<String, Object> row = audited(seeded("basis", tenant));
          row.put("basis_kind", "NGHI_QUYET");
          row.put("title", "Nghị quyết mẫu");
          return row;
        },
        "okr.objective",
        (tenant, seed) -> {
          Map<String, Object> row = periodic(seeded("obj", tenant));
          row.put("owner_type", "TAP_THE");
          row.put("owner_unit_id", seeded("unit", tenant));
          row.put("code", "O1");
          row.put("title", "Mục tiêu mẫu");
          row.put("tier", (short) 1);
          return row;
        },
        "okr.objective_basis",
        (tenant, seed) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("period_bucket", SAMPLE_PERIOD_BUCKET);
          row.put("objective_id", seeded("obj", tenant));
          row.put("basis_id", seeded("basis", tenant));
          row.put("created_by", ACTOR);
          return row;
        },
        "okr.objective_review",
        (tenant, seed) -> {
          Map<String, Object> row = periodic(seeded("review", tenant));
          row.put("objective_id", seeded("obj", tenant));
          row.put("version_no", 1);
          row.put("reviewer_id", ACTOR);
          row.put("decision", "DUYET");
          return row;
        },
        "okr.key_result",
        (tenant, seed) -> {
          Map<String, Object> row = periodic(seeded("kr", tenant));
          row.put("objective_id", seeded("obj-kr", tenant));
          row.put("code", "KR 1.1");
          row.put("title", "Kết quả mẫu");
          row.put("metric_type", "SO_LUONG");
          row.put("unit", "văn bản");
          row.put("target_value", new BigDecimal("10"));
          row.put("direction", "TANG");
          row.put("weight", new BigDecimal("0.5"));
          row.put("due_date", LocalDate.of(2027, 3, 31));
          row.put("verifier_person_id", ACTOR);
          row.put("checkin_frequency", "TUAN");
          return row;
        });
  }

  @BeforeAll
  void seedObjectivesForKeyResults() throws SQLException {
    try (Connection admin =
        DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      ensurePeriodPartitionsCreated(jdbcUrl(), admin, schemas());
      try (PreparedStatement st =
          admin.prepareStatement(
              "INSERT INTO okr.objective (id, tenant_id, period_id, period_bucket, owner_type,"
                  + " owner_unit_id, code, title, tier, created_by, updated_by) VALUES (?, ?, ?, ?,"
                  + " 'TAP_THE', ?, 'O9', 'Mục tiêu cho KR mẫu', 1, ?, ?)")) {
        for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
          st.setObject(1, seeded("obj-kr", tenant));
          st.setObject(2, tenant);
          st.setObject(3, PERIOD);
          st.setInt(4, SAMPLE_PERIOD_BUCKET);
          st.setObject(5, seeded("unit", tenant));
          st.setObject(6, ACTOR);
          st.setObject(7, ACTOR);
          st.executeUpdate();
        }
      }
    }
  }

  private static Map<String, Object> periodic(UUID id) {
    Map<String, Object> row = audited(id);
    row.put("period_id", PERIOD);
    row.put("period_bucket", SAMPLE_PERIOD_BUCKET);
    return row;
  }

  private static Map<String, Object> audited(UUID id) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    row.put("created_by", ACTOR);
    row.put("updated_by", ACTOR);
    return row;
  }

  private static UUID seeded(String kind, UUID tenantId) {
    return UUID.nameUUIDFromBytes((kind + "-" + tenantId).getBytes());
  }
}
