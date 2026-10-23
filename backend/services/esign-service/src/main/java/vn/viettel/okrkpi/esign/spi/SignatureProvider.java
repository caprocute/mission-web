package vn.viettel.okrkpi.esign.spi;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Giao diện nhà cung cấp chữ ký số (TL-01 "SVC-10 esign-service", "Thiết kế giao diện nhà cung
 * cấp"). Mỗi nhà cung cấp (MySign, Viettel-CA, CA chuyên dùng Chính phủ, Ban Cơ yếu) là một bản
 * hiện thực; esign-service chọn theo {@link #id()}. Khoá riêng của người ký luôn nằm ở phía nhà
 * cung cấp hoặc thiết bị người ký, không bao giờ đi qua giao diện này.
 */
public interface SignatureProvider {

  /** Mã nhà cung cấp: {@code mysign}, {@code viettel-ca}, {@code gov-ca}, {@code bcy}. */
  String id();

  Set<SignatureFormat> supportedFormats();

  /**
   * Nhà cung cấp có đang nhận yêu cầu không (ví dụ mạch chưa ngắt). Chỉ đọc trạng thái cục bộ,
   * không gọi ra ngoài, để API tạo yêu cầu trả lỗi ngay thay vì để yêu cầu chờ tới hết hạn.
   */
  default boolean available() {
    return true;
  }

  /**
   * Tạo phiên ký; người ký có thể phải xác nhận trên điện thoại. Không chờ ký. Từ DV-10-02, worker
   * thăm dò gọi thao tác này, không phải luồng xử lý API.
   *
   * @throws SignatureProviderException khi nhà cung cấp từ chối ({@code permanent}) hoặc lỗi tạm
   */
  SignSession begin(SignRequest request);

  /** Trạng thái phiên: đang chờ, đã ký, bị từ chối, hết hạn. */
  SignStatus poll(String sessionId);

  /** Lấy chữ ký hoặc tài liệu đã ký của phiên ở trạng thái {@link SignStatus#SIGNED}. */
  List<SignedItem> collect(String sessionId);

  /**
   * Chứng thư của người ký đang có ở nhà cung cấp, kèm trạng thái thu hồi (DV-10-04, để liệt kê
   * chứng thư ở mọi nhà cung cấp được bật). Mặc định không liệt kê được.
   */
  default List<SignedItem.Certificate> certificates(UUID tenantId, UUID personId) {
    return List.of();
  }

  /**
   * Kiểm lại trạng thái thu hồi (OCSP/CRL) của chứng thư đã lưu trước khi ký (DV-10-04). Rỗng khi
   * chứng thư không thuộc chuỗi gốc của nhà cung cấp hoặc nhà cung cấp không tự kiểm được.
   */
  default Optional<SignedItem.Certificate> recheck(byte[] certificateDer) {
    return Optional.empty();
  }

  /**
   * Nhà cung cấp tự kiểm chuỗi gốc và thu hồi của chứng thư ({@link #recheck}); khi đó chứng thư đã
   * lưu mà nhà cung cấp không nhận ra là của nhà cung cấp khác và không được chọn để ký (DV-10-04).
   */
  default boolean checksCertificates() {
    return false;
  }
}
