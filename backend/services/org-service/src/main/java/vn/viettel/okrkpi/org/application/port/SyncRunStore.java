package vn.viettel.okrkpi.org.application.port;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.Actor;

/** DV-04-07 · Bảng {@code org.sync_run}. */
public interface SyncRunStore {

  record Run(UUID id, String connector, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt,
      int records, int changes, int errors, int deadLetters, String reason) {
  }

  /** Tạo lần chạy {@code DANG_CHAY}; ném {@code OrgConflictException} nếu nguồn đang có lần chạy. */
  void start(UUID id, String connector, Actor actor);

  Optional<Run> find(UUID id);

  /** Cộng dồn số liệu một lô. */
  void addBatch(UUID id, int records, int changes, int errors, Actor actor);

  void finish(UUID id, String status, int deadLetters, String reason, Actor actor);

  /** Các lần chạy cùng một lệnh "Đồng bộ ngay" với {@code id}: cùng người gọi, cùng thời điểm bắt đầu (một giao dịch). */
  List<Run> startedTogether(UUID id);

  List<Run> page(String connector, OffsetDateTime beforeStarted, UUID beforeId, int limit);
}
