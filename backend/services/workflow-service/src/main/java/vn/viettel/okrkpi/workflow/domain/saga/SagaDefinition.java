package vn.viettel.okrkpi.workflow.domain.saga;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Định nghĩa một loại saga: danh sách bước theo thứ tự và việc có tự bù trừ khi một bước trước điểm
 * không quay lại hết số lần thử hay không.
 *
 * <p>Ràng buộc theo TL-03 "Saga điều phối chốt kỳ": sau điểm không quay lại các bước chỉ được thử
 * lại, nên bước nằm sau bước đánh dấu điểm không quay lại (và chính bước đó) không được khai bù
 * trừ.
 */
public record SagaDefinition(
    SagaType type, List<SagaStepDefinition> steps, boolean compensateOnExhaustion) {

  public SagaDefinition {
    Objects.requireNonNull(type, "type");
    steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("Saga phải có ít nhất một bước");
    }
    Set<String> codes = new HashSet<>();
    boolean pastPointOfNoReturn = false;
    for (SagaStepDefinition step : steps) {
      if (!codes.add(step.stepCode())) {
        throw new IllegalArgumentException("Trùng mã bước: " + step.stepCode());
      }
      if (step.pointOfNoReturn()) {
        if (pastPointOfNoReturn) {
          throw new IllegalArgumentException("Saga chỉ có một điểm không quay lại");
        }
        pastPointOfNoReturn = true;
      }
      if (pastPointOfNoReturn && step.compensationCode() != null) {
        throw new IllegalArgumentException(
            "Bước " + step.stepCode() + " ở hoặc sau điểm không quay lại không được khai bù trừ");
      }
    }
  }

  public int size() {
    return steps.size();
  }

  /** Bước có số thứ tự {@code stepNo} (đếm từ 1). */
  public SagaStepDefinition step(int stepNo) {
    if (stepNo < 1 || stepNo > steps.size()) {
      throw new IllegalArgumentException("Không có bước " + stepNo + " trong saga " + type);
    }
    return steps.get(stepNo - 1);
  }
}
