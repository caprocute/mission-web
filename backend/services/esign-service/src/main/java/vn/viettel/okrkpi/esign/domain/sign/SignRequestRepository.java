package vn.viettel.okrkpi.esign.domain.sign;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Kho yêu cầu ký; mọi truy vấn chạy trong ngữ cảnh tenant (RLS). */
public interface SignRequestRepository {

  Optional<SignRequest> findById(UUID id);

  /** Khoá dòng để xử lý; bỏ qua khi worker khác đang giữ. */
  Optional<SignRequest> lockForProcessing(UUID id);

  boolean existsPending(UUID signerPersonId, String sourceModule, UUID sourceRefId);

  /**
   * Trang yêu cầu của người ký, mới nhất trước; {@code afterId} là con trỏ (mã cuối trang trước).
   */
  List<SignRequest> listBySigner(
      UUID signerPersonId, SignRequestStatus status, SignPurpose purpose, UUID afterId, int limit);

  void insert(SignRequest request, UUID actor);

  /** Cập nhật theo {@code row_version}; ném {@link ConcurrentModification} khi đã đổi. */
  void update(SignRequest request, UUID actor);

  /**
   * Khoá một tài liệu chưa ký của lô (DV-10-06); bỏ qua khi worker khác đang giữ hoặc tài liệu đã
   * xong. Khoá giữ tới hết giao dịch, nên hai worker không ký cùng một tài liệu.
   */
  default Optional<PendingItem> lockPendingItem(UUID itemId) {
    throw new UnsupportedOperationException("Kho yêu cầu ký này không hỗ trợ ký lô (DV-10-06)");
  }

  /** Ghi chữ ký của tài liệu trong lô; chỉ ghi khi tài liệu chưa có chữ ký. */
  default boolean itemSigned(
      UUID itemId, byte[] signature, String objectKey, UUID certificateId, Instant at) {
    throw new UnsupportedOperationException("Kho yêu cầu ký này không hỗ trợ ký lô (DV-10-06)");
  }

  /** Lỗi tạm thời: tăng lượt thử và hẹn thử lại. */
  default void itemRetry(UUID itemId, int attempts, Instant nextAttemptAt, String error) {
    throw new UnsupportedOperationException("Kho yêu cầu ký này không hỗ trợ ký lô (DV-10-06)");
  }

  /** Lỗi vĩnh viễn hoặc hết lượt thử: tài liệu {@code LOI}, không làm hỏng cả lô. */
  default void itemFailed(UUID itemId, int attempts, String error) {
    throw new UnsupportedOperationException("Kho yêu cầu ký này không hỗ trợ ký lô (DV-10-06)");
  }

  /** Số tài liệu của lô còn chờ ký. */
  default int pendingItems(UUID requestId) {
    throw new UnsupportedOperationException("Kho yêu cầu ký này không hỗ trợ ký lô (DV-10-06)");
  }

  /**
   * Phần đầu của yêu cầu ký lô, không nạp danh sách tài liệu (worker ký lô đọc cho mỗi tài liệu).
   */
  default Optional<BatchHeader> batchHeader(UUID requestId) {
    throw new UnsupportedOperationException("Kho yêu cầu ký này không hỗ trợ ký lô (DV-10-06)");
  }

  /** Phần đầu của yêu cầu ký lô. */
  record BatchHeader(
      UUID id,
      UUID tenantId,
      UUID signerPersonId,
      SignPurpose purpose,
      String sourceModule,
      UUID sourceRefId,
      ProviderCode provider,
      String providerTxnId,
      SignRequestStatus status,
      Instant batchAuthorizedAt) {}

  /** Tài liệu chờ ký đã khoá cùng mã yêu cầu của nó. */
  record PendingItem(UUID requestId, SignItem item) {}

  /** Ghi đồng thời. */
  final class ConcurrentModification extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConcurrentModification(UUID id) {
      super("Yêu cầu ký " + id + " vừa được cập nhật bởi tiến trình khác");
    }
  }
}
