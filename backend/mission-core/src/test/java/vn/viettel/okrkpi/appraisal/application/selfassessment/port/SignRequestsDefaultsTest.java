package vn.viettel.okrkpi.appraisal.application.selfassessment.port;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Chưa cấu hình dịch vụ ký số thì các mục đích ký mới báo lỗi rõ ràng. */
class SignRequestsDefaultsTest {

  @Test
  void chuaCauHinhThiBaoLoi() {
    SignRequests signs = (t, id, provider, key, auth) -> UUID.randomUUID();
    TenantId t = TenantId.of(UUID.randomUUID());
    assertThatThrownBy(() -> signs.requestAppraisal(t, UUID.randomUUID(), null, "k", null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> signs.requestDecision(t, UUID.randomUUID(), null, "k", null))
        .isInstanceOf(IllegalStateException.class);
  }
}
