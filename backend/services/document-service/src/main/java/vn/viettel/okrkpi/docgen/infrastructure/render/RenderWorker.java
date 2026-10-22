package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.docgen.application.render.RenderService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Worker sinh (DV-11-01): mỗi chu kỳ lấy một lô tác vụ đang chờ (cùng tác vụ đang sinh đã quá hạn
 * thuê), sinh từng tác vụ trong ngữ cảnh tenant của nó; nhiều bản sao (KEDA co giãn theo hàng đợi)
 * chạy song song an toàn nhờ {@code SKIP LOCKED}.
 */
public class RenderWorker {

  private static final Logger LOG = LoggerFactory.getLogger(RenderWorker.class);

  private final PendingJobScanner scanner;
  private final RenderService service;
  private final int batchSize;
  private final Duration lease;

  public RenderWorker(
      PendingJobScanner scanner, RenderService service, int batchSize, Duration lease) {
    this.scanner = scanner;
    this.service = service;
    this.batchSize = batchSize;
    this.lease = lease;
  }

  @Scheduled(fixedDelayString = "${okrkpi.docgen.worker.poll-interval:1s}")
  public void poll() {
    for (PendingJobScanner.Pending pending : scanner.scan(batchSize, lease)) {
      try {
        TenantContext.run(TenantId.of(pending.tenantId()), () -> service.processOnce(pending.id()));
      } catch (RuntimeException e) {
        LOG.warn("Sinh tác vụ {} lỗi: {}", pending.id(), e.toString());
      }
    }
  }
}
