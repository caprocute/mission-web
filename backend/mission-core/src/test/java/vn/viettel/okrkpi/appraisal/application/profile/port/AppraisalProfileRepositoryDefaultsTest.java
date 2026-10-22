package vn.viettel.okrkpi.appraisal.application.profile.port;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Bản mặc định của {@code insertRating} có lô quyết định (CORE-04-07). */
class AppraisalProfileRepositoryDefaultsTest {

  @Test
  void khongCoLoThiGoiBanCuConCoLoThiTuChoi() {
    AppraisalProfileRepository repo = mock(AppraisalProfileRepository.class, CALLS_REAL_METHODS);
    TenantId t = TenantId.of(UUID.randomUUID());
    Instant at = Instant.now();
    repo.insertRating(t, null, null, null, null, null, at, null);
    verify(repo).insertRating(t, null, null, null, null, null, at);
    assertThatThrownBy(
            () -> repo.insertRating(t, null, null, null, null, null, at, UUID.randomUUID()))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
