package vn.viettel.okrkpi.scoring.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Worker tính điểm theo hàng đợi lô (DV-07-06, NFR-06): bắt đầu tác vụ chờ bằng lô đầu (hoặc chia
 * danh sách kết quả của {@code KIEM_TRA_TAI_LAP} thành lô 500), nhận lô đến hạn của mọi tenant, đọc
 * trang đầu vào rồi xếp ngay lô kế tiếp (để worker khác đọc song song), tính bằng {@link
 * ScoreJobRunner}, và ghi trong một giao dịch: kết quả, vết tính, EVT-10 qua outbox, tổng và tiến
 * độ tác vụ, hàng đợi lỗi, trạng thái lô. Lô lỗi được trả lại với giãn cách tăng dần; quá {@value
 * #MAX_ATTEMPTS} lần thì lô lỗi hẳn và hồ sơ của lô vào hàng đợi lỗi. Mọi thao tác ghi chạy trong
 * ngữ cảnh tenant của lô (RLS).
 */
public class ScoreJobWorker {

  private static final Logger LOG = LoggerFactory.getLogger(ScoreJobWorker.class);

  /** Số lần nhận một lô tối đa (lần đầu và hai lần thử lại). */
  public static final int MAX_ATTEMPTS = 3;

  private final ScoreChunkQueue queue;
  private final ScoreJobStore jobs;
  private final ScoringInputs inputs;
  private final ScoreJobRunner runner;
  private final ScoreRecorder recorder;
  private final TransactionOperations tx;
  private final Duration lease;
  private final Duration backoff;

  public ScoreJobWorker(
      ScoreChunkQueue queue,
      ScoreJobStore jobs,
      ScoringInputs inputs,
      ScoreJobRunner runner,
      ScoreRecorder recorder,
      TransactionOperations tx,
      Duration lease,
      Duration backoff) {
    this.queue = Objects.requireNonNull(queue, "queue");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.inputs = Objects.requireNonNull(inputs, "inputs");
    this.runner = Objects.requireNonNull(runner, "runner");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.lease = Objects.requireNonNull(lease, "lease");
    this.backoff = Objects.requireNonNull(backoff, "backoff");
  }

  /** Bắt đầu các tác vụ đang chờ; trả số tác vụ đã bắt đầu. */
  public int startWaiting(int limit) {
    List<Ref> waiting = tx.execute(t -> queue.waitingJobs(limit));
    int started = 0;
    for (Ref ref : waiting == null ? List.<Ref>of() : waiting) {
      try {
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        TenantContext.run(TenantId.of(ref.tenantId()), () -> ok.set(tx.execute(t -> start(ref))));
        started += Boolean.TRUE.equals(ok.get()) ? 1 : 0;
      } catch (RuntimeException e) {
        LOG.error("Không bắt đầu được tác vụ tính {}", ref.id(), e);
      }
    }
    return started;
  }

  private boolean start(Ref ref) {
    Optional<ScoreChunkQueue.Job> job = queue.startJob(ref);
    if (job.isEmpty()) {
      return false;
    }
    ScoreChunkQueue.Job j = job.get();
    if (j.kind() != ScoreJobKind.KIEM_TRA_TAI_LAP) {
      queue.enqueue(ref, 1, null);
      return true;
    }
    // Bên mở tác vụ đối chiếu (DL-12 R4) có thể đã ghi tổng bằng số kết quả cần tính lại.
    int total = JobScope.parse(j.scope()).results().size();
    if (total > j.totalItems()) {
      jobs.addTotal(ref.tenantId(), ref.periodBucket(), ref.id(), total - j.totalItems());
    }
    for (int offset = 0, seq = 1; offset < total; offset += ScoreJobRunner.RECHECK_CHUNK, seq++) {
      queue.enqueue(ref, seq, Integer.toString(offset));
    }
    jobs.progress(ref.tenantId(), ref.periodBucket(), ref.id(), 0, 0, j.actorId());
    return true;
  }

  /**
   * Nhận và xử lý một lô đến hạn.
   *
   * @return {@code false} khi không còn lô nào nhận được
   */
  public boolean runOne(int candidates) {
    List<Ref> due = tx.execute(t -> queue.due(candidates));
    for (Ref ref : due == null ? List.<Ref>of() : due) {
      AtomicReference<Optional<Claimed>> claimed = new AtomicReference<>(Optional.empty());
      TenantContext.run(
          TenantId.of(ref.tenantId()), () -> claimed.set(tx.execute(t -> queue.claim(ref, lease))));
      if (claimed.get() != null && claimed.get().isPresent()) {
        Claimed c = claimed.get().get();
        TenantContext.run(TenantId.of(ref.tenantId()), () -> process(c));
        return true;
      }
    }
    return false;
  }

  void process(Claimed c) {
    AtomicReference<List<CoreScoringInput>> page = new AtomicReference<>();
    try {
      ScoreJobRunner.Computed out;
      if (c.job().kind() == ScoreJobKind.KIEM_TRA_TAI_LAP) {
        // Đọc kết quả gốc cần giao dịch để có ngữ cảnh tenant (RLS).
        int offset = Integer.parseInt(c.cursor() == null ? "0" : c.cursor());
        out = tx.execute(t -> runner.recheck(c.job(), offset));
      } else {
        ScoringInputs.Page p =
            inputs.page(
                c.chunk().tenantId(), c.job().periodId(), c.cursor(), ScoringInputs.PAGE_SIZE);
        page.set(p.items());
        if (p.nextCursor() != null) {
          tx.executeWithoutResult(t -> queue.enqueue(c.job().ref(), c.seq() + 1, p.nextCursor()));
        }
        out = runner.scorePage(c.job(), p.items());
      }
      tx.executeWithoutResult(t -> complete(c, out));
    } catch (RuntimeException e) {
      LOG.warn(
          "Lô {} của tác vụ tính {} lỗi lần {}: {}",
          c.seq(),
          c.job().ref().id(),
          c.attempts(),
          ScoreJobRunner.summary(e));
      tx.executeWithoutResult(t -> retryOrFail(c, e, page.get()));
    }
  }

  private void complete(Claimed c, ScoreJobRunner.Computed out) {
    Ref job = c.job().ref();
    queue.lockJob(job);
    queue.complete(c.chunk(), out.scored());
    if (c.job().kind() != ScoreJobKind.KIEM_TRA_TAI_LAP) {
      jobs.addTotal(
          job.tenantId(), job.periodBucket(), job.id(), out.scored() + out.errors().size());
    }
    for (ScoreBatch batch : out.batches()) {
      recorder.record(batch);
    }
    queue.errors(job, c.seq(), c.attempts(), out.errors());
    jobs.progress(
        job.tenantId(), job.periodBucket(), job.id(), 0, out.errors().size(), c.job().actorId());
  }

  private void retryOrFail(Claimed c, RuntimeException e, List<CoreScoringInput> page) {
    Ref job = c.job().ref();
    queue.lockJob(job);
    String detail = ScoreJobRunner.summary(e);
    if (c.attempts() < MAX_ATTEMPTS) {
      queue.retry(c.chunk(), backoff.multipliedBy(1L << (c.attempts() - 1)), detail);
      return;
    }
    queue.fail(c.chunk(), detail);
    List<ScoreJobError> errors = new ArrayList<>();
    JobScope scope = JobScope.parse(c.job().scope());
    if (c.job().kind() == ScoreJobKind.KIEM_TRA_TAI_LAP) {
      int offset = Integer.parseInt(c.cursor() == null ? "0" : c.cursor());
      int end = Math.min(offset + ScoreJobRunner.RECHECK_CHUNK, scope.results().size());
      for (int i = offset; i < end; i++) {
        errors.add(
            new ScoreJobError(
                null,
                null,
                null,
                ScoreJobError.WRITE_FAILED,
                "Kết quả gốc " + scope.results().get(i) + ": " + detail));
      }
    } else if (page != null) {
      for (CoreScoringInput in : page) {
        if (scope.includes(in)) {
          errors.add(
              new ScoreJobError(
                  in.profileId(), in.personId(), in.flow(), ScoreJobError.WRITE_FAILED, detail));
        }
      }
      jobs.addTotal(job.tenantId(), job.periodBucket(), job.id(), errors.size());
    } else {
      // Không đọc được trang: không biết các trang sau nên tác vụ không thể xong trọn vẹn.
      errors.add(ScoreJobError.page(ScoreJobError.READ_FAILED, detail));
      jobs.markFailed(job.tenantId(), job.periodBucket(), job.id(), c.job().actorId());
    }
    queue.errors(job, c.seq(), c.attempts(), errors);
    int failed =
        c.job().kind() == ScoreJobKind.KIEM_TRA_TAI_LAP || page != null ? errors.size() : 0;
    jobs.progress(job.tenantId(), job.periodBucket(), job.id(), 0, failed, c.job().actorId());
  }
}
