package vn.viettel.okrkpi.scoring.domain;

import java.util.Objects;

/**
 * Một bước của vết tính ({@code scoring.score_trace}): quy tắc, điều khoản, giá trị vào và ra, câu
 * giải thích (nguồn màn hình "vì sao có điểm này").
 */
public record TraceStep(
    int stepNo,
    String ruleCode,
    String legalRef,
    CanonicalDoc inputs,
    CanonicalDoc output,
    String explanation) {

  public TraceStep {
    if (stepNo < 1) {
      throw new IllegalArgumentException("Thứ tự bước bắt đầu từ 1");
    }
    Objects.requireNonNull(ruleCode, "ruleCode");
    if (ruleCode.isBlank() || ruleCode.length() > 64) {
      throw new IllegalArgumentException("Mã quy tắc rỗng hoặc dài quá 64 ký tự");
    }
    if (legalRef != null && legalRef.length() > 200) {
      throw new IllegalArgumentException("Điều khoản dài quá 200 ký tự");
    }
    Objects.requireNonNull(inputs, "inputs");
    Objects.requireNonNull(output, "output");
    if (explanation != null && explanation.length() > 1000) {
      throw new IllegalArgumentException("Câu giải thích dài quá 1000 ký tự");
    }
  }
}
