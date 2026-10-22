package vn.viettel.okrkpi.scoring.infrastructure.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.scoring.domain.ScoreStatus;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.scoring.v1.DiemDaTinh;

/** EVT-10 mang đúng khoá {@code tenant:profileId}, điểm 3 chữ số thập phân và hai băm. */
class OutboxScoreEventsTest {

  @Test
  void phatDiemDaTinhTheoHoSo() {
    OutboxWriter outbox = mock(OutboxWriter.class);
    UUID tenant = UUID.fromString("0192f1a2-0000-7000-8000-000000000001");
    UUID profile = UUID.fromString("0192f1a2-0000-7000-8000-000000000002");
    CanonicalDoc input = CanonicalDoc.of(Map.of("a", 1));
    ComputedScore score =
        new ComputedScore(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "DANG",
            profile,
            input,
            CanonicalDoc.of(Map.of()),
            new BigDecimal("96.75"),
            "HTXSNV",
            ScoreStatus.OK,
            List.of());
    byte[] policy = new byte[32];
    policy[0] = 7;
    ScoreBatch batch =
        new ScoreBatch(
            tenant,
            UUID.randomUUID(),
            ScoreJobKind.CHOT_KY,
            UUID.randomUUID(),
            20271,
            UUID.randomUUID(),
            policy,
            "v1",
            UUID.randomUUID(),
            Instant.EPOCH,
            List.of(score));

    new OutboxScoreEvents(outbox).computed(batch, score, 4);

    ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
    verify(outbox).append(captor.capture());
    EventEnvelope e = captor.getValue();
    assertThat(e.topic()).isEqualTo("scoring.score.computed.v1");
    assertThat(e.eventKey()).isEqualTo(tenant + ":" + profile);
    assertThat(e.aggregateVersion()).isEqualTo(4);
    DiemDaTinh payload = (DiemDaTinh) e.payload();
    assertThat(payload.getTotalScore()).isEqualTo(new BigDecimal("96.750"));
    assertThat(payload.getJobKind()).isEqualTo("CHOT_KY");
    assertThat(payload.getInputHash().array()).isEqualTo(input.hash());
    assertThat(payload.getPolicyContentHash().get(0)).isEqualTo((byte) 7);
    assertThat(payload.getAggregateVersion()).isEqualTo(4);
  }
}
