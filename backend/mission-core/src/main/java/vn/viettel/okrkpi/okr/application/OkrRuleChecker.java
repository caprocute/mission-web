package vn.viettel.okrkpi.okr.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.cel.application.CelCompiledProgram;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelSandboxOptions;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;

/**
 * Bộ kiểm tra quy tắc OKR BR-02-01..07 ở máy chủ (CORE-01-04, QT-02 bước 4): chạy cùng bộ quy tắc
 * CEL mà trình duyệt chạy bằng cel-js ({@code fe-okr/okr-rules.ts}), trên cùng các biến {@code o.*}
 * và {@code kr.*}. Việc dò từ mơ hồ, động từ nhiệm vụ và trường thiếu tính trước thành biến vì
 * cel-js chưa có hàm chuỗi (wiki trang 10). Kết quả tính lại mỗi lần đọc, không lưu; máy chủ là
 * nguồn sự thật cuối cùng khi trình (AGENTS.md quy tắc 6).
 */
public class OkrRuleChecker {

  private static final Logger log = LoggerFactory.getLogger(OkrRuleChecker.class);

  public static final String CHAN = "CHAN";
  public static final String CANH_BAO = "CANH_BAO";

  /** Mức kết quả theo {@code OkrCheckLevel} của okr.yaml. */
  public static final String DAT = "DAT";

  public static final String LOI_CHAN = "LOI_CHAN";

  private static final Locale VI = Locale.forLanguageTag("vi");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
  private static final Map<String, CelValueType> O_VARS = Map.of("o", CelValueType.MAP_STRING_DYN);
  private static final Map<String, CelValueType> KR_VARS =
      Map.of("kr", CelValueType.MAP_STRING_DYN);

  /** Một quy tắc soát OKR, cùng cấu trúc {@code OkrRule} của okr.yaml. */
  public record Rule(
      String id,
      String br,
      String scope,
      String severity,
      String when,
      String assertion,
      String message,
      String passLabel,
      boolean serverOnly) {}

  /** Tham số soát của Policy Pack đang áp dụng ({@code OkrPolicy}). */
  public record Policy(
      String version,
      int recommendedMax,
      List<String> vagueWords,
      List<String> taskVerbs,
      List<String> units,
      List<Rule> rules) {}

  /** Dữ liệu của một KR cần soát. */
  public record KrInput(
      UUID id,
      String code,
      String title,
      String unit,
      BigDecimal baselineValue,
      BigDecimal targetValue,
      BigDecimal weight,
      LocalDate dueDate,
      UUID verifierPersonId) {}

  /** Dữ liệu mục tiêu cần soát cùng ngữ cảnh máy chủ biết. */
  public record ObjectiveInput(
      String title,
      int basisCount,
      boolean hasParent,
      boolean isTopLevel,
      boolean parentApproved,
      String parentCode,
      long totalObjectives,
      String overLimitReason,
      UUID krOwnerPersonId,
      List<KrInput> keyResults) {}

  /** Một kết quả, khớp {@code OkrCheckResult}: {@code level} là DAT, CANH_BAO hoặc LOI_CHAN. */
  public record Finding(
      String ruleId,
      String br,
      String level,
      String message,
      UUID keyResultId,
      String similarTaskCode) {}

  /**
   * Từ mơ hồ mặc định (BR-02-05, TL-02a QT-02) khi Policy Pack chưa đặt {@code okr_vague_words}.
   */
  public static final List<String> DEFAULT_VAGUE_WORDS =
      List.of("tăng cường", "đẩy mạnh", "kịp thời", "nâng cao", "chú trọng", "quan tâm");

  /** Động từ mở đầu giống nhiệm vụ mặc định (BR-02-06). */
  public static final List<String> DEFAULT_TASK_VERBS =
      List.of("tổ chức", "ban hành", "xây dựng", "triển khai", "họp", "tập huấn");

  /** Đơn vị đo gợi ý mặc định (SCR-0202 trường 9). */
  public static final List<String> DEFAULT_UNITS =
      List.of("%", "người", "hồ sơ", "ngày", "lần", "mốc", "chi bộ", "tổ chức");

  /**
   * Bộ quy tắc mặc định, giống hệt bộ của trình duyệt và của {@code data/golden/okr-rules}; Policy
   * Pack thay bằng tham số {@code okr_rules}. {@code KR-XN} chỉ chạy ở máy chủ.
   */
  public static final List<Rule> DEFAULT_RULES =
      List.of(
          new Rule(
              "O-TEN",
              "SCR-0202",
              "objective",
              CHAN,
              "true",
              "o.titleLength > 0 && o.titleLength <= 200",
              "Tên mục tiêu không được trống và tối đa 200 ký tự.",
              null,
              false),
          new Rule(
              "BR-02-02-CC",
              "BR-02-02",
              "objective",
              CHAN,
              "true",
              "o.basisCount >= 1",
              "Mục tiêu phải có ít nhất một căn cứ.",
              "Có căn cứ",
              false),
          new Rule(
              "BR-02-02-CT",
              "BR-02-02",
              "objective",
              CHAN,
              "!o.isTopLevel",
              "o.hasParent",
              "Mục tiêu phải liên kết mục tiêu cấp trên.",
              "Có mục tiêu cấp trên",
              false),
          new Rule(
              "MSG-02-03",
              "BR-02-02",
              "objective",
              CHAN,
              "o.hasParent",
              "o.parentApproved",
              "Mục tiêu cấp trên {parentCode} chưa được duyệt. Đồng chí có thể soạn nhưng chưa trình"
                  + " được.",
              null,
              false),
          new Rule(
              "BR-02-01",
              "BR-02-01",
              "objective",
              CANH_BAO,
              "true",
              "o.totalObjectives <= o.recommendedMax",
              "{totalObjectives} mục tiêu trong kỳ, vượt khuyến nghị {recommendedMax}.",
              null,
              false),
          new Rule(
              "BR-02-01-LD",
              "BR-02-01",
              "objective",
              CHAN,
              "o.totalObjectives > o.recommendedMax",
              "o.overLimitReasonLength > 0",
              "Vui lòng ghi lý do vượt {recommendedMax} mục tiêu.",
              null,
              false),
          new Rule(
              "BR-02-03",
              "BR-02-03",
              "objective",
              CANH_BAO,
              "true",
              "o.krCount >= 2 && o.krCount <= 5",
              "Mục tiêu nên có từ 2 đến 5 KR (hiện có {krCount}).",
              null,
              false),
          new Rule(
              "BR-02-07",
              "BR-02-07",
              "objective",
              CHAN,
              "true",
              "o.totalWeight == 100",
              "Tổng trọng số KR là {totalWeight}%, cần bằng 100%.",
              "Tổng trọng số KR = 100%",
              false),
          new Rule(
              "KR-ND",
              "SCR-0202",
              "kr",
              CHAN,
              "true",
              "kr.titleLength > 0 && kr.titleLength <= 300",
              "{code} cần nội dung, tối đa 300 ký tự.",
              null,
              false),
          new Rule(
              "BR-02-05",
              "BR-02-05",
              "kr",
              CHAN,
              "kr.vagueWord != \"\"",
              "kr.hasTarget && kr.unit != \"\"",
              "KPI mơ hồ: \"{vagueWord}\" không đo được. Vui lòng bổ sung chỉ tiêu và đơn vị đo.",
              null,
              false),
          new Rule(
              "BR-02-04",
              "BR-02-04",
              "kr",
              CHAN,
              "true",
              "kr.missing == \"\"",
              "{code} thiếu {missing}.",
              "Mọi KR có đơn vị đo, giá trị đầu, chỉ tiêu, hạn, người xác nhận",
              false),
          new Rule(
              "KR-CT",
              "SCR-0202",
              "kr",
              CHAN,
              "kr.hasTarget && kr.hasBaseline",
              "kr.targetDiffers",
              "{code}: chỉ tiêu phải khác giá trị đầu.",
              null,
              false),
          new Rule(
              "BR-02-06",
              "BR-02-06",
              "kr",
              CANH_BAO,
              "true",
              "kr.taskVerb == \"\"",
              "{code} giống một nhiệm vụ, không phải kết quả đo được. Cân nhắc chuyển sang Kế hoạch"
                  + " quý.",
              null,
              false),
          new Rule(
              "KR-XN",
              "SCR-0202",
              "kr",
              CHAN,
              "kr.hasVerifier",
              "kr.verifierDiffers",
              "Người xác nhận phải khác chủ sở hữu KR.",
              null,
              true));

  public static final Policy DEFAULT_POLICY =
      new Policy(
          "mac-dinh",
          OkrReadService.RECOMMENDED_MAX,
          DEFAULT_VAGUE_WORDS,
          DEFAULT_TASK_VERBS,
          DEFAULT_UNITS,
          DEFAULT_RULES);

  private final CelEngine cel =
      new CelEngine(StaticWorkingCalendar.weekendsOnly(), CelSandboxOptions.defaults());

  /** Chạy mọi quy tắc của {@code policy} trên mục tiêu; kết quả DAT cho quy tắc có nhãn đạt. */
  public List<Finding> check(ObjectiveInput input, Policy policy) {
    Map<String, Object> o = objectiveVars(input, policy);
    List<Finding> violations = new ArrayList<>();
    for (Rule r : policy.rules()) {
      if ("objective".equals(r.scope()) && violated(r, "o", O_VARS, o)) {
        violations.add(finding(r, o, null));
      }
    }
    for (KrInput k : input.keyResults()) {
      Map<String, Object> kr = krVars(k, input.krOwnerPersonId(), policy);
      for (Rule r : policy.rules()) {
        if ("kr".equals(r.scope()) && violated(r, "kr", KR_VARS, kr)) {
          violations.add(finding(r, kr, k.id()));
        }
      }
    }
    Set<String> failed = new HashSet<>();
    violations.forEach(f -> failed.add(f.ruleId()));
    List<Finding> result = new ArrayList<>();
    for (Rule r : policy.rules()) {
      if (r.passLabel() != null && !failed.contains(r.id())) {
        result.add(new Finding(r.id(), r.br(), DAT, r.passLabel(), null, null));
      }
    }
    result.addAll(violations);
    return result;
  }

  /** Có kết quả lỗi chặn (dùng khi trình, CORE-01-05). */
  public static boolean blocking(List<Finding> findings) {
    return findings.stream().anyMatch(f -> LOI_CHAN.equals(f.level()));
  }

  /** Mã quy tắc bị vi phạm khi chạy trực tiếp trên biến đã tính (bộ ca dùng chung với cel-js). */
  public List<String> violations(String scope, Map<String, Object> vars, List<Rule> rules) {
    String name = "objective".equals(scope) ? "o" : "kr";
    Map<String, CelValueType> types = "o".equals(name) ? O_VARS : KR_VARS;
    List<String> ids = new ArrayList<>();
    for (Rule r : rules) {
      if (scope.equals(r.scope()) && violated(r, name, types, vars)) {
        ids.add(r.id());
      }
    }
    return ids;
  }

  /** Biến {@code o.*}, cùng tên và cách tính với {@code objectiveVars} của trình duyệt. */
  public static Map<String, Object> objectiveVars(ObjectiveInput in, Policy policy) {
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("titleLength", (long) strip(in.title()).length());
    o.put("basisCount", (long) in.basisCount());
    o.put("hasParent", in.hasParent());
    o.put("isTopLevel", in.isTopLevel());
    o.put("parentApproved", in.parentApproved());
    o.put("parentCode", in.parentCode() == null ? "" : in.parentCode());
    o.put("krCount", (long) in.keyResults().size());
    o.put("totalWeight", number(totalWeightPct(in.keyResults())));
    o.put("totalObjectives", in.totalObjectives());
    o.put("recommendedMax", (long) policy.recommendedMax());
    o.put("overLimitReasonLength", (long) strip(in.overLimitReason()).length());
    return o;
  }

  /** Biến {@code kr.*}, cùng tên và cách tính với {@code krVars} của trình duyệt. */
  public static Map<String, Object> krVars(KrInput k, UUID owner, Policy policy) {
    Map<String, Object> kr = new LinkedHashMap<>();
    String title = k.title() == null ? "" : k.title();
    kr.put("code", k.code() == null ? "" : k.code());
    kr.put("titleLength", (long) strip(title).length());
    kr.put("unit", strip(k.unit()));
    kr.put("hasBaseline", k.baselineValue() != null);
    kr.put("hasTarget", k.targetValue() != null);
    kr.put(
        "targetDiffers",
        k.baselineValue() != null
            && k.targetValue() != null
            && k.baselineValue().compareTo(k.targetValue()) != 0);
    kr.put("hasDue", k.dueDate() != null);
    kr.put("hasVerifier", k.verifierPersonId() != null);
    kr.put("verifierDiffers", k.verifierPersonId() != null && !k.verifierPersonId().equals(owner));
    kr.put("vagueWord", vagueWord(title, policy.vagueWords()));
    kr.put("taskVerb", taskVerb(title, policy.taskVerbs()));
    kr.put("missing", missing(k));
    return kr;
  }

  /** Từ mơ hồ đầu tiên có trong nội dung KR, rỗng nếu không có. */
  static String vagueWord(String title, List<String> words) {
    String t = lower(title);
    return words.stream().filter(w -> t.contains(lower(w))).findFirst().orElse("");
  }

  /** Động từ nhiệm vụ mở đầu nội dung KR, rỗng nếu không có. */
  static String taskVerb(String title, List<String> verbs) {
    String t = lower(title);
    return verbs.stream().filter(v -> t.startsWith(lower(v) + " ")).findFirst().orElse("");
  }

  static String missing(KrInput k) {
    List<String> miss = new ArrayList<>();
    if (strip(k.unit()).isEmpty()) {
      miss.add("đơn vị đo");
    }
    if (k.baselineValue() == null) {
      miss.add("giá trị đầu");
    }
    if (k.targetValue() == null) {
      miss.add("chỉ tiêu");
    }
    if (k.dueDate() == null) {
      miss.add("hạn");
    }
    if (k.verifierPersonId() == null) {
      miss.add("người xác nhận");
    }
    return String.join(", ", miss);
  }

  /** Tổng trọng số theo phần trăm, làm tròn hai chữ số như ô nhập của SCR-0202. */
  static BigDecimal totalWeightPct(List<KrInput> krs) {
    BigDecimal sum = BigDecimal.ZERO;
    for (KrInput k : krs) {
      if (k.weight() != null) {
        sum = sum.add(k.weight());
      }
    }
    return sum.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
  }

  private boolean violated(
      Rule r, String name, Map<String, CelValueType> types, Map<String, Object> vars) {
    Map<String, Object> input = Map.of(name, vars);
    try {
      CelCompiledProgram when = cel.compile(r.when(), types, CelValueType.BOOL);
      if (!Boolean.TRUE.equals(cel.evaluate(when, input))) {
        return false;
      }
      CelCompiledProgram assertion = cel.compile(r.assertion(), types, CelValueType.BOOL);
      return !Boolean.TRUE.equals(cel.evaluate(assertion, input));
    } catch (RuntimeException e) {
      // Quy tắc hỏng của Policy Pack không được làm hỏng việc đọc; bỏ qua và ghi nhật ký.
      log.warn("Bỏ qua quy tắc OKR {} vì không chạy được: {}", r.id(), e.getMessage());
      return false;
    }
  }

  private static Finding finding(Rule r, Map<String, Object> vars, UUID keyResultId) {
    return new Finding(
        r.id(),
        r.br(),
        CHAN.equals(r.severity()) ? LOI_CHAN : CANH_BAO,
        fill(r.message(), vars),
        keyResultId,
        null);
  }

  /** Điền chỗ trống {@code {tên}} bằng biến cùng tên, như {@code fillMessage} của trình duyệt. */
  static String fill(String template, Map<String, Object> vars) {
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      Object v = vars.get(m.group(1));
      m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : display(v)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static String display(Object v) {
    if (v instanceof Double d && d == Math.rint(d)) {
      return Long.toString(d.longValue());
    }
    return String.valueOf(v);
  }

  /** Số nguyên thành {@code Long}, số lẻ thành {@code Double} (CEL phân biệt int và double). */
  private static Object number(BigDecimal value) {
    BigDecimal v = value.stripTrailingZeros();
    return v.scale() <= 0 ? (Object) v.longValueExact() : (Object) v.doubleValue();
  }

  private static String lower(String s) {
    return strip(s).toLowerCase(VI);
  }

  private static String strip(String s) {
    return s == null ? "" : s.strip();
  }

  /** Kiểm tra trùng mã quy tắc trong bộ quy tắc (dùng khi đọc tham số Policy Pack). */
  public static boolean valid(List<Rule> rules) {
    Set<String> ids = new HashSet<>();
    for (Rule r : rules) {
      if (r.id() == null
          || r.when() == null
          || r.assertion() == null
          || r.message() == null
          || !ids.add(r.id())
          || !Objects.equals(r.scope(), "objective") && !Objects.equals(r.scope(), "kr")) {
        return false;
      }
    }
    return true;
  }
}
