package vn.viettel.okrkpi.scoring.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.scoring.application.ScoreTraceService;
import vn.viettel.okrkpi.scoring.domain.ScoreTrace;
import vn.viettel.okrkpi.scoring.domain.TraceStep;
import vn.viettel.okrkpi.scoring.infrastructure.web.api.AppraisalsApi;
import vn.viettel.okrkpi.scoring.infrastructure.web.model.AppraisalScorePreview;
import vn.viettel.okrkpi.scoring.infrastructure.web.model.AppraisalScorePreviewInput;
import vn.viettel.okrkpi.scoring.infrastructure.web.model.AppraisalScoreTrace;
import vn.viettel.okrkpi.scoring.infrastructure.web.model.AppraisalScoreTraceStep;
import vn.viettel.okrkpi.scoring.infrastructure.web.model.SubmitAppraisalSelfAssessment202Response;

/**
 * Ba thao tác SVC-07 của {@code appraisals.yaml}. {@code getAppraisalScoreTrace} (DV-07-03) trả vết
 * tính của kết quả mới nhất; xem trước điểm (DV-07-08) và tính lại hồ sơ (DV-07-06) chưa hiện thực
 * nên trả 501.
 */
@RestController
@RequestMapping("/api/v1")
public class ScoreTraceController implements AppraisalsApi {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final ScoreTraceService traces;
  private final ObjectMapper json;

  public ScoreTraceController(ScoreTraceService traces, ObjectMapper json) {
    this.traces = traces;
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
      String idempotencyKey, AppraisalScorePreviewInput appraisalScorePreviewInput) {
    throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
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
