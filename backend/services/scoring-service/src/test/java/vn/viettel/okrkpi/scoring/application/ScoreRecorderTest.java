package vn.viettel.okrkpi.scoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.scoring.application.port.ScoreEvents;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.application.port.ScoreResultStore;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;
import vn.viettel.okrkpi.scoring.domain.ScoreJob;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.scoring.domain.ScoreStatus;

/** Thứ tự ghi trong một lô: kết quả, EVT-10 cho kết quả OK kèm thứ tự, rồi tiến độ tác vụ. */
class ScoreRecorderTest {

  private static final UUID T = UUID.fromString("0192f1a2-0000-7000-8000-000000000001");
  private static final CanonicalDoc DOC = CanonicalDoc.of(Map.of());

  private final List<String> log = new ArrayList<>();
  private Map<UUID, Long> versions = Map.of();

  private final ScoreJobStore jobs =
      new ScoreJobStore() {
        @Override
        public UUID open(ScoreJob job) {
          log.add("open");
          return job.id();
        }

        @Override
        public void progress(UUID tenant, int bucket, UUID job, int done, int failed, UUID actor) {
          log.add("progress " + done + "/" + failed);
        }
      };
  private java.util.Set<UUID> unchanged = java.util.Set.of();
  private final ScoreResultStore results =
      new ScoreResultStore() {
        @Override
        public ScoreResultStore.Appended append(ScoreBatch batch) {
          log.add("append " + batch.items().size());
          return new ScoreResultStore.Appended(1, versions);
        }

        @Override
        public java.util.Set<UUID> unchanged(ScoreBatch batch) {
          return unchanged;
        }
      };
  private final ScoreEvents events =
      (batch, score, version) -> log.add("evt " + score.resultId() + "@" + version);
  private final ScoreRecorder recorder = new ScoreRecorder(jobs, results, events);

  @Test
  void ghiLoPhatSuKienChoKetQuaOkRoiCongTienDo() {
    ComputedScore ok = score(UUID.randomUUID(), ScoreStatus.OK);
    ComputedScore failed = score(UUID.randomUUID(), ScoreStatus.LOI);
    versions = Map.of(ok.resultId(), 3L);
    UUID job = UUID.randomUUID();
    assertThat(
            recorder.open(
                new ScoreJob(T, job, T, 20271, ScoreJobKind.TAM_TINH, DOC, T, null, 2, T)))
        .isEqualTo(job);

    assertThat(recorder.record(batch(List.of(ok, failed)))).isEqualTo(1);
    assertThat(log)
        .containsExactly("open", "append 2", "evt " + ok.resultId() + "@3", "progress 1/1");
  }

  @Test
  void ketQuaKhongDoiThiKhongGhiKhongPhatSuKien() {
    ComputedScore same = score(UUID.randomUUID(), ScoreStatus.OK);
    ComputedScore changed = score(UUID.randomUUID(), ScoreStatus.OK);
    unchanged = java.util.Set.of(same.resultId());
    versions = Map.of(changed.resultId(), 2L);

    assertThat(recorder.record(batch(List.of(same, changed)))).isEqualTo(1);
    assertThat(log).containsExactly("append 1", "evt " + changed.resultId() + "@2", "progress 2/0");

    log.clear();
    unchanged = java.util.Set.of(same.resultId(), changed.resultId());
    assertThat(recorder.record(batch(List.of(same, changed)))).isZero();
    assertThat(log).containsExactly("progress 2/0");
  }

  @Test
  void thieuThuTuKetQuaThiDungLai() {
    ComputedScore ok = score(UUID.randomUUID(), ScoreStatus.OK);
    assertThatThrownBy(() -> recorder.record(batch(List.of(ok))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(log).containsExactly("append 1");
  }

  @Test
  void ketQuaGhiLoBatBienPhienBan() {
    Map<UUID, Long> source = new java.util.HashMap<>(Map.of(T, 1L));
    ScoreResultStore.Appended appended = new ScoreResultStore.Appended(0, source);
    source.clear();
    assertThat(appended.versions()).containsEntry(T, 1L);
  }

  private static ComputedScore score(UUID id, ScoreStatus status) {
    boolean ok = status == ScoreStatus.OK;
    return new ComputedScore(
        id,
        T,
        "DANG",
        T,
        DOC,
        DOC,
        ok ? BigDecimal.TEN : null,
        ok ? "HTNV" : null,
        status,
        List.of());
  }

  private static ScoreBatch batch(List<ComputedScore> items) {
    return new ScoreBatch(
        T, T, ScoreJobKind.TAM_TINH, T, 20271, T, new byte[32], "v1", T, Instant.EPOCH, items);
  }
}
