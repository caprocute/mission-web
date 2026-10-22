package vn.viettel.okrkpi.docgen.domain.render;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Kho tác vụ sinh {@code docgen.render_job}, đọc trong ngữ cảnh tenant (RLS). */
public interface RenderJobRepository {

  Optional<RenderJob> findById(UUID id);

  /** Khoá dòng để xử lý; rỗng khi không có hoặc worker khác đang giữ ({@code SKIP LOCKED}). */
  Optional<RenderJob> lockForProcessing(UUID id);

  List<RenderJob> findByRequest(UUID requestId);

  /**
   * Thêm tác vụ; trả {@code false} khi {@code request_event_id} đã có (lệnh sinh gửi lại), để lệnh
   * trùng không sinh thêm tệp.
   */
  boolean insertIfAbsent(RenderJob job);

  /** Ghi trạng thái theo khoá lạc quan {@code row_version}; trả phiên bản mới. */
  long update(RenderJob job);

  void insertOutput(RenderOutput output);

  List<RenderOutput> outputsOf(UUID renderJobId);
}
