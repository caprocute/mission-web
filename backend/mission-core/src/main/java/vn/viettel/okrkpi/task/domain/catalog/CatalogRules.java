package vn.viettel.okrkpi.task.domain.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quy tắc của danh mục công việc: kiểm dữ liệu mục (BR-04-14 và ràng buộc trường SCR-0401 theo
 * {@code CatalogItemInput}), sinh mã {@code TC-xx-nn}, gợi ý điểm chuẩn theo hệ số mức khó và chặn
 * vòng kế thừa.
 */
public final class CatalogRules {

  /** Mã công việc {@code TC-xx-nn} (TL-02a SCR-0401 trường 4), tối đa 20 ký tự. */
  static final Pattern CODE = Pattern.compile("TC-([A-Z0-9]{1,8})-(\\d{1,4})");

  static final int CODE_MAX = 20;
  static final BigDecimal MAX_SCORE = BigDecimal.valueOf(100);
  static final String DEFAULT_PREFIX = "CV";

  /** Giới hạn độ sâu khi lần theo danh mục cấp trên, đủ cho mọi cấp hành chính. */
  public static final int MAX_DEPTH = 16;

  static final Pattern LEVEL_TYPE = Pattern.compile("[a-z][a-z0-9_]{0,31}");
  static final Pattern AXIS = Pattern.compile("[A-Z0-9_.-]{1,16}");
  static final int MAX_LEVELS = 16;

  private static final Set<String> GROUPS = Set.of("THUONG_XUYEN", "THEN_CHOT");
  private static final Set<String> ITEM_STATUSES = Set.of("AP_DUNG", "NGUNG");

  private CatalogRules() {}

  /** Cắt khoảng trắng; chuỗi rỗng thành {@code null}; trạng thái trống thành {@code AP_DUNG}. */
  public static CatalogItemDraft normalize(CatalogItemDraft d) {
    return new CatalogItemDraft(
        trim(d.code()),
        d.positionId(),
        trim(d.name()),
        trim(d.productName()),
        trim(d.unit()),
        d.difficulty(),
        d.baseScore(),
        trim(d.qualityCriteria()),
        trim(d.evidenceRequired()),
        d.group(),
        d.standardDays(),
        d.status() == null ? "AP_DUNG" : d.status(),
        new ItemScoring(
            d.scoring().coefficientLevels(),
            trim(d.scoring().axisCode()),
            d.scoring().scoreAdjustMin(),
            d.scoring().scoreAdjustMax()));
  }

  /**
   * Kiểm một mục đã {@link #normalize chuẩn hoá}; ném {@link CatalogViolationException} kèm lỗi
   * từng trường.
   *
   * @param vagueWords từ mơ hồ của Policy Pack (BR-04-03, dùng chung với kiểm KPI mơ hồ)
   */
  public static void validate(CatalogItemDraft d, Collection<String> vagueWords) {
    List<CatalogViolationException.Violation> errors = new ArrayList<>();
    if (d.code() != null && (d.code().length() > CODE_MAX || !CODE.matcher(d.code()).matches())) {
      errors.add(v("code", "Mã công việc có dạng TC-xx-nn, tối đa 20 ký tự."));
    }
    required(errors, "name", d.name(), 500, "Tên công việc");
    String vague = vagueWord(d.name(), vagueWords);
    if (vague != null) {
      errors.add(
          new CatalogViolationException.Violation(
              "name",
              "Tên công việc chứa từ mơ hồ \"" + vague + "\". Hãy nêu cụ thể việc cần làm.",
              CatalogViolationException.Kind.MO_HO));
    }
    required(errors, "productName", d.productName(), 500, "Sản phẩm");
    required(errors, "unit", d.unit(), 50, "Đơn vị tính");
    if (d.difficulty() == null) {
      errors.add(missing("difficulty", "Mức khó"));
    } else if (d.difficulty() < 1 || d.difficulty() > 5) {
      errors.add(v("difficulty", "Mức khó từ 1 đến 5."));
    }
    if (d.baseScore() == null) {
      errors.add(missing("baseScore", "Điểm chuẩn"));
    } else if (d.baseScore().signum() < 0
        || d.baseScore().compareTo(MAX_SCORE) > 0
        || d.baseScore().stripTrailingZeros().scale() > 3) {
      errors.add(v("baseScore", "Điểm chuẩn từ 0 đến 100, tối đa ba chữ số thập phân."));
    }
    required(errors, "qualityCriteria", d.qualityCriteria(), 2000, "Tiêu chuẩn chất lượng");
    required(errors, "evidenceRequired", d.evidenceRequired(), 1000, "Minh chứng yêu cầu");
    if (d.group() == null) {
      errors.add(missing("defaultGroup", "Nhóm mặc định"));
    } else if (!GROUPS.contains(d.group())) {
      errors.add(v("defaultGroup", "Chọn nhóm thường xuyên hoặc then chốt."));
    }
    if (d.standardDays() != null && d.standardDays() < 1) {
      errors.add(v("standardDays", "Thời hạn chuẩn phải lớn hơn 0 ngày làm việc."));
    }
    if (!ITEM_STATUSES.contains(d.status())) {
      errors.add(v("status", "Trạng thái không hợp lệ."));
    }
    errors.addAll(scoring(d.scoring()));
    if (!errors.isEmpty()) {
      throw new CatalogViolationException(errors);
    }
  }

  /**
   * Dạng của dữ liệu chấm điểm mở: mã loại hệ số, mức, mã trục, khung chỉnh (BS-03 DC-01, DC-02).
   */
  static List<CatalogViolationException.Violation> scoring(ItemScoring s) {
    List<CatalogViolationException.Violation> errors = new ArrayList<>();
    Map<String, String> levels = s.coefficientLevels();
    if (levels.size() > MAX_LEVELS
        || levels.entrySet().stream()
            .anyMatch(
                e ->
                    !LEVEL_TYPE.matcher(e.getKey()).matches()
                        || e.getValue() == null
                        || e.getValue().isBlank()
                        || e.getValue().length() > 32)) {
      errors.add(v("coefficientLevels", "Mức hệ số không hợp lệ."));
    }
    if (s.axisCode() != null && !AXIS.matcher(s.axisCode()).matches()) {
      errors.add(v("axisCode", "Mã trục gồm chữ in hoa, chữ số, tối đa 16 ký tự."));
    }
    BigDecimal min = s.scoreAdjustMin();
    if (min != null && (min.compareTo(BigDecimal.ONE.negate()) < 0 || min.signum() > 0)) {
      errors.add(v("scoreAdjustMin", "Tỷ lệ giảm điểm chuẩn tối đa từ -1 đến 0."));
    }
    BigDecimal max = s.scoreAdjustMax();
    if (max != null && (max.signum() < 0 || max.compareTo(BigDecimal.TEN) > 0)) {
      errors.add(v("scoreAdjustMax", "Tỷ lệ tăng điểm chuẩn tối đa từ 0 đến 10."));
    }
    return errors;
  }

  /**
   * Mức hệ số phải thuộc khai báo của Policy Pack: khoá là mã loại đã khai báo, mức thuộc tập mức
   * của loại đó (tập rỗng là không giới hạn mức). Lõi không viết cứng mã loại nào.
   *
   * @param declared loại hệ số và tập mức của gói áp cho kỳ
   */
  public static List<CatalogViolationException.Violation> declaredLevels(
      Map<String, String> levels, Map<String, Set<String>> declared) {
    List<CatalogViolationException.Violation> errors = new ArrayList<>();
    for (Map.Entry<String, String> e : levels.entrySet()) {
      Set<String> allowed = declared.get(e.getKey());
      if (allowed == null) {
        errors.add(
            v(
                "coefficientLevels",
                "Loại hệ số " + e.getKey() + " không có trong chính sách của kỳ."));
      } else if (!allowed.isEmpty() && !allowed.contains(e.getValue())) {
        errors.add(
            v(
                "coefficientLevels",
                "Mức " + e.getValue() + " không thuộc loại hệ số " + e.getKey() + "."));
      }
    }
    return errors;
  }

  /**
   * Khung chỉnh điểm chuẩn của dòng kế thừa (BS-03 DC-02): dòng cấp dưới cùng mã phải có điểm chuẩn
   * trong [điểm cha × (1 + min); điểm cha × (1 + max)]. Dòng cha không đặt khung (cả hai đầu rỗng)
   * thì không giới hạn, giữ hành vi cũ; chỉ đặt một đầu thì đầu kia là 0 (không được chỉnh chiều
   * đó). Trả lời lỗi hoặc {@code null}.
   */
  public static String adjustViolation(
      BigDecimal parentScore, ItemScoring parent, BigDecimal childScore) {
    if (parentScore == null || childScore == null || !parent.bounded()) {
      return null;
    }
    BigDecimal min = parent.scoreAdjustMin() == null ? BigDecimal.ZERO : parent.scoreAdjustMin();
    BigDecimal max = parent.scoreAdjustMax() == null ? BigDecimal.ZERO : parent.scoreAdjustMax();
    BigDecimal low = parentScore.multiply(BigDecimal.ONE.add(min));
    BigDecimal high = parentScore.multiply(BigDecimal.ONE.add(max));
    if (childScore.compareTo(low) >= 0 && childScore.compareTo(high) <= 0) {
      return null;
    }
    return "Điểm chuẩn chỉ được chỉnh trong khoảng "
        + low.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        + " đến "
        + high.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        + " so với danh mục cấp trên.";
  }

  /** Từ mơ hồ đầu tiên có trong tên (so khớp cả từ, không phân biệt dấu), hoặc {@code null}. */
  public static String vagueWord(String name, Collection<String> vagueWords) {
    if (name == null) {
      return null;
    }
    String folded = " " + AccentFold.fold(name).replaceAll("[^a-z0-9]+", " ") + " ";
    for (String word : vagueWords) {
      String w = AccentFold.fold(word).replaceAll("[^a-z0-9]+", " ").strip();
      if (!w.isEmpty() && folded.contains(" " + w + " ")) {
        return word;
      }
    }
    return null;
  }

  /**
   * Mã tự sinh {@code TC-<tiền tố>-nn}: tiền tố là tiền tố dùng nhiều nhất trong các mã đã có của
   * danh mục, nếu chưa có thì {@code CV} (suy luận); số thứ tự lớn nhất cộng một, đủ hai chữ số.
   */
  public static String nextCode(Collection<String> existingCodes) {
    Map<String, Integer> prefixes = new LinkedHashMap<>();
    for (String code : existingCodes) {
      Matcher m = CODE.matcher(code);
      if (m.matches()) {
        prefixes.merge(m.group(1), 1, Integer::sum);
      }
    }
    String prefix =
        prefixes.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(DEFAULT_PREFIX);
    int max = 0;
    Set<String> taken = new HashSet<>(existingCodes);
    for (String code : existingCodes) {
      Matcher m = CODE.matcher(code);
      if (m.matches() && m.group(1).equals(prefix)) {
        max = Math.max(max, Integer.parseInt(m.group(2)));
      }
    }
    String code;
    do {
      max++;
      code = "TC-" + prefix + "-" + String.format(java.util.Locale.ROOT, "%02d", max);
    } while (taken.contains(code));
    return code;
  }

  /**
   * Điểm gợi ý theo mức khó: {@code điểm cơ sở × mức × hệ số} (hệ số mặc định 1), làm tròn một chữ
   * số thập phân, không quá 100. Chỉ để gợi ý khi nhập; điểm tính là điểm chuẩn đã duyệt (TL-02a
   * L1259). Công thức cụ thể là suy luận.
   */
  public static Map<Integer, BigDecimal> scoreSuggestion(
      BigDecimal basePerLevel, Map<Integer, BigDecimal> factors) {
    Map<Integer, BigDecimal> out = new LinkedHashMap<>();
    for (int level = 1; level <= 5; level++) {
      BigDecimal score =
          basePerLevel
              .multiply(BigDecimal.valueOf(level))
              .multiply(factors.getOrDefault(level, BigDecimal.ONE))
              .setScale(1, RoundingMode.HALF_UP);
      out.put(level, score.min(MAX_SCORE));
    }
    return out;
  }

  /**
   * Chặn vòng kế thừa (SCR-0401 trường 2 "Không tạo vòng"): {@code child} không được kế thừa từ
   * {@code parent} khi {@code child} đã là tổ tiên của {@code parent}.
   *
   * @param parentOf danh mục cấp trên trực tiếp của một danh mục
   */
  public static void requireAcyclic(
      UUID child, UUID parent, Function<UUID, Optional<UUID>> parentOf) {
    Set<UUID> seen = new HashSet<>();
    UUID cursor = parent;
    int depth = 0;
    while (cursor != null) {
      if (cursor.equals(child) || !seen.add(cursor) || ++depth > MAX_DEPTH) {
        throw new CatalogViolationException(
            List.of(v("parentCatalogId", "Không thể kế thừa vì sẽ tạo vòng kế thừa danh mục.")));
      }
      cursor = parentOf.apply(cursor).orElse(null);
    }
  }

  private static void required(
      List<CatalogViolationException.Violation> errors,
      String field,
      String value,
      int max,
      String label) {
    if (value == null) {
      errors.add(missing(field, label));
    } else if (value.length() > max) {
      errors.add(v(field, label + " tối đa " + max + " ký tự."));
    }
  }

  private static CatalogViolationException.Violation missing(String field, String label) {
    return new CatalogViolationException.Violation(
        field, label + " là bắt buộc.", CatalogViolationException.Kind.THIEU);
  }

  private static CatalogViolationException.Violation v(String field, String message) {
    return new CatalogViolationException.Violation(field, message);
  }

  private static String trim(String s) {
    if (s == null) {
      return null;
    }
    String t = s.strip();
    return t.isEmpty() ? null : t;
  }
}
