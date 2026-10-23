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

  /**
   * Cách xác nhận một lần cho lô mà nhà cung cấp hỗ trợ (DV-10-06, ADR-31); rỗng khi không ký lô
   * được. {@code SAD}: nhà cung cấp ký từ xa cấp một phiên ủy quyền ký nhiều băm sau khi người ký
   * xác nhận trên thiết bị; {@code STEP_UP}: HSM của tổ chức, lần xác thực nâng cao của người quyết
   * định khi tạo yêu cầu là xác nhận cho cả lô.
   */
  default Optional<BatchMode> batchMode() {
    return Optional.empty();
  }

  /**
   * Mở phiên ủy quyền cho cả lô; người ký xác nhận một lần. {@link #poll} của phiên này trả {@link
   * SignStatus#SIGNED} khi đã được ủy quyền. Không ký tài liệu nào.
   */
  default SignSession beginBatch(SignRequest request) {
    throw new SignatureProviderException(id() + " không hỗ trợ ký lô", true);
  }

  /**
   * Ký một tài liệu của lô đã được ủy quyền. Thử lại cùng tài liệu phải an toàn (esign không lưu
   * hai chữ ký cho một tài liệu).
   *
   * @throws SignatureProviderException {@code permanent} khi tài liệu không ký được, còn lại là lỗi
   *     tạm thời để thử lại
   */
  default SignedItem signItem(String sessionId, SignRequest.Document document) {
    throw new SignatureProviderException(id() + " không hỗ trợ ký lô", true);
  }

  /** Cách xác nhận một lần cho lô. */
  enum BatchMode {
    SAD,
    STEP_UP
  }
}
