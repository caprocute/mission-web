package vn.viettel.okrkpi.evidence.application;

import vn.viettel.okrkpi.evidence.domain.StoredFile;

/** Sự kiện kỹ thuật của SVC-08 qua outbox {@code evidence.outbox_event}, cùng giao dịch ghi. */
public interface FileEvents {

  /** {@code sys.evidence.object.stored.v1}: MOD-03 gắn đối tượng với phiên bản minh chứng. */
  void stored(StoredFile file);
}
