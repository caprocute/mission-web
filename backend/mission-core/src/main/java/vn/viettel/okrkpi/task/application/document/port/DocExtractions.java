package vn.viettel.okrkpi.task.application.document.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec.Assignee;

/**
 * Kết quả AI trích nhiệm vụ của SVC-14 ai-gateway ({@code getDocExtraction}) và quyết định với gợi
 * ý ({@code decideAiSuggestion}), gọi thay mặt người dùng. MOD-02 không gửi yêu cầu trích: SVC-14
 * tự trích khi nhận EVT-24.
 */
public interface DocExtractions {

  /** Các ô của thẻ nhiệm vụ ({@code TaskSuggestionFields}). */
  record Fields(
      String title,
      UUID leadUnitId,
      String leadUnitName,
      List<Assignee> coUnits,
      List<String> coUnitNames,
      LocalDate dueDate,
      String productName,
      UUID catalogItemId,
      String catalogItemCode,
      UUID keyResultId,
      String keyResultCode) {

    public Fields {
      coUnits = List.copyOf(coUnits == null ? List.of() : coUnits);
      coUnitNames = List.copyOf(coUnitNames == null ? List.of() : coUnitNames);
    }
  }

  /** Một nhiệm vụ AI trích. */
  record Suggestion(
      UUID id,
      int paragraphNo,
      BigDecimal confidence,
      String decision,
      Fields fields,
      Fields aiFields,
      List<String> editedFields,
      String reason) {

    public Suggestion {
      editedFields = List.copyOf(editedFields == null ? List.of() : editedFields);
    }
  }

  /** {@code status} là {@code DANG_TRICH}, {@code XONG} hoặc {@code KHONG_TRICH_DUOC}. */
  record Extraction(String status, List<Suggestion> suggestions) {

    public Extraction {
      suggestions = List.copyOf(suggestions == null ? List.of() : suggestions);
    }
  }

  /** Thông báo khi SVC-14 lỗi hoặc không gọi được. */
  final class Unavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Rỗng khi SVC-14 chưa có kết quả cho văn bản (404).
   *
   * @throws Unavailable khi SVC-14 lỗi hoặc không gọi được
   */
  Optional<Extraction> find(TenantId tenant, UUID sourceDocId, String authorization);

  /**
   * Ghi quyết định {@code CHAP_NHAN} hoặc {@code SUA} với gợi ý; 409 (đã có quyết định) coi là
   * xong.
   *
   * @throws Unavailable khi SVC-14 lỗi hoặc không gọi được
   */
  void accept(
      TenantId tenant,
      UUID suggestionId,
      boolean edited,
      List<String> editedFields,
      String targetRef,
      String authorization,
      String idempotencyKey);
}
