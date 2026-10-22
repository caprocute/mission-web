package vn.viettel.okrkpi.esign.infrastructure.sign;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import vn.viettel.okrkpi.esign.application.sign.EsignService;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Worker thăm dò nhà cung cấp cho yêu cầu đang chờ xác nhận (DV-10-01): mỗi chu kỳ lấy một lô, xử
 * lý từng yêu cầu trong ngữ cảnh tenant; nhiều bản sao chạy song song an toàn nhờ khoá dòng {@code
 * SKIP LOCKED}.
 */
public class SignPollingWorker {

  private static final Logger LOG = LoggerFactory.getLogger(SignPollingWorker.class);

  private final PendingRequestScanner scanner;
  private final EsignService service;
  private final int batchSize;

  public SignPollingWorker(PendingRequestScanner scanner, EsignService service, int batchSize) {
    this.scanner = scanner;
    this.service = service;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${okrkpi.esign.worker.poll-interval:2s}")
  public void poll() {
    for (PendingRequestScanner.Pending pending : scanner.scan(batchSize)) {
      try {
        TenantContext.run(TenantId.of(pending.tenantId()), () -> service.pollOnce(pending.id()));
      } catch (RuntimeException e) {
        LOG.warn("Thăm dò yêu cầu ký {} lỗi: {}", pending.id(), e.toString());
      }
    }
  }
}
