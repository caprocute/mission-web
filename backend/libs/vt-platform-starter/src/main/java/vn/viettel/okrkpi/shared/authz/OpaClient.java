package vn.viettel.okrkpi.shared.authz;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Giao diện gọi OPA cùng pod để lấy quyết định uỷ quyền ({@code /v1/data/okrkpi/authz/decision}) và
 * đánh giá từng phần ({@code /v1/compile}) (NEN-014, ADR-21).
 */
public interface OpaClient {

  /**
   * Hỏi quyết định uỷ quyền từ OPA; lỗi kết nối hoặc quá thời hạn sẽ từ chối (fail closed) và ghi
   * metric.
   */
  OpaDecision decide(OpaInput input);

  /**
   * Đánh giá từng phần ({@code POST /v1/compile}) với tập {@code unknowns} là thuộc tính tài nguyên
   * để dịch sang mệnh đề SQL.
   *
   * @throws UntranslatablePolicyException khi OPA lỗi, quá hạn hoặc trả kết quả không hợp lệ
   */
  JsonNode compile(String query, Map<String, Object> input, List<String> unknowns);

  /** Phiên bản bundle hiện hành dùng trong khoá đệm quyết định. */
  String currentBundleRevision();

  /** Cập nhật phiên bản bundle và xoá bộ đệm quyết định cũ. */
  void invalidateCache(String newBundleRevision);
}
