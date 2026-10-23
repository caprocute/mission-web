package vn.viettel.okrkpi.appraisal.infrastructure.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hành động xử lý của R3 (TL-03: "Chặn bước ký số cho hồ sơ lệch"): hồ sơ bị chặn khi lần đối soát
 * R3 kết thúc gần nhất của kỳ là {@code LOI} và liệt kê hồ sơ đó, hoặc danh sách bị cắt ({@code
 * truncated}) thì chặn cả kỳ. Lần chạy sau khớp ({@code KHOP}, {@code DA_SUA}) gỡ chặn. Cổng tài
 * liệu ký nội bộ của SVC-10 gọi trong ngữ cảnh tenant của request.
 */
public class ScoreReconcileGate {

  private final JdbcTemplate appraisal;
  private final TransactionTemplate tx;
  private final ObjectMapper json;

  public ScoreReconcileGate(
      JdbcTemplate appraisal, PlatformTransactionManager tm, ObjectMapper json) {
    this.appraisal = Objects.requireNonNull(appraisal, "appraisal");
    this.tx = new TransactionTemplate(Objects.requireNonNull(tm, "tm"));
    this.tx.setReadOnly(true);
    this.json = Objects.requireNonNull(json, "json");
  }

  /** {@code true} khi hồ sơ đang bị chặn ký số do lệch điểm và ảnh chụp. */
  public boolean blocked(UUID profileId) {
    return anyBlocked(java.util.List.of(profileId));
  }

  /** {@code true} khi ít nhất một hồ sơ trong danh sách đang bị chặn (lô quyết định ký cả lô). */
  public boolean anyBlocked(Collection<UUID> profileIds) {
    if (profileIds.isEmpty()) {
      return false;
    }
    return Boolean.TRUE.equals(
        tx.execute(
            st -> {
              Map<UUID, Optional<JsonNode>> latest = new HashMap<>();
              for (UUID profile : profileIds) {
                UUID period = period(profile);
                if (period == null) {
                  continue;
                }
                Optional<JsonNode> run = latest.computeIfAbsent(period, this::latestLoi);
                if (run.isPresent() && lists(run.get(), profile)) {
                  return true;
                }
              }
              return false;
            }));
  }

  private UUID period(UUID profile) {
    return appraisal
        .query(
            "SELECT period_id FROM appraisal.appraisal_profile WHERE id = ?",
            (rs, i) -> rs.getObject(1, UUID.class),
            profile)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** Chi tiết lần R3 kết thúc gần nhất của kỳ nếu lần đó là {@code LOI}. */
  private Optional<JsonNode> latestLoi(UUID period) {
    return appraisal
        .query(
            "SELECT status, detail::text FROM appraisal.reconcile_run WHERE job = ?"
                + " AND status <> 'DANG_CHAY' AND detail ->> 'periodId' = ?"
                + " ORDER BY started_at DESC LIMIT 1",
            (rs, i) -> "LOI".equals(rs.getString(1)) ? parse(rs.getString(2)) : null,
            ScoreSnapshotReconciler.JOB,
            period.toString())
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  private static boolean lists(JsonNode detail, UUID profile) {
    if (detail.path("truncated").asBoolean(false)) {
      return true;
    }
    String id = profile.toString();
    for (JsonNode p : detail.path("profiles")) {
      if (id.equals(p.asText())) {
        return true;
      }
    }
    return false;
  }

  private JsonNode parse(String detail) {
    try {
      return json.readTree(detail);
    } catch (JsonProcessingException e) {
      // Chi tiết hỏng: chặn để người vận hành xem lại (an toàn hơn cho phép ký).
      return json.createObjectNode().put("truncated", true);
    }
  }
}
