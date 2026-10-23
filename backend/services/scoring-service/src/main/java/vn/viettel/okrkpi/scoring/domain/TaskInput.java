package vn.viettel.okrkpi.scoring.domain;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Một nhiệm vụ được tính của hồ sơ (TL-03 {@code score_input_v}, kiểu {@code NhiemVu} của Policy
 * Pack): số lượng giao, sản phẩm, các khoảng chậm, điểm danh mục, cờ không hoàn thành do chủ quan.
 */
public record TaskInput(
    UUID taskId,
    String code,
    String group,
    int plannedCount,
    BigDecimal catalogBaseScore,
    boolean keyMissedSubjective,
    List<ProductInput> products,
    List<DelaySegment> delays) {

  public TaskInput {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(group, "group");
    if (plannedCount < 0) {
      throw new IllegalArgumentException("Số lượng giao không âm");
    }
    if (catalogBaseScore != null
        && (catalogBaseScore.signum() < 0
            || catalogBaseScore.compareTo(BigDecimal.valueOf(100)) > 0)) {
      throw new IllegalArgumentException("Điểm danh mục phải trong 0..100");
    }
    products = List.copyOf(products);
    delays = List.copyOf(delays);
  }

  /**
   * Phần tử {@code nhiem_vu} theo kiểu {@code NhiemVu} của gói: chỉ sản phẩm được đếm vào số lượng
   * duyệt và trung bình chất lượng (BR-07-05, BR-07-07, BR-09-02); ngày chậm chỉ cộng khoảng chủ
   * quan. Không có sản phẩm được đếm thì mức chất lượng là 0, trừ nhiệm vụ không giao số lượng (A =
   * 1 theo gói) thì là 1 (suy luận, wiki 14).
   */
  public Map<String, Object> toNhiemVu() {
    List<ProductInput> counted = products.stream().filter(ProductInput::counted).toList();
    double quality =
        counted.isEmpty()
            ? (plannedCount == 0 ? 1.0 : 0.0)
            : counted.stream().mapToDouble(p -> p.qualityLevel().doubleValue()).sum()
                / counted.size();
    long revisions = counted.stream().mapToLong(ProductInput::revisionCount).sum();
    long workdays = 0;
    long calendarDays = 0;
    for (DelaySegment d : delays) {
      if (d.cause() == DelaySegment.Cause.CHU_QUAN) {
        workdays += d.workdays();
        calendarDays += d.calendarDays();
      }
    }
    Map<String, Object> nv = new LinkedHashMap<>();
    nv.put("task_id", taskId.toString());
    nv.put("ma", code);
    nv.put("nhom", group);
    nv.put("ke_hoach", (long) plannedCount);
    nv.put("so_luong_duyet", (long) counted.size());
    nv.put("muc_chat_luong", quality);
    nv.put("so_lan_chinh_sua", revisions);
    nv.put("ngay_lam_viec_cham_chu_quan", workdays);
    nv.put("ngay_lich_cham_chu_quan", calendarDays);
    nv.put("diem_chuan", catalogBaseScore == null ? 0.0 : catalogBaseScore.doubleValue());
    nv.put("khong_hoan_thanh_chu_quan", keyMissedSubjective);
    return nv;
  }
}
