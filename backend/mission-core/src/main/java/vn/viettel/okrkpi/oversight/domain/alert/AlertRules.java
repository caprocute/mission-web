package vn.viettel.okrkpi.oversight.domain.alert;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Quy tắc tiếp nhận cảnh báo của QT-14 (CORE-07-10).
 *
 * <ul>
 *   <li>Mã dấu hiệu, mức và loại đối tượng theo CHECK của {@code oversight.anomaly_alert} (TL-03).
 *   <li>Chuỗi bằng chứng chỉ giữ mã dạng {@code <loại>:<mã>} (BR-14-06): mục khác dạng bị bỏ, nên
 *       văn bản tự do (có thể là C2, C3) không vào CSDL.
 *   <li>Kỳ lấy từ mục {@code ky:<uuid>} của chuỗi (DV-15-08).
 *   <li>Phá chuỗi sổ cái (mục {@code ledger_chain:}) luôn mức Cao (BR-14-07).
 *   <li>{@code severity = HET_DAU_HIEU} là tín hiệu hết dấu hiệu (đề xuất ở DV-15-09, suy luận).
 * </ul>
 */
public final class AlertRules {

  public static final Set<String> SIGNALS =
      Set.of(
          "TU_CHAM_CAO",
          "CAO_BANG",
          "MUC_TIEU_DE",
          "MINH_CHUNG_TRUNG",
          "CAP_NHAT_DON",
          "SUA_SAU_KHOA",
          "CHE_NHO",
          "MIEN_TRU_LAP");
  public static final Set<String> SEVERITIES = Set.of("CAO", "VUA", "THAP");
  public static final Set<String> SUBJECT_TYPES = Set.of("CA_NHAN", "DON_VI", "NGUOI_DUYET");

  public static final String RESOLVED = "HET_DAU_HIEU";
  public static final String DUPLICATE_EVIDENCE = "MINH_CHUNG_TRUNG";
  public static final String UNIT = "DON_VI";
  public static final String HIGH = "CAO";

  public static final String MOI = "MOI";
  public static final String TU_DONG_DONG = "TU_DONG_DONG";
  public static final String DONG = "DONG";

  /** Tiền tố mã trong chuỗi bằng chứng (suy luận, wiki trang 14). */
  public static final String PERIOD_PREFIX = "ky:";

  public static final String EVIDENCE_PREFIX = "evidence:";
  public static final String LEDGER_PREFIX = "ledger_chain:";
  public static final String CHILD_PREFIX = "anomaly_alert:";

  /** Số mục tối đa giữ trong chuỗi bằng chứng của một cảnh báo. */
  public static final int MAX_EVIDENCE = 500;

  private static final Pattern CODE =
      Pattern.compile("^[a-z][a-z_]{0,39}:[A-Za-z0-9._=\\-]{1,100}$");

  private AlertRules() {}

  /** Lỗi tín hiệu không đúng hợp đồng: không thử lại, chuyển thư chết. */
  public static final class InvalidSignal extends RuntimeException {
    public InvalidSignal(String message) {
      super(message);
    }
  }

  public static void validate(AlertSignal s) {
    if (!SIGNALS.contains(s.signalCode())) {
      throw new InvalidSignal("Dấu hiệu không có trong danh mục: " + s.signalCode());
    }
    if (!SEVERITIES.contains(s.severity()) && !RESOLVED.equals(s.severity())) {
      throw new InvalidSignal("Mức cảnh báo không hợp lệ: " + s.severity());
    }
    if (!SUBJECT_TYPES.contains(s.subjectType())) {
      throw new InvalidSignal("Loại đối tượng không hợp lệ: " + s.subjectType());
    }
    if (s.modelVersion() == null || s.modelVersion().isBlank() || s.modelVersion().length() > 32) {
      throw new InvalidSignal("Thiếu phiên bản quy tắc phát hiện");
    }
  }

  public static boolean resolution(AlertSignal s) {
    return RESOLVED.equals(s.severity());
  }

  /** Chỉ giữ mục là mã, bỏ trùng, giữ thứ tự, tối đa {@link #MAX_EVIDENCE} mục. */
  public static List<String> sanitize(List<String> raw) {
    Set<String> out = new LinkedHashSet<>();
    for (String x : raw) {
      if (x != null && CODE.matcher(x.trim()).matches() && out.size() < MAX_EVIDENCE) {
        out.add(x.trim());
      }
    }
    return List.copyOf(out);
  }

  public static Optional<UUID> period(List<String> evidence) {
    return evidence.stream()
        .filter(x -> x.startsWith(PERIOD_PREFIX))
        .map(x -> uuid(x.substring(PERIOD_PREFIX.length())))
        .flatMap(Optional::stream)
        .findFirst();
  }

  /** Mức lưu: phá chuỗi sổ cái luôn Cao (BR-14-07). */
  public static String severity(AlertSignal s, List<String> evidence) {
    return evidence.stream().anyMatch(x -> x.startsWith(LEDGER_PREFIX)) ? HIGH : s.severity();
  }

  /** Dấu hiệu 4: tạm chưa tính điểm các minh chứng liên quan (BR-14-04, BR-09-02). */
  public static boolean blocksEvidence(String signalCode) {
    return DUPLICATE_EVIDENCE.equals(signalCode);
  }

  public static List<UUID> evidenceIds(List<String> evidence) {
    List<UUID> ids = new ArrayList<>();
    for (String x : evidence) {
      if (x.startsWith(EVIDENCE_PREFIX)) {
        uuid(x.substring(EVIDENCE_PREFIX.length())).ifPresent(ids::add);
      }
    }
    return ids;
  }

  public static int rank(String severity) {
    return switch (severity) {
      case "CAO" -> 3;
      case "VUA" -> 2;
      default -> 1;
    };
  }

  /**
   * {@code period_bucket} như {@code Period.bucketOf}: kỳ dài hơn bốn tháng lấy quý 0, còn lại lấy
   * quý của ngày bắt đầu.
   */
  public static int bucket(LocalDate start, LocalDate end) {
    boolean yearly = ChronoUnit.DAYS.between(start, end) > 120;
    return start.getYear() * 10 + (yearly ? 0 : (start.getMonthValue() - 1) / 3 + 1);
  }

  /** Khoá chống trùng (tenant, dấu hiệu, đối tượng, kỳ), cũng là khoá tư vấn của PostgreSQL. */
  public static String dedupKey(
      UUID tenant, String signal, String subjectType, UUID subject, UUID period) {
    return tenant + "|" + signal + "|" + subjectType + "|" + subject + "|" + period;
  }

  /** Mã sự kiện nguồn tất định của cảnh báo gộp cấp đơn vị. */
  public static UUID groupSourceId(UUID tenant, String signal, UUID unit, UUID period) {
    return UUID.nameUUIDFromBytes(
        ("GOP|" + dedupKey(tenant, signal, UNIT, unit, period)).getBytes(StandardCharsets.UTF_8));
  }

  private static Optional<UUID> uuid(String s) {
    try {
      return Optional.of(UUID.fromString(s));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
