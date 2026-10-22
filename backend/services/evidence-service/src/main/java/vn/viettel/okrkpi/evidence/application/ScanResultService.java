package vn.viettel.okrkpi.evidence.application;

import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.evidence.application.ScanQueue.Status;

/** {@code getFileScanResult}: trạng thái quét hiện tại của một phiên bản (SCR-0701). */
public class ScanResultService {

  private final ScanQueue queue;
  private final FileAccessGuard guard;
  private final TransactionOperations tx;

  public ScanResultService(ScanQueue queue, FileAccessGuard guard, TransactionOperations tx) {
    this.queue = queue;
    this.guard = guard;
    this.tx = tx;
  }

  public Status status(UUID objectId, Integer versionNo) {
    Status s =
        tx.execute(t -> queue.status(objectId, versionNo))
            .orElseThrow(UploadService.FileNotFound::new);
    guard.checkScanResult(new FileAccessGuard.FileAccess(s.file()));
    return s;
  }
}
