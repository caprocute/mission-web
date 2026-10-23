package vn.viettel.okrkpi.scoring.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.scoring.application.ProfileRecheck;
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
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.job.Jobs;

/**
 * Bốn thao tác SVC-07 của {@code appraisals.yaml}. {@code getAppraisalScoreTrace} (DV-07-03) trả
 * vết tính của kết quả mới nhất; xem trước điểm và tính thử (DV-07-08) tính bằng phiên bản gói gốc,
 * không lưu; tính lại hồ sơ (DV-07-06) mở tác vụ đối chiếu chạy nền. {@code Idempotency-Key} của
 * hai thao tác tính thử không cần lưu vì chúng không đổi trạng thái.
 */
@RestController
@RequestMapping("/api/v1")
public class ScoreTraceController implements AppraisalsApi {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final ScoreTraceService traces;
  private final ScoreSimulationService simulations;
  private final ProfileRecheck rechecks;
  private final ObjectMapper json;

  public ScoreTraceController(
      ScoreTraceService traces,
      ScoreSimulationService simulations,
      ProfileRecheck rechecks,
      ObjectMapper json) {
    this.traces = traces;
    this.simulations = simulations;
    this.rechecks = rechecks;
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

  /**
   * Mở tác vụ tính lại đối chiếu (DV-07-06); 202 kèm {@code Location: /api/v1/jobs/{id}}. Lặp lại
   * cùng {@code Idempotency-Key} trả lại đúng phản hồi đầu, không mở tác vụ thứ hai.
   */
  @Override
  @Idempotent
  public ResponseEntity<SubmitAppraisalSelfAssessment202Response> recomputeAppraisalProfile(
      String idempotencyKey, UUID id) {
    UUID job = rechecks.request(id, CurrentCaller.actor());
    SubmitAppraisalSelfAssessment202Response body =
        new SubmitAppraisalSelfAssessment202Response(
            job.toString(),
            ScoreJobSource.TYPE,
            SubmitAppraisalSelfAssessment202Response.StatusEnum.CHO);
    body.setProgress(0);
    body.setMessage("Đang chờ tính lại để đối chiếu.");
    body.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    return ResponseEntity.accepted()
        .location(Jobs.location(job.toString()))
        .header(HttpHeaders.RETRY_AFTER, Long.toString(Jobs.DEFAULT_RETRY_AFTER_SECONDS))
        .body(body);
  }

  private Map<String, Object> read(String value) {
    try {
      return json.readValue(value, MAP);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Vết tính không đọc được", e);
    }
  }
}
