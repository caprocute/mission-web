package vn.viettel.okrkpi.platform.db.migration.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RoleBootstrapPlanTest {

  @Test
  void boTriCsdlDuLieuTenantCoMuoiLamSchema() {
    assertThat(DatabaseLayout.TENANT_DATA.schemas()).hasSize(15);
    assertThat(DatabaseLayout.CELL_SHARED.schemas()).containsExactly("notify", "integ");
    assertThat(DatabaseLayout.fromCode("td")).isEqualTo(DatabaseLayout.TENANT_DATA);
    assertThatThrownBy(() -> DatabaseLayout.fromCode("x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void khongCoMatKhauTrongCauLenh() {
    List<String> sql =
        RoleBootstrapPlan.statements("td_hcm", DatabaseLayout.TENANT_DATA, RoleLimits.load());
    assertThat(sql).noneMatch(s -> s.toUpperCase(java.util.Locale.ROOT).contains("PASSWORD"));
    assertThat(sql).contains("ALTER ROLE svc_task SET statement_timeout = '5s'");
    assertThat(sql)
        .anyMatch(
            s -> s.startsWith("ALTER ROLE svc_task LOGIN") && s.endsWith("CONNECTION LIMIT 400"));
    assertThat(sql)
        .anyMatch(s -> s.startsWith("ALTER ROLE cdc_task") && s.contains(" REPLICATION "));
  }

  @Test
  void cellSharedKhongCoNgoaiLeMissionCore() {
    List<String> sql =
        RoleBootstrapPlan.statements("cell_shared", DatabaseLayout.CELL_SHARED, RoleLimits.load());
    assertThat(sql).noneMatch(s -> s.contains("org_replica") || s.contains("keyring"));
  }

  @Test
  void gioiHanKetNoiMacDinh() {
    RoleLimits limits = RoleLimits.load();
    assertThat(limits.limit("svc", "task")).isEqualTo(400);
    assertThat(limits.limit("ro", "okr")).isEqualTo(20);
    assertThat(limits.limit("cdc", "okr")).isEqualTo(5);
    assertThat(limits.limit("mig", "okr")).isEqualTo(5);
  }

  @Test
  void tuChoiTenCsdlKhongHopLe() {
    assertThatThrownBy(
            () ->
                RoleBootstrapPlan.statements(
                    "td; DROP", DatabaseLayout.TENANT_DATA, RoleLimits.load()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void chiSchemaGhiLoMoiCoQuyenBangTam() {
    assertThat(RoleBootstrapPlan.schemaStatements("scoring", RoleLimits.load()))
        .anyMatch(sql -> sql.contains("GRANT TEMPORARY") && sql.contains("svc_scoring"));
    assertThat(RoleBootstrapPlan.schemaStatements("task", RoleLimits.load()))
        .noneMatch(sql -> sql.contains("TO svc_task") && sql.contains("TEMPORARY"));
  }
}
