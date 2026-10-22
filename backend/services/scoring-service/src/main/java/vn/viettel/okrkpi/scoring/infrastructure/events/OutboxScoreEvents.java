package vn.viettel.okrkpi.scoring.infrastructure.events;

import java.nio.ByteBuffer;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.scoring.application.port.ScoreEvents;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.scoring.v1.DiemDaTinh;

/**
 * EVT-10 {@code scoring.score.computed.v1} qua {@code scoring.outbox_event}; khoá {@code
 * tenant:profileId} theo {@code contracts/events/catalog.yaml}, nên thực thể gốc là hồ sơ.
 */
public class OutboxScoreEvents implements ScoreEvents {

  static final String AGGREGATE = "APPRAISAL_PROFILE";

  private final OutboxWriter outbox;

  public OutboxScoreEvents(OutboxWriter outbox) {
    this.outbox = outbox;
  }

  @Override
  public void computed(ScoreBatch batch, ComputedScore score, long aggregateVersion) {
    DiemDaTinh payload =
        DiemDaTinh.newBuilder()
            .setScoreResultId(score.resultId())
            .setProfileId(score.profileId())
            .setJobKind(batch.jobKind().name())
            .setTotalScore(score.totalScore().setScale(3))
            .setAutoRatingCode(score.autoRatingCode())
            .setInputHash(ByteBuffer.wrap(score.input().hash()))
            .setPolicyContentHash(ByteBuffer.wrap(batch.policyContentHash()))
            .setAggregateVersion(Math.toIntExact(aggregateVersion))
            .build();
    outbox.append(
        EventEnvelope.of(
                EventTopics.EVT_10_DIEM_DA_TINH,
                TenantId.of(batch.tenantId()),
                AGGREGATE,
                score.profileId(),
                aggregateVersion,
                payload,
                Classification.C1)
            .withActor(ActorId.of(batch.actorId())));
  }
}
