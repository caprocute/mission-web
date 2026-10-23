package vn.viettel.okrkpi.esign.infrastructure.provider.hsm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình HSM PKCS#11 ({@code okrkpi.esign.hsm.*}). PIN nạp từ OpenBao qua biến môi trường {@code
 * ESIGN_HSM_PIN}; không có giá trị mặc định và không bao giờ ghi nhật ký.
 *
 * @param enabled bật nhà cung cấp HSM
 * @param library đường dẫn thư viện PKCS#11 (SoftHSM2 ở dev, thư viện của hãng HSM ở production)
 * @param slot mã slot, ưu tiên hơn {@code slotListIndex} khi có
 * @param slotListIndex chỉ số trong danh sách slot có token
 * @param pin PIN người dùng của token
 * @param orgKeyAlias nhãn khoá chữ ký tổ chức (dấu cơ quan)
 * @param sealKeyAlias nhãn khoá niêm phong sổ cái của cell
 * @param maxSessions số lệnh ký đồng thời tối đa (pool phiên PKCS#11)
 * @param acquireTimeout thời gian chờ phiên rảnh
 * @param provisionDevKeys chỉ dev: tự tạo khoá và chứng thư tự ký khi token chưa có
 */
@ConfigurationProperties(prefix = "okrkpi.esign.hsm")
public record HsmProperties(
    boolean enabled,
    String library,
    Integer slot,
    Integer slotListIndex,
    String pin,
    String orgKeyAlias,
    String sealKeyAlias,
    Integer maxSessions,
    Duration acquireTimeout,
    boolean provisionDevKeys) {

  public HsmProperties {
    orgKeyAlias = orgKeyAlias == null || orgKeyAlias.isBlank() ? "mission-org-sign" : orgKeyAlias;
    sealKeyAlias = sealKeyAlias == null || sealKeyAlias.isBlank() ? "mission-seal" : sealKeyAlias;
    maxSessions = maxSessions == null || maxSessions < 1 ? 8 : maxSessions;
    acquireTimeout = acquireTimeout == null ? Duration.ofSeconds(2) : acquireTimeout;
  }

  /** Cấu hình SunPKCS11; khoá sinh ra luôn nằm trên token, nhạy cảm và không trích xuất được. */
  String sunPkcs11Config() {
    if (library == null || library.isBlank()) {
      throw new IllegalStateException("Chưa cấu hình thư viện PKCS#11 (okrkpi.esign.hsm.library)");
    }
    StringBuilder cfg = new StringBuilder();
    cfg.append("name=mission-hsm\n");
    cfg.append("library=").append(library).append('\n');
    if (slot != null) {
      cfg.append("slot=").append(slot).append('\n');
    } else {
      cfg.append("slotListIndex=").append(slotListIndex == null ? 0 : slotListIndex).append('\n');
    }
    cfg.append("attributes(generate,CKO_PRIVATE_KEY,CKK_EC)={\n")
        .append("  CKA_TOKEN=true\n")
        .append("  CKA_PRIVATE=true\n")
        .append("  CKA_SENSITIVE=true\n")
        .append("  CKA_EXTRACTABLE=false\n")
        .append("  CKA_SIGN=true\n")
        .append("}\n");
    cfg.append("attributes(generate,CKO_PUBLIC_KEY,CKK_EC)={\n  CKA_TOKEN=true\n}\n");
    return cfg.toString();
  }

  @Override
  public String toString() {
    // Không in PIN.
    return "HsmProperties[enabled="
        + enabled
        + ", library="
        + library
        + ", slot="
        + slot
        + ", slotListIndex="
        + slotListIndex
        + ", orgKeyAlias="
        + orgKeyAlias
        + ", sealKeyAlias="
        + sealKeyAlias
        + ", maxSessions="
        + maxSessions
        + "]";
  }
}
