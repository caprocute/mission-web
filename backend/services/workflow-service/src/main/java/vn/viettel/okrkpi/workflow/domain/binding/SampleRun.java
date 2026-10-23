package vn.viettel.okrkpi.workflow.domain.binding;

import java.util.List;
import java.util.Objects;

/**
 * Kết quả chạy một hồ sơ mẫu trên engine thử ({@code sampleRuns[]} của {@code
 * WorkflowSimulateResult}).
 *
 * @param sample ca mẫu
 * @param skippedSteps mã các bước bị bỏ qua
 * @param endState trạng thái kết thúc: {@link SimulationSample#DA_DUYET}, {@link
 *     SimulationSample#TRA_LAI} hoặc mã phần tử nơi tiến trình dừng
 * @param passed kết thúc và số bước đi qua đúng như mong đợi
 */
public record SampleRun(
    SimulationSample sample, List<String> skippedSteps, String endState, boolean passed) {

  public SampleRun {
    Objects.requireNonNull(sample, "sample");
    Objects.requireNonNull(endState, "endState");
    skippedSteps = skippedSteps == null ? List.of() : List.copyOf(skippedSteps);
  }
}
