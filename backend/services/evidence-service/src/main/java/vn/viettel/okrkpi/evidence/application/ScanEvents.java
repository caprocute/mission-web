package vn.viettel.okrkpi.evidence.application;

import vn.viettel.okrkpi.evidence.domain.ScanRecord;
import vn.viettel.okrkpi.evidence.domain.StoredFile;

/** Sự kiện của việc quét, ghi outbox trong giao dịch của bên gọi. */
public interface ScanEvents {

  /** {@code evidence.object.scanned.v1} cho MOD-03 (BR-07-04). */
  void scanned(StoredFile file, ScanRecord result);

  /** EVT-21 báo người nộp tệp bị cách ly (MSG-07-02). */
  void quarantined(StoredFile file, ScanRecord result);
}
