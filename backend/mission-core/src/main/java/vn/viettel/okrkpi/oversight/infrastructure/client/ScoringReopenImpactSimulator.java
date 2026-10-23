package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.oversight.application.port.ReopenImpactSimulator;
import vn.viettel.okrkpi.oversight.application.port.ReopenRequestRepository.Adjustment;
import vn.viettel.okrkpi.shared.infrastructure.scoring.ScoringSimulationClient;
import vn.viettel.okrkpi.shared.infrastructure.scoring.ScoringSimulationClient.Result;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link ReopenImpactSimulator} gọi {@code simulateAppraisalScore} của SVC-07 cho từng hồ sơ trong
 * phạm vi (DV-07-08 chưa có thao tác lô). Mỗi hồ sơ cho hai dòng: tổng điểm và mức xếp loại trước,
 * sau. Phạm vi đơn vị hoặc toàn kỳ không kèm danh sách hồ sơ thì không có dòng nào.
 *
 * <p>Điều chỉnh (suy luận, wiki trang 14): {@code targetKey} là mã cấu phần (A, B, C, nhóm điểm),
 * dạng {@code <cấu phần>:<mã nhiệm vụ>} khi gắn một nhiệm vụ; chỉ điều chỉnh có giá trị mới là số
 * được gửi, điều chỉnh mức xếp loại ({@code XEP_LOAI.*}) không tính thử được nên bỏ qua.
 */
public class ScoringReopenImpactSimulator implements ReopenImpactSimulator {

  static final String METRIC_SCORE = "TONG_DIEM";
  static final String METRIC_RATING = "XEP_LOAI";

  private final ScoringSimulationClient client;

  public ScoringReopenImpactSimulator(ScoringSimulationClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public Impact simulate(TenantId tenant, Request request) {
    List<ScoringSimulationClient.Adjustment> adjustments = adjustments(request.adjustments());
    List<Row> rows = new ArrayList<>();
    String policy = null;
    int changed = 0;
    if (adjustments.isEmpty()) {
      return new Impact(policy, rows, 0);
    }
    for (UUID profile : request.profileIds()) {
      Result r = client.simulate(tenant, profile, adjustments, request.authorization());
      if (r == null) {
        continue;
      }
      if (policy == null) {
        policy = r.policyVersion();
      }
      rows.add(
          new Row(
              profile,
              METRIC_SCORE,
              plain(r.before().totalScore()),
              plain(r.after().totalScore())));
      rows.add(new Row(profile, METRIC_RATING, r.before().ratingCode(), r.after().ratingCode()));
      if (!Objects.equals(r.before().ratingCode(), r.after().ratingCode())
          || compare(r.before().totalScore(), r.after().totalScore()) != 0) {
        changed++;
      }
    }
    // Mỗi hồ sơ đổi kết quả cần sinh lại Mẫu 2, Mẫu 3 (QT-13 bước 11, suy luận).
    return new Impact(policy, rows, changed * 2);
  }

  static List<ScoringSimulationClient.Adjustment> adjustments(List<Adjustment> in) {
    List<ScoringSimulationClient.Adjustment> out = new ArrayList<>();
    for (Adjustment a : in) {
      BigDecimal newValue = number(a.newValue());
      if (a.targetKey() == null || a.targetKey().isBlank() || newValue == null) {
        continue;
      }
      String key = a.targetKey().strip();
      if (key.startsWith(METRIC_RATING)) {
        continue;
      }
      String component = key;
      UUID task = null;
      int colon = key.indexOf(':');
      if (colon > 0) {
        component = key.substring(0, colon);
        try {
          task = UUID.fromString(key.substring(colon + 1));
        } catch (IllegalArgumentException e) {
          task = null;
        }
      }
      out.add(
          new ScoringSimulationClient.Adjustment(
              component, task, number(a.oldValue()), newValue, a.reason()));
    }
    return out;
  }

  private static BigDecimal number(String v) {
    if (v == null || v.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(v.strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static int compare(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) {
      return a == b ? 0 : 1;
    }
    return a.compareTo(b);
  }

  private static String plain(BigDecimal v) {
    return v == null ? null : v.stripTrailingZeros().toPlainString();
  }
}
