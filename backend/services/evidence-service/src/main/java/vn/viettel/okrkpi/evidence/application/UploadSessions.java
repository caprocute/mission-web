package vn.viettel.okrkpi.evidence.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.evidence.domain.UploadSession;

/** Kho {@code evidence.upload_session}; mọi lệnh chạy trong giao dịch có ngữ cảnh tenant (RLS). */
public interface UploadSessions {

  void insert(UploadSession session);

  Optional<UploadSession> find(UUID id);

  void updatePartsDone(UUID id, int partsDone, UUID actor);

  /** Chuyển {@code MO} sang trạng thái đóng; {@code false} khi phiên không còn {@code MO}. */
  boolean close(UUID id, UploadSession.Status to, UUID actor);

  /** Phiên {@code MO} đã quá hạn của mọi tenant (chỉ mã và tenant). */
  List<Expired> expired(int limit);

  /** Một phiên quá hạn. */
  record Expired(UUID id, UUID tenantId) {}
}
