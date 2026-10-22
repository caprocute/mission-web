package vn.viettel.okrkpi.policy.application.port;

import java.util.List;
import java.util.Map;

/**
 * Cổng chạy lại biểu thức CEL trên cel-js để đối chiếu với cel-java (CORE-05-07, BR-15-07, ADR-19).
 * Máy chủ Java không chạy được cel-js trực tiếp; bộ chạy là dịch vụ Node riêng ({@code
 * backend/services/cel-js-runner}) dùng chung hàm Mission của {@code @vt/form-engine}.
 */
public interface CelJsRunner {

  /**
   * Một lần đánh giá: biểu thức, kiểu biến theo tên {@code CelValueType}, kiểu trả về và giá trị
   * các biến biểu thức tham chiếu.
   */
  record Evaluation(
      String expression,
      Map<String, String> variableTypes,
      String returnType,
      Map<String, Object> inputs) {}

  /** Kết quả cel-js; {@code ok = false} kèm mã lỗi chuẩn {@code POLICY-422-CEL-*}. */
  record Result(boolean ok, Object value, String errorCode, String message) {}

  /** Bộ chạy không cấu hình. */
  CelJsRunner NONE =
      new CelJsRunner() {
        @Override
        public boolean available() {
          return false;
        }

        @Override
        public List<Result> evaluate(List<Evaluation> evaluations) {
          throw new IllegalStateException("Chưa cấu hình bộ chạy cel-js");
        }
      };

  boolean available();

  /**
   * Đánh giá theo lô, kết quả cùng thứ tự đầu vào.
   *
   * @throws IllegalStateException khi bộ chạy không phản hồi hoặc trả sai số lượng
   */
  List<Result> evaluate(List<Evaluation> evaluations);
}
