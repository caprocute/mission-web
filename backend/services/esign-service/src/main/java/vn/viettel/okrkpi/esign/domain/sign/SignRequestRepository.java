package vn.viettel.okrkpi.esign.domain.sign;

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

  /** Ghi đồng thời. */
  final class ConcurrentModification extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConcurrentModification(UUID id) {
      super("Yêu cầu ký " + id + " vừa được cập nhật bởi tiến trình khác");
    }
  }
}
