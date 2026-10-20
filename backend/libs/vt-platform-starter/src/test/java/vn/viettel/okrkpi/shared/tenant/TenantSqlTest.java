package vn.viettel.okrkpi.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.viettel.okrkpi.shared.observability.SqlCommenterStatementInspector;

/** NEN-010 mục 6: câu đặt app.tenant_id mức phiên bị phát hiện, câu cục bộ giao dịch được phép. */
class TenantSqlTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SET app.tenant_id = '00000000-0000-0000-0000-000000000001'",
        "set app.tenant_id to 'x'",
        "SET SESSION app.tenant_id = 'x'",
        "set \"app.tenant_id\" = 'x'",
        "RESET app.tenant_id",
        "select set_config('app.tenant_id', ?, false)",
        "SELECT SET_CONFIG( 'app.tenant_id' , 'x' , FALSE )"
      })
  void phatHienCauMucPhien(String sql) {
    assertThat(TenantSql.isSessionLevelTenantStatement(sql)).isTrue();
    assertThatThrownBy(() -> TenantSql.requireTransactionLocal(sql))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new SqlCommenterStatementInspector().inspect(sql))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SET LOCAL app.tenant_id = 'x'",
        "select set_config('app.tenant_id', ?, true)",
        "select set_config(?, ?, true)",
        "select current_setting('app.tenant_id', true)",
        "SET statement_timeout = '5s'",
        "select * from task where tenant_id = ?"
      })
  void choPhepCauCucBoGiaoDich(String sql) {
    assertThat(TenantSql.isSessionLevelTenantStatement(sql)).isFalse();
    assertThat(TenantSql.requireTransactionLocal(sql)).isEqualTo(sql);
  }

  @org.junit.jupiter.api.Test
  void cauLenhCuaThuVienLaCucBoGiaoDich() {
    assertThat(TenantSql.setLocalStatement()).endsWith("true)");
    assertThat(TenantSql.isSessionLevelTenantStatement(TenantSql.setLocalStatement())).isFalse();
  }
}
