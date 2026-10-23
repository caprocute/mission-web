package vn.viettel.okrkpi.workflow.application.binding;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelCompilationException;
import vn.viettel.okrkpi.cel.domain.CelIssueDetail;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.workflow.domain.binding.SkipConditionChecker;

/**
 * Điều kiện bỏ qua cấp kiểm kiểu tĩnh bằng cel-java của thư viện {@code vt-cel} (ADR-10): biểu thức
 * phải trả về {@code bool} trên biến của hồ sơ (tên theo ví dụ SCR-1504; danh sách biến là suy
 * luận, wiki trang 14).
 */
@Component
public class CelSkipConditions implements SkipConditionChecker {

  /** Biến được dùng trong điều kiện bỏ qua cấp. */
  public static final Map<String, CelValueType> VARIABLES =
      Map.of(
          "nguoi_duoc_danh_gia", CelValueType.MAP_STRING_DYN,
          "muc_tham_dinh", CelValueType.STRING,
          "muc_tu_de_xuat", CelValueType.STRING,
          "la_nguoi_dung_dau", CelValueType.BOOL,
          "khoi", CelValueType.STRING);

  private final CelEngine engine = new CelEngine();

  @Override
  public Optional<String> problem(String expression) {
    try {
      engine.compile(expression, VARIABLES, CelValueType.BOOL);
      return Optional.empty();
    } catch (CelCompilationException e) {
      return Optional.of(
          e.issues().stream()
              .findFirst()
              .map(CelIssueDetail::message)
              .orElse("biểu thức phải trả về đúng hoặc sai"));
    }
  }

  @Override
  public boolean evaluate(String expression, Map<String, Object> variables) {
    return engine.evaluateBoolean(expression, VARIABLES, variables);
  }
}
