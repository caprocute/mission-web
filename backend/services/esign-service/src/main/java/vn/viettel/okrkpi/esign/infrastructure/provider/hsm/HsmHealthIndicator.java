package vn.viettel.okrkpi.esign.infrastructure.provider.hsm;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Readiness {@code hsm} (DV-10-03): ký thử một băm cố định bằng khoá tổ chức và kiểm lại; HSM dừng
 * hoặc mất phiên thì DOWN để Kubernetes rút pod khỏi Service. Khi HSM tắt bằng cấu hình (dev không
 * có token) chỉ báo UP kèm {@code disabled}, vì nhóm readiness luôn liệt kê chỉ báo này.
 */
public class HsmHealthIndicator implements HealthIndicator {

  private final ObjectProvider<HsmPkcs11Signer> signer;

  public HsmHealthIndicator(ObjectProvider<HsmPkcs11Signer> signer) {
    this.signer = signer;
  }

  @Override
  public Health health() {
    HsmPkcs11Signer s = signer.getIfAvailable();
    if (s == null) {
      return Health.up().withDetail("hsm", "disabled").build();
    }
    return s.probe()
        ? Health.up().build()
        : Health.down().withDetail("hsm", "probe-failed").build();
  }
}
