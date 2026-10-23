package vn.viettel.okrkpi.scoring.infrastructure.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.scoring.application.ScoreJobWorker;

/**
 * Vòng nhận việc của worker tính điểm (DV-07-06): mỗi nhịp bắt đầu các tác vụ chờ rồi chạy {@code
 * parallelism} luồng ảo, mỗi luồng nhận lô đến khi hết lô đến hạn. KEDA co giãn số pod theo chỉ số
 * {@code scoring_chunk_pending} và độ trễ các topic đầu vào; bật bằng {@code
 * mission.scoring.worker.enabled}.
 */
public class ScoreJobPoller implements DisposableBean {

  private static final Logger LOG = LoggerFactory.getLogger(ScoreJobPoller.class);

  private final ScoreJobWorker worker;
  private final int parallelism;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public ScoreJobPoller(ScoreJobWorker worker, int parallelism) {
    this.worker = worker;
    this.parallelism = Math.max(1, parallelism);
  }

  @Scheduled(
      initialDelayString = "${mission.scoring.worker.poll-interval:PT2S}",
      fixedDelayString = "${mission.scoring.worker.poll-interval:PT2S}")
  public void poll() {
    worker.startWaiting(parallelism * 4);
    List<Future<?>> running = new ArrayList<>(parallelism);
    for (int i = 0; i < parallelism; i++) {
      running.add(
          executor.submit(
              () -> {
                while (worker.runOne(parallelism * 2)) {
                  // Còn lô đến hạn: nhận tiếp.
                }
              }));
    }
    for (Future<?> f : running) {
      try {
        f.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (ExecutionException e) {
        LOG.error("Luồng worker tính điểm dừng do lỗi", e.getCause());
      }
    }
  }

  @Override
  public void destroy() {
    executor.shutdownNow();
  }
}
