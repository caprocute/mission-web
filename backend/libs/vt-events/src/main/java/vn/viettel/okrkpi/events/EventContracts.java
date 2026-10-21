package vn.viettel.okrkpi.events;

import org.apache.avro.util.ClassSecurityValidator;
import org.apache.avro.util.ClassSecurityValidator.ClassSecurityPredicate;

/**
 * NEN-007 · Điểm vào dùng chung của thư viện hợp đồng sự kiện.
 *
 * <p>Từ Avro 1.12.1, {@code SpecificData} chỉ nạp lớp sinh thuộc danh sách tin cậy
 * (ClassSecurityValidator, vá CVE-2024-47561); lớp ngoài danh sách bị từ chối bằng {@link
 * SecurityException} khi đọc. Service tuần tự hoá sự kiện (outbox NEN-011, consumer NEN-012) gọi
 * {@link #trustContractPackages()} một lần khi khởi động, thay cho việc đặt thuộc tính hệ thống
 * {@code org.apache.avro.SERIALIZABLE_PACKAGES}.
 */
public final class EventContracts {

  /** Gói gốc của mọi lớp sinh từ contracts/events/avro (namespace {@code vn.vtokr.<miền>.v1}). */
  public static final String CONTRACT_PACKAGE_PREFIX = "vn.vtokr.";

  /** Tin cậy đúng các lớp sinh từ hợp đồng sự kiện, không mở rộng sang gói khác. */
  static final ClassSecurityPredicate CONTRACT_CLASSES =
      c -> c.getName().startsWith(CONTRACT_PACKAGE_PREFIX);

  private static boolean installed;

  private EventContracts() {}

  /**
   * Thêm lớp hợp đồng sự kiện vào danh sách tin cậy toàn cục của Avro, giữ nguyên danh sách hiện
   * có. Gọi nhiều lần không có tác dụng phụ.
   */
  public static synchronized void trustContractPackages() {
    if (installed) {
      return;
    }
    ClassSecurityValidator.setGlobal(
        ClassSecurityValidator.composite(ClassSecurityValidator.getGlobal(), CONTRACT_CLASSES));
    installed = true;
  }
}
