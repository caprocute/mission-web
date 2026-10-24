package vn.viettel.okrkpi.org.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ADR-34: công tắc đồng bộ ngoài chỉ chặn lối ghi của đồng bộ, không chặn thao tác quản trị trong ứng dụng. */
class ExternalSyncGateTest {

  @Test
  void blocksSyncWritesWhenDisabled() {
    assertThat(ExternalSyncGate.blocked(false, "POST", "/scim/v2/Users")).isTrue();
    assertThat(ExternalSyncGate.blocked(false, "GET", "/scim/v2/ServiceProviderConfig")).isTrue();
    assertThat(ExternalSyncGate.blocked(false, "POST", "/api/v1/org/sync-runs")).isTrue();
    assertThat(ExternalSyncGate.blocked(false, "POST", "/api/v1/org/sync-runs/abc/retry")).isTrue();
    assertThat(ExternalSyncGate.blocked(false, "PUT", "/api/v1/org/sync-schedules")).isTrue();
    assertThat(ExternalSyncGate.blocked(false, "POST", "/internal/v1/org/sync-runs/abc/batches")).isTrue();
  }

  @Test
  void keepsReadsAndInAppAdministration() {
    assertThat(ExternalSyncGate.blocked(false, "GET", "/api/v1/org/sync-runs")).isFalse();
    assertThat(ExternalSyncGate.blocked(false, "GET", "/api/v1/org/discrepancies")).isFalse();
    assertThat(ExternalSyncGate.blocked(false, "POST", "/api/v1/org/users")).isFalse();
    assertThat(ExternalSyncGate.blocked(false, "POST", "/api/v1/org/assignments")).isFalse();
    assertThat(ExternalSyncGate.blocked(false, "POST", "/api/v1/org/units/x/changes")).isFalse();
  }

  @Test
  void allowsEverythingWhenEnabled() {
    assertThat(ExternalSyncGate.blocked(true, "POST", "/scim/v2/Users")).isFalse();
    assertThat(ExternalSyncGate.blocked(true, "POST", "/api/v1/org/sync-runs")).isFalse();
  }
}
