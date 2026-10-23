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
 * lại, nên bước nằm sau bước đánh dấu điểm không quay lại không được khai bù trừ. Chính bước đó
 * được khai bù trừ cho lệnh đang chờ (ví dụ huỷ yêu cầu ký khi bước ký chưa xong, BR-08-09); bước
 * xong là qua điểm không quay lại.
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
      if (pastPointOfNoReturn && step.compensationCode() != null) {
        throw new IllegalArgumentException(
            "Bước " + step.stepCode() + " sau điểm không quay lại không được khai bù trừ");
      }
      if (step.pointOfNoReturn()) {
        if (pastPointOfNoReturn) {
          throw new IllegalArgumentException("Saga chỉ có một điểm không quay lại");
        }
        pastPointOfNoReturn = true;
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
