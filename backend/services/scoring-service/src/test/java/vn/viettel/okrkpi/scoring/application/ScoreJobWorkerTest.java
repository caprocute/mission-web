package vn.viettel.okrkpi.scoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.scoring.application.port.ScoreChunkQueue;
import vn.viettel.okrkpi.scoring.application.port.ScoreChunkQueue.Claimed;
import vn.viettel.okrkpi.scoring.application.port.ScoreChunkQueue.Ref;
import vn.viettel.okrkpi.scoring.application.port.ScoreJobStore;
import vn.viettel.okrkpi.scoring.application.port.ScoringInputs;
import vn.viettel.okrkpi.scoring.domain.CoreScoringInput;
import vn.viettel.okrkpi.scoring.domain.JobScope;
import vn.viettel.okrkpi.scoring.domain.ScoreBatch;
import vn.viettel.okrkpi.scoring.domain.ScoreJobError;
import vn.viettel.okrkpi.scoring.domain.ScoreJobKind;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/** Điều phối lô của worker: bắt đầu tác vụ, xếp lô kế, ghi, thử lại và hàng đợi lỗi (DV-07-06). */
@SuppressWarnings("unchecked")
class ScoreJobWorkerTest {

  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000a001");
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID ACTOR = UUID.randomUUID();

  final ScoreChunkQueue queue = mock(ScoreChunkQueue.class);
  final ScoreJobStore jobs = mock(ScoreJobStore.class);
  final ScoringInputs inputs = mock(ScoringInputs.class);
  final ScoreJobRunner runner = mock(ScoreJobRunner.class);
  final ScoreRecorder recorder = mock(ScoreRecorder.class);
  final ScoreJobWorker worker =
      new ScoreJobWorker(
          queue,
          jobs,
          inputs,
          runner,
          recorder,
          TransactionOperations.withoutTransaction(),
          Duration.ofMinutes(10),
          Duration.ofSeconds(10));
  final Ref jobRef = new Ref(TENANT, 20271, UUID.randomUUID());
  final Ref chunk = new Ref(TENANT, 20271, UUID.randomUUID());

  ScoreChunkQueue.Job job(ScoreJobKind kind, String scope, int total) {
    return new ScoreChunkQueue.Job(jobRef, PERIOD, kind, scope, UUID.randomUUID(), ACTOR, total);
  }

  static CoreScoringInput in() {
    return new CoreScoringInput(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "DANG",
        null,
        null,
        false,
        false,
        null,
        List.of(),
        List.of(),
        0);
  }

  @Test
  void batDauTacVuThuongVaTacVuDoiChieu() {
    Ref other = new Ref(TENANT, 20271, UUID.randomUUID());
    Ref taken = new Ref(TENANT, 20271, UUID.randomUUID());
    Ref broken = new Ref(TENANT, 20271, UUID.randomUUID());
    when(queue.waitingJobs(8)).thenReturn(List.of(jobRef, other, taken, broken));
    when(queue.startJob(jobRef))
        .thenReturn(Optional.of(job(ScoreJobKind.CHOT_KY, JobScope.of("x").toDoc().json(), 0)));
    StringBuilder results = new StringBuilder();
    for (int i = 0; i < 501; i++) {
      results.append(i == 0 ? "" : ",").append('"').append(UUID.randomUUID()).append('"');
    }
    String recheck = "{\"recheckOf\":\"" + UUID.randomUUID() + "\",\"results\":[" + results + "]}";
    when(queue.startJob(other))
        .thenReturn(
            Optional.of(
                new ScoreChunkQueue.Job(
                    other,
                    PERIOD,
                    ScoreJobKind.KIEM_TRA_TAI_LAP,
                    recheck,
                    UUID.randomUUID(),
                    ACTOR,
                    1)));
    when(queue.startJob(taken)).thenReturn(Optional.empty());
    when(queue.startJob(broken)).thenThrow(new IllegalStateException("CSDL"));

    assertThat(worker.startWaiting(8)).isEqualTo(2);
    verify(queue).enqueue(jobRef, 1, null);
    verify(jobs).addTotal(TENANT, 20271, other.id(), 500);
    verify(queue).enqueue(other, 1, "0");
    verify(queue).enqueue(other, 2, "500");
    verify(jobs).progress(TENANT, 20271, other.id(), 0, 0, ACTOR);
  }

  @Test
  void loThanhCongXepLoKeVaGhiTrongMotGiaoDich() {
    Claimed c = new Claimed(chunk, 1, null, 1, job(ScoreJobKind.CHOT_KY, "{}", 0));
    when(queue.due(4)).thenReturn(List.of(new Ref(TENANT, 20271, UUID.randomUUID()), chunk));
    when(queue.claim(any(), any())).thenReturn(Optional.empty());
    when(queue.claim(eq(chunk), any())).thenReturn(Optional.of(c));
    List<CoreScoringInput> page = List.of(in());
    when(inputs.page(TENANT, PERIOD, null, ScoringInputs.PAGE_SIZE))
        .thenReturn(new ScoringInputs.Page(page, "c2"));
    ScoreBatch batch = mock(ScoreBatch.class);
    when(batch.items()).thenReturn(List.of());
    ScoreJobError err = new ScoreJobError(null, null, null, ScoreJobError.INPUT_INVALID, "x");
    when(runner.scorePage(c.job(), page))
        .thenReturn(new ScoreJobRunner.Computed(List.of(batch), List.of(err)));
    TenantContext.run(
        vn.viettel.okrkpi.shared.kernel.TenantId.of(TENANT),
        () -> assertThat(worker.runOne(4)).isTrue());

    verify(queue).enqueue(jobRef, 2, "c2");
    verify(queue).lockJob(jobRef);
    verify(queue).complete(chunk, 0);
    verify(jobs).addTotal(TENANT, 20271, jobRef.id(), 1);
    verify(recorder).record(batch);
    verify(queue).errors(jobRef, 1, 1, List.of(err));
    verify(jobs).progress(TENANT, 20271, jobRef.id(), 0, 1, ACTOR);

    when(queue.due(4)).thenReturn(List.of());
    assertThat(worker.runOne(4)).isFalse();
  }

  @Test
  void loiThiThuLaiGianCachRoiVaoHangDoiLoi() {
    Claimed first = new Claimed(chunk, 3, "c3", 2, job(ScoreJobKind.TAM_TINH, "{}", 0));
    when(inputs.page(any(), any(), any(), anyInt())).thenThrow(new IllegalStateException("503"));
    worker.process(first);
    verify(queue).retry(eq(chunk), eq(Duration.ofSeconds(20)), any());
    verify(queue, never()).fail(any(), any());

    // Lần thứ ba vẫn không đọc được trang: tác vụ lỗi, ghi một dòng lỗi của cả trang.
    Claimed last = new Claimed(chunk, 3, "c3", 3, job(ScoreJobKind.TAM_TINH, "{}", 0));
    worker.process(last);
    verify(queue).fail(eq(chunk), any());
    verify(jobs).markFailed(TENANT, 20271, jobRef.id(), ACTOR);
    ArgumentCaptor<List<ScoreJobError>> errors = ArgumentCaptor.forClass(List.class);
    verify(queue).errors(eq(jobRef), eq(3), eq(3), errors.capture());
    assertThat(errors.getValue())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.reasonCode()).isEqualTo(ScoreJobError.READ_FAILED);
              assertThat(e.profileId()).isNull();
            });
    verify(jobs).progress(TENANT, 20271, jobRef.id(), 0, 0, ACTOR);
  }

  @Test
  void ghiLoiSauKhiDocDuocTrangThiHoSoTrongPhamViVaoHangDoiLoi() {
    CoreScoringInput a = in();
    CoreScoringInput b = in();
    String scope = JobScope.of("x").withProfiles(List.of(a.profileId())).toDoc().json();
    Claimed c = new Claimed(chunk, 1, null, 3, job(ScoreJobKind.CHOT_KY, scope, 0));
    when(inputs.page(any(), any(), any(), anyInt()))
        .thenReturn(new ScoringInputs.Page(List.of(a, b), null));
    when(runner.scorePage(any(), any()))
        .thenReturn(new ScoreJobRunner.Computed(List.of(), List.of()));
    doThrow(new IllegalStateException("khoá")).when(queue).complete(any(), anyInt());
    worker.process(c);
    verify(queue, never()).enqueue(any(), anyInt(), any());
    ArgumentCaptor<List<ScoreJobError>> errors = ArgumentCaptor.forClass(List.class);
    verify(queue).errors(eq(jobRef), eq(1), eq(3), errors.capture());
    assertThat(errors.getValue())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.reasonCode()).isEqualTo(ScoreJobError.WRITE_FAILED);
              assertThat(e.profileId()).isEqualTo(a.profileId());
            });
    verify(jobs).addTotal(TENANT, 20271, jobRef.id(), 1);
    verify(jobs).progress(TENANT, 20271, jobRef.id(), 0, 1, ACTOR);
  }

  @Test
  void doiChieuTinhTheoViTriVaLoiHanThiTungKetQuaVaoHangDoiLoi() {
    UUID r1 = UUID.randomUUID();
    String scope = "{\"recheckOf\":\"" + UUID.randomUUID() + "\",\"results\":[\"" + r1 + "\"]}";
    Claimed ok = new Claimed(chunk, 1, "0", 1, job(ScoreJobKind.KIEM_TRA_TAI_LAP, scope, 1));
    when(runner.recheck(ok.job(), 0)).thenReturn(new ScoreJobRunner.Computed(List.of(), List.of()));
    worker.process(ok);
    verify(jobs, never()).addTotal(any(), anyInt(), any(), anyInt());
    verify(queue).complete(chunk, 0);

    Claimed bad = new Claimed(chunk, 1, null, 3, job(ScoreJobKind.KIEM_TRA_TAI_LAP, scope, 1));
    when(runner.recheck(bad.job(), 0)).thenThrow(new IllegalStateException("x"));
    worker.process(bad);
    ArgumentCaptor<List<ScoreJobError>> errors = ArgumentCaptor.forClass(List.class);
    verify(queue).errors(eq(jobRef), eq(1), eq(3), errors.capture());
    assertThat(errors.getValue())
        .singleElement()
        .satisfies(e -> assertThat(e.detail()).contains(r1.toString()));
    verify(jobs).progress(TENANT, 20271, jobRef.id(), 0, 1, ACTOR);
  }
}
