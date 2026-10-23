package vn.viettel.okrkpi.scoring.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore.JobState;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.scoring.domain.ScoreJobStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Mã tác vụ {@code score-<uuid>} để cổng cell định tuyến {@code /api/v1/jobs} (DV-01-04). */
class ScoreJobSourceTest {

  private final ScoreJobStore store = mock(ScoreJobStore.class);
  private final ScoreJobSource source =
      new ScoreJobSource(store, TransactionOperations.withoutTransaction());
  private final TenantId tenant = TenantId.of(UUID.randomUUID());

  @Test
  void prefixedAndBareIdsFindTheJob_otherPrefixDoesNotQuery() {
    UUID id = UUID.randomUUID();
    JobState s =
        new JobState(
            id,
            0,
            ScoreJobKind.TAM_TINH,
            ScoreJobStatus.DANG_CHAY,
            4,
            1,
            0,
            UUID.randomUUID(),
            Instant.now(),
            Instant.now());
    when(store.find(id)).thenReturn(Optional.of(s));

    assertThat(source.find(tenant, "score-" + id))
        .get()
        .extracting(t -> t.job().id())
        .isEqualTo("score-" + id);
    assertThat(source.find(tenant, id.toString())).isPresent();
    assertThat(source.find(tenant, "doc-" + id)).isEmpty();
    verify(store, org.mockito.Mockito.times(2)).find(id);
  }
}
