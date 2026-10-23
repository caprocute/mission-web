package vn.viettel.okrkpi.esign.domain.sign;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Kho chưa hỗ trợ ký lô (DV-10-06) báo rõ thay vì im lặng bỏ qua tài liệu của lô. */
class SignRequestRepositoryDefaultsTest {

  private final SignRequestRepository plain =
      new SignRequestRepository() {
        @Override
        public Optional<SignRequest> findById(UUID id) {
          return Optional.empty();
        }

        @Override
        public Optional<SignRequest> lockForProcessing(UUID id) {
          return Optional.empty();
        }

        @Override
        public boolean existsPending(UUID signer, String module, UUID ref) {
          return false;
        }

        @Override
        public List<SignRequest> listBySigner(
            UUID signer, SignRequestStatus status, SignPurpose purpose, UUID afterId, int limit) {
          return List.of();
        }

        @Override
        public void insert(SignRequest request, UUID actor) {}

        @Override
        public void update(SignRequest request, UUID actor) {}
      };

  @Test
  void phuongThucKyLoMacDinhTuChoi() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> plain.lockPendingItem(id))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> plain.itemSigned(id, new byte[1], "k", id, Instant.EPOCH))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> plain.itemRetry(id, 1, Instant.EPOCH, "x"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> plain.itemFailed(id, 1, "x"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> plain.pendingItems(id))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> plain.batchHeader(id))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
