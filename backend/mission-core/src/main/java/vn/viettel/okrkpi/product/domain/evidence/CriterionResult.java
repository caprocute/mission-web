package vn.viettel.okrkpi.product.domain.evidence;

/** Kết quả đối chiếu một tiêu chuẩn chất lượng ({@code product.acceptance.criteria_results}). */
public record CriterionResult(String code, boolean passed, String note) {}
