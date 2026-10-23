package vn.viettel.okrkpi.esign.application.seal;

import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thao tác ký tổ chức của SVC-10 (DV-13-10): dịch vụ nội bộ gửi băm SHA-256 của tài liệu cần đóng
 * dấu số tổ chức, ví dụ phần đầu gói chuyển hồ sơ liên cell của SVC-13. Chỉ nhận các mục đích đã
 * khai báo và băm đúng 32 byte; nội dung tài liệu không đi qua đây. Mỗi lần ký ghi một dòng nhật ký
 * có mục đích, mã tham chiếu và băm (không có dữ liệu cá nhân); sổ cái nghiệp vụ do bên gọi ghi.
 */
public class OrganizationSealService {

  private static final Logger LOG = LoggerFactory.getLogger(OrganizationSealService.class);

  /** Băm hoặc mục đích không hợp lệ. */
  public static final class InvalidSealRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidSealRequestException(String message) {
      super(message);
    }
  }

  private final OrganizationSealer sealer;
  private final Set<String> purposes;

  public OrganizationSealService(OrganizationSealer sealer, Set<String> purposes) {
    this.sealer = Objects.requireNonNull(sealer, "sealer");
    this.purposes = Set.copyOf(purposes);
  }

  public OrganizationSealer.Seal seal(
      UUID tenantId, String purpose, String reference, byte[] sha256) {
    if (purpose == null || !purposes.contains(purpose)) {
      throw new InvalidSealRequestException("Mục đích ký tổ chức không được phép");
    }
    if (sha256 == null || sha256.length != 32) {
      throw new InvalidSealRequestException("Băm cần ký phải là SHA-256");
    }
    if (reference != null && (reference.length() > 128 || !reference.matches("[A-Za-z0-9:._-]*"))) {
      throw new InvalidSealRequestException("Mã tham chiếu không hợp lệ");
    }
    OrganizationSealer.Seal seal = sealer.seal(tenantId, sha256);
    LOG.info(
        "Ký tổ chức purpose={} reference={} tenant={} sha256={} algorithm={}",
        purpose,
        reference,
        tenantId,
        HexFormat.of().formatHex(sha256),
        seal.algorithm());
    return seal;
  }
}
