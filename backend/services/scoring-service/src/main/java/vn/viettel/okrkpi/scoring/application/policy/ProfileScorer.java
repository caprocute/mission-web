package vn.viettel.okrkpi.scoring.application.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
    Object flow = RatingClassifier.map(policy.manifest().get("metadata")).get("luong");
    if (flow != null && !flow.equals(input.flow())) {
      throw new IllegalArgumentException(
          "Bộ quy định thuộc luồng " + flow + ", hồ sơ thuộc luồng " + input.flow());
    }
    ScoreCalculator.Calculation calc = calculator.calculate(policy, input);
    RatingClassifier.Rating rating = classifier.classify(policy, calc);
    Map<String, Object> doc = read(calc.result());
    doc.putAll(rating.outputs());
    doc.put("luong", input.flow());
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
        input.personId(),
        input.flow(),
        input.profileId(),
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
