package vn.viettel.okrkpi.okr.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import vn.viettel.okrkpi.okr.application.OkrComposeService.ObjectiveDraft;
import vn.viettel.okrkpi.okr.application.port.OkrAiSuggestions;
import vn.viettel.okrkpi.okr.application.port.OkrAiSuggestions.Suggestion;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.KeyResultData;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểm gợi ý AI mà người soạn chèn vào mục tiêu, KR trước khi lưu (CORE-01-13, BR-02-11, BR-17-01):
 * gợi ý phải tồn tại, thuộc cùng tenant và chính người soạn, chưa bị bỏ; đọc qua {@link
 * OkrAiSuggestions} ngoài giao dịch CSDL. Nội dung {@code HUMAN} không gọi SVC-14 nên lỗi hay quá
 * tải ở SVC-14 không chặn lưu nội dung tự soạn (TL-02a L840).
 */
public class OkrAiProvenance {

  static final String MODULE = "OKR";
  static final String BO_QUA = "BO_QUA";

  private final Supplier<OkrAiSuggestions> suggestions;

  public OkrAiProvenance(Supplier<OkrAiSuggestions> suggestions) {
    this.suggestions = Objects.requireNonNull(suggestions, "suggestions");
  }

  /** Gợi ý của toàn bộ mục tiêu (gợi ý của mục tiêu và của các KR có nguồn AI). */
  public Map<UUID, Suggestion> verify(
      TenantId tenant, UUID actor, String authorization, ObjectiveDraft draft) {
    Map<String, UUID> refs = new LinkedHashMap<>();
    if (draft.aiSuggestionId() != null) {
      refs.put("aiSuggestionId", draft.aiSuggestionId());
    }
    List<KeyResultData> krs = draft.keyResults() == null ? List.of() : draft.keyResults();
    for (int i = 0; i < krs.size(); i++) {
      KeyResultData k = krs.get(i);
      if (aiSourced(k)) {
        refs.put("keyResults[" + i + "].aiSuggestionId", k.aiSuggestionId());
      }
    }
    return verify(tenant, actor, authorization, refs);
  }

  /** Gợi ý của một KR thêm lẻ. */
  public Map<UUID, Suggestion> verify(
      TenantId tenant, UUID actor, String authorization, KeyResultData keyResult) {
    Map<String, UUID> refs = new LinkedHashMap<>();
    if (aiSourced(keyResult)) {
      refs.put("aiSuggestionId", keyResult.aiSuggestionId());
    }
    return verify(tenant, actor, authorization, refs);
  }

  static boolean aiSourced(KeyResultData k) {
    return k != null
        && k.aiSuggestionId() != null
        && k.contentSource() != null
        && !"HUMAN".equals(k.contentSource());
  }

  private Map<UUID, Suggestion> verify(
      TenantId tenant, UUID actor, String authorization, Map<String, UUID> refs) {
    Map<UUID, Suggestion> found = new LinkedHashMap<>();
    if (refs.isEmpty()) {
      return found;
    }
    OkrAiSuggestions port = suggestions.get();
    if (port == null) {
      throw unavailable(null);
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    for (Map.Entry<String, UUID> ref : refs.entrySet()) {
      Suggestion s = found.get(ref.getValue());
      if (s == null) {
        try {
          s = port.find(tenant, actor, ref.getValue(), authorization).orElse(null);
        } catch (OkrAiSuggestions.Unavailable e) {
          throw unavailable(e);
        }
      }
      if (s == null) {
        errors.add(
            new ProblemFieldError(
                ref.getKey(), "Gợi ý của trợ lý không tồn tại hoặc không thuộc đồng chí", null));
      } else if (BO_QUA.equals(s.decision())) {
        errors.add(new ProblemFieldError(ref.getKey(), "Gợi ý của trợ lý đã bị bỏ qua", null));
      } else {
        found.put(ref.getValue(), s);
      }
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          MODULE + "-422-VALIDATION", "Nội dung chèn từ trợ lý không hợp lệ", errors);
    }
    return found;
  }

  private static BusinessException unavailable(Throwable cause) {
    // Nguyên nhân đã ghi nhật ký ở adapter; Problem trả người dùng không mang chi tiết kỹ thuật.
    return new BusinessException(
        MODULE + "-502-SOURCE-UNAVAILABLE",
        "Chưa kiểm được gợi ý của trợ lý. Nội dung tự soạn vẫn lưu được; đồng chí thử lại sau.");
  }
}
