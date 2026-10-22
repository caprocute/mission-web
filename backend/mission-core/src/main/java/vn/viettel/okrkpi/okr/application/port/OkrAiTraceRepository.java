package vn.viettel.okrkpi.okr.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Dấu vết nội dung chèn từ gợi ý AI ({@code okr.ai_content_trace}, CORE-01-13, BR-17-08). */
public interface OkrAiTraceRepository {

  /** Một dòng dấu vết; {@code keyResultId} rỗng khi thuộc mục tiêu. */
  record AiTrace(
      UUID objectiveId,
      UUID keyResultId,
      UUID suggestionId,
      String contentSource,
      List<String> keptFields,
      List<String> editedFields,
      Instant at,
      UUID actor,
      int versionNo) {}

  /** Ghi dấu vết cùng giao dịch lưu; phiên bản mục tiêu lấy từ dòng mục tiêu. */
  void record(TenantId tenant, int bucket, List<AiTrace> traces);

  /** Đặt {@code ai_assisted} của mục tiêu theo gợi ý của chính nó hoặc của bất kỳ KR nào. */
  void refreshObjectiveAiAssisted(TenantId tenant, int bucket, UUID objectiveId);

  /** Dấu vết của mục tiêu, cũ trước. */
  List<AiTrace> traces(TenantId tenant, UUID objectiveId);
}
