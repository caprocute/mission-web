package vn.viettel.okrkpi.oversight.domain.reopen;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tiến độ hậu kiểm của một hồ sơ trong phạm vi yêu cầu mở lại (CORE-07-09, QT-13 bước 10, 11): lần
 * tính lại {@code TINH_LAI_HAU_KIEM} mới nhất (EVT-10) và các biểu mẫu sinh lại (EVT-17), ký lại
 * (EVT-18) theo mã biểu mẫu. So theo thời điểm của sự kiện chứ không theo thứ tự nhận, nên sự kiện
 * đến sai thứ tự hoặc trùng vẫn cho cùng kết quả.
 */
public record ReopenProgress(
    UUID profileId,
    Instant recomputedAt,
    UUID scoreResultId,
    Map<UUID, Form> forms,
    Instant completedAt) {

  /**
   * Một biểu mẫu sinh lại; {@code generatedAt} hoặc {@code signedAt} rỗng khi chưa nhận sự kiện.
   */
  public record Form(String code, Instant generatedAt, Instant signedAt) {}

  public ReopenProgress {
    Objects.requireNonNull(profileId, "profileId");
    forms = forms == null ? Map.of() : Map.copyOf(forms);
  }

  public static ReopenProgress pending(UUID profileId) {
    return new ReopenProgress(profileId, null, null, Map.of(), null);
  }

  /** Giữ lần tính lại mới nhất; lần cũ hơn hoặc trùng không đổi gì. */
  public ReopenProgress recomputed(UUID scoreId, Instant at) {
    if (recomputedAt != null && !at.isAfter(recomputedAt)) {
      return this;
    }
    return new ReopenProgress(profileId, at, scoreId, forms, completedAt);
  }

  /** Biểu mẫu đã sinh; giữ thời điểm sinh muộn nhất của cùng bản biểu mẫu. */
  public ReopenProgress generated(UUID formId, String code, Instant at) {
    Form old = forms.get(formId);
    Instant g =
        old == null || old.generatedAt() == null || at.isAfter(old.generatedAt())
            ? at
            : old.generatedAt();
    return withForm(formId, new Form(code, g, old == null ? null : old.signedAt()));
  }

  /** Biểu mẫu đã ký; có thể đến trước sự kiện sinh. */
  public ReopenProgress signed(UUID formId, String code, Instant at) {
    Form old = forms.get(formId);
    Instant s =
        old == null || old.signedAt() == null || at.isAfter(old.signedAt()) ? at : old.signedAt();
    return withForm(formId, new Form(code, old == null ? null : old.generatedAt(), s));
  }

  /**
   * Đã tính lại, và với mỗi mã biểu mẫu bắt buộc có một bản sinh sau lần tính lại mới nhất và được
   * ký sau khi sinh.
   */
  public boolean done(Collection<String> requiredForms) {
    if (recomputedAt == null) {
      return false;
    }
    for (String code : requiredForms) {
      boolean ok =
          forms.values().stream()
              .anyMatch(
                  f ->
                      code.equals(f.code())
                          && f.generatedAt() != null
                          && !f.generatedAt().isBefore(recomputedAt)
                          && f.signedAt() != null
                          && !f.signedAt().isBefore(f.generatedAt()));
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  /** Bản sao với thời điểm hoàn tất mới ({@code null} khi chưa hoặc không còn hoàn tất). */
  public ReopenProgress withCompletedAt(Instant at) {
    return new ReopenProgress(profileId, recomputedAt, scoreResultId, forms, at);
  }

  private ReopenProgress withForm(UUID formId, Form f) {
    Map<UUID, Form> m = new LinkedHashMap<>(forms);
    m.put(formId, f);
    return new ReopenProgress(profileId, recomputedAt, scoreResultId, m, completedAt);
  }
}
