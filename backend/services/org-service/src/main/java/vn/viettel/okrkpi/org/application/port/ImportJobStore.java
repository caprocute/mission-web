package vn.viettel.okrkpi.org.application.port;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.orgimport.ImportError;

/** DV-04-05 · {@code org.import_job}: tác vụ nền nhập cây tổ chức. */
public interface ImportJobStore {

  record NewJob(UUID id, String fileName, byte[] content, byte[] sha256, LocalDate effectiveFrom) {
  }

  record JobRow(UUID id, UUID createdBy, String status, int progress, String message, LocalDate effectiveFrom,
      int totalRows, int createdUnits, List<ImportError> errors, UUID changeOrderId, OffsetDateTime updatedAt) {
  }

  void insert(NewJob job, Actor actor);

  Optional<JobRow> find(UUID id);

  /** Nội dung tệp còn chờ xử lý; rỗng khi tác vụ đã kết thúc. */
  Optional<byte[]> content(UUID id);

  void progress(UUID id, String status, int progress, String message, int totalRows, Actor actor);

  /** Kết thúc tác vụ và xoá nội dung tệp. */
  void finish(UUID id, String status, int totalRows, int createdUnits, List<ImportError> errors, UUID changeOrderId,
      String message, Actor actor);
}
