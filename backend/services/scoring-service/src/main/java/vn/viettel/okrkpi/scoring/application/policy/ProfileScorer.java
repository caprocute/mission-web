package vn.viettel.okrkpi.scoring.application.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import vn.viettel.okrkpi.scoring.domain.AnnualInput;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ComputedScore;
import vn.viettel.okrkpi.scoring.domain.ScoreStatus;
import vn.viettel.okrkpi.scoring.domain.ScoringInput;
import vn.viettel.okrkpi.scoring.domain.TraceStep;

/**
 * Tính điểm rồi xếp loại một hồ sơ trong một luồng (DV-07-05): kết quả gồm điểm, mức hệ thống gợi
 * ý, dòng bảng, hạ mức, điều kiện thiếu và câu giải thích, đủ để MOD-04 lập {@code score_snapshot}
 * và {@code rating_explanation} từ EVT-10. Mỗi luồng (Đảng, Nhà nước) dùng gói riêng theo {@code
 * policy_binding} và cho kết quả riêng (BR-09-11); gói khai báo {@code metadata.luong} khác luồng
 * của hồ sơ thì từ chối.
 */
public final class ProfileScorer {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final ScoreCalculator calculator;
  private final RatingClassifier classifier;

  public ProfileScorer(ScoreCalculator calculator, RatingClassifier classifier) {
    this.calculator = Objects.requireNonNull(calculator, "calculator");
    this.classifier = Objects.requireNonNull(classifier, "classifier");
  }

  /**
   * Tính và xếp loại.
   *
   * @throws IllegalArgumentException gói thuộc luồng khác luồng của hồ sơ
   */
  public ComputedScore score(UUID resultId, CompiledPolicy policy, ScoringInput input) {
    checkFlow(policy, input.flow());
    ScoreCalculator.Calculation calc = calculator.calculate(policy, input);
    return result(resultId, policy, calc, input.flow(), input.personId(), input.profileId(), null);
  }

  /** Kết quả năm lần trước của hồ sơ, để MOD-04 báo thay đổi (MSG-10-04). */
  public record Previous(BigDecimal score, String rating) {}

  /**
   * Tính và xếp loại năm (DV-07-07, QT-10) trên kết quả các quý đã quyết định. Có {@code previous}
   * khác kết quả mới (quý được điều chỉnh sau hậu kiểm) thì kết quả mang {@code ket_qua_truoc}.
   *
   * @throws IllegalArgumentException gói thuộc luồng khác luồng của hồ sơ
   */
  public ComputedScore scoreAnnual(
      UUID resultId, CompiledPolicy policy, AnnualInput input, Previous previous) {
    checkFlow(policy, input.flow());
    CanonicalDoc doc = input.document(annualParams(policy));
    // Kỳ năm nằm trong input_doc ("ky": "NAM") nên không trùng băm với kết quả quý.
    ScoreCalculator.Calculation calc =
        calculator.calculateAnnual(policy, doc, input.variables(), Map.of());
    return result(
        resultId, policy, calc, input.flow(), input.personId(), input.profileId(), previous);
  }

  /**
   * Tham số năm mà phạm vi {@code nam} đọc (khoá phẳng → giá trị mặc định), đưa vào {@code
   * input_doc} năm.
   */
  static Map<String, Object> annualParams(CompiledPolicy policy) {
    Map<String, Object> ts = ScoreCalculator.params(policy.params(), Map.of());
    Map<String, Object> out = new TreeMap<>();
    Object defs = RatingClassifier.map(policy.manifest().get("definitions_nam")).get("nam");
    for (String key : RatingClassifier.map(defs).keySet()) {
      CompiledPolicy.Rule rule = policy.rules().get(key);
      if (rule != null) {
        ScoreCalculator.usedParams(rule.program().expression(), ts)
            .forEach((k, v) -> out.put(k.substring("tham_so.".length()), v));
      }
    }
    return out;
  }

  private static void checkFlow(CompiledPolicy policy, String inputFlow) {
    Object flow = RatingClassifier.map(policy.manifest().get("metadata")).get("luong");
    if (flow != null && !flow.equals(inputFlow)) {
      throw new IllegalArgumentException(
          "Bộ quy định thuộc luồng " + flow + ", hồ sơ thuộc luồng " + inputFlow);
    }
  }

  private ComputedScore result(
      UUID resultId,
      CompiledPolicy policy,
      ScoreCalculator.Calculation calc,
      String flowCode,
      UUID personId,
      UUID profileId,
      Previous previous) {
    RatingClassifier.Rating rating = classifier.classify(policy, calc);
    Map<String, Object> doc = read(calc.result());
    doc.putAll(rating.outputs());
    if (calc.outputs().containsKey(ScoreCalculator.ANNUAL_SCORE_KEY)) {
      doc.put("ky", "NAM");
    }
    if (previous != null
        && (previous.score().compareTo(calc.totalScore()) != 0
            || !previous.rating().equals(rating.level()))) {
      Map<String, Object> before = new LinkedHashMap<>();
      before.put("diem", previous.score());
      before.put("muc", previous.rating());
      doc.put("ket_qua_truoc", before);
    }
    doc.put("luong", flowCode);
    doc.put("muc_he_thong", rating.level());
    doc.put("muc_theo_bang", rating.tableLevel());
    if (rating.ruleId() != null) {
      doc.put("dong_dmn", rating.ruleId());
    }
    doc.put("ha_muc", rating.lowered());
    doc.put("dieu_kien_thieu", rating.missing());
    if (rating.explanation() != null) {
      doc.put("giai_thich", rating.explanation());
    }
    List<TraceStep> trace = new ArrayList<>(calc.trace());
    trace.addAll(rating.steps());
    return new ComputedScore(
        resultId,
        personId,
        flowCode,
        profileId,
        calc.input(),
        CanonicalDoc.of(doc),
        calc.totalScore(),
        rating.level(),
        ScoreStatus.OK,
        trace);
  }

  private static Map<String, Object> read(CanonicalDoc doc) {
    try {
      return new LinkedHashMap<>(
          JSON.readValue(doc.json(), new TypeReference<Map<String, Object>>() {}));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Kết quả tính không phải JSON", e);
    }
  }
}
