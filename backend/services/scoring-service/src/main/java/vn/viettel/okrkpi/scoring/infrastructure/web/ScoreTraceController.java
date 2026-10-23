package vn.viettel.okrkpi.scoring.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.scoring.application.ScoreSimulationService;
import vn.viettel.okrkpi.scoring.application.ScoreTraceService;
import vn.viettel.okrkpi.scoring.domain.ScoreTrace;
import vn.viettel.okrkpi.scoring.domain.TraceStep;
import vn.viettel.okrkpi.scoring.infrastructure.web.api.AppraisalsApi;
import vn.viettel.okrkpi.scoring.infrastructure.web.model.AppraisalScoreOutcome;
import vn.viettel.okrkpi.scoring.infrastructure.web.model.AppraisalScorePreview;
import vn.viettel.okrkpi.scoring.infrastructure.web.model.AppraisalScorePreviewInput;
import vn.viettel.okrkpi.scoring.infrastructure.web.model.AppraisalScoreTrace;
import vn.viettel.okrkpi.scoring.infrastructure.web.model.AppraisalScoreTraceStep;
import vn.viettel.okrkpi.scoring.infrastructure.web.model.AppraisalSimulationInput;
import vn.viettel.okrkpi.scoring.infrastructure.web.model.AppraisalSimulationResult;
import vn.viettel.okrkpi.scoring.infrastructure.web.model.SubmitAppraisalSelfAssessment202Response;

/**
 * Bốn thao tác SVC-07 của {@code appraisals.yaml}. {@code getAppraisalScoreTrace} (DV-07-03) trả
 * vết tính của kết quả mới nhất; xem trước điểm và tính thử (DV-07-08) tính bằng phiên bản gói gốc,
 * không lưu; tính lại hồ sơ (DV-07-06) chưa hiện thực nên trả 501. {@code Idempotency-Key} của hai
 * thao tác tính thử không cần lưu vì chúng không đổi trạng thái.
 */
@RestController
@RequestMapping("/api/v1")
public class ScoreTraceController implements AppraisalsApi {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final ScoreTraceService traces;
  private final ScoreSimulationService simulations;
  private final ObjectMapper json;

  public ScoreTraceController(
      ScoreTraceService traces, ScoreSimulationService simulations, ObjectMapper json) {
    this.traces = traces;
    this.simulations = simulations;
    this.json = json;
  }

  @Override
  public ResponseEntity<AppraisalScoreTrace> getAppraisalScoreTrace(
      UUID id, String flow, UUID taskId) {
    if (flow != null && !"DANG".equals(flow) && !"NHA_NUOC".equals(flow)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Luồng đánh giá không hợp lệ");
    }
    ScoreTrace trace = traces.find(id, flow, taskId);
    AppraisalScoreTrace body =
        new AppraisalScoreTrace()
            .profileId(trace.profileId())
            .scoreResultId(trace.resultId())
            .policyVersion(trace.policyVersion())
            .engineVersion(trace.engineVersion());
    for (TraceStep s : trace.steps()) {
      String task = ScoreTraceService.taskOf(s);
      body.addStepsItem(
          new AppraisalScoreTraceStep()
              .stepNo(s.stepNo())
              .ruleCode(s.ruleCode())
              .legalRef(s.legalRef())
              .taskId(task == null ? null : UUID.fromString(task))
              .inputs(read(s.inputs().json()))
              .output(read(s.output().json()))
              .explanation(s.explanation()));
    }
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<AppraisalScorePreview> previewAppraisalTaskScore(
      String idempotencyKey, AppraisalScorePreviewInput in) {
    ScoreSimulationService.Preview p =
        simulations.preview(
            new ScoreSimulationService.PreviewRequest(
                in.getTaskId(),
                in.getQualityLevel().getValue(),
                in.getRevisionCause() == null ? null : in.getRevisionCause().getValue(),
                in.getIsObjectiveRevision()));
    return ResponseEntity.ok(
        new AppraisalScorePreview()
            .a(p.a())
            .b(level(p.b()))
            .c(p.c())
            .kpi(p.kpi())
            .policyVersion(p.policy()));
  }

  @Override
  public ResponseEntity<AppraisalSimulationResult> simulateAppraisalScore(
      String idempotencyKey, UUID id, AppraisalSimulationInput in) {
    List<ScoreSimulationService.Adjustment> adjustments =
        in.getAdjustments().stream()
            .map(
                a ->
                    new ScoreSimulationService.Adjustment(
                        a.getComponent(), a.getTaskId(), a.getNewValue()))
            .toList();
    ScoreSimulationService.Simulation s = simulations.simulate(id, adjustments);
    return ResponseEntity.ok(
        new AppraisalSimulationResult()
            .policyVersion(s.policyVersion())
            .before(outcome(s.before()))
            .after(outcome(s.after()))
            .explanation(s.explanation()));
  }

  private static AppraisalScoreOutcome outcome(ScoreSimulationService.Outcome o) {
    return new AppraisalScoreOutcome()
        .totalScore(o.totalScore())
        .ratingCode(AppraisalScoreOutcome.RatingCodeEnum.fromValue(o.ratingCode()));
  }

  /** B theo năm mức của {@code QualityLevel}; so sánh theo giá trị, không theo số chữ số. */
  static AppraisalScorePreview.BEnum level(BigDecimal b) {
    for (AppraisalScorePreview.BEnum e : AppraisalScorePreview.BEnum.values()) {
      if (e.getValue().compareTo(b) == 0) {
        return e;
      }
    }
    throw new IllegalStateException("Mức chất lượng ngoài năm mức: " + b);
  }

  @Override
  public ResponseEntity<SubmitAppraisalSelfAssessment202Response> recomputeAppraisalProfile(
      String idempotencyKey, UUID id) {
    throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  private Map<String, Object> read(String value) {
    try {
      return json.readValue(value, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Vết tính không đọc được", e);
    }
  }
}
