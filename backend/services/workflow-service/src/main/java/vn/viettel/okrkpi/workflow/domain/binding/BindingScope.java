package vn.viettel.okrkpi.workflow.domain.binding;

import java.util.Objects;
import java.util.UUID;

/**
 * Phạm vi áp dụng của tham số luồng ({@code WorkflowBindingScope}): các cột khoá {@code flow},
 * {@code subject_group_id}, {@code period_type} của {@code wf_binding} và kỳ bắt đầu.
 *
 * @param flow luồng {@code DANG} hoặc {@code NHA_NUOC}; {@code null} là cả hai
 * @param subjectGroupId khối đối tượng, có thể {@code null}
 * @param periodType loại kỳ, ví dụ {@code QUY}; có thể {@code null}
 * @param fromPeriodId kỳ bắt đầu áp dụng
 */
public record BindingScope(String flow, UUID subjectGroupId, String periodType, UUID fromPeriodId) {

  public BindingScope {
    Objects.requireNonNull(fromPeriodId, "fromPeriodId");
  }
}
