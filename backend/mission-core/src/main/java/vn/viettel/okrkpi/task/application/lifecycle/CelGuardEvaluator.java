package vn.viettel.okrkpi.task.application.lifecycle;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import vn.viettel.okrkpi.cel.application.CelCompiledProgram;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.CelExecutionException;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.task.domain.lifecycle.GuardEvaluator;

/**
 * Guard CEL của bảng chuyển trên {@code vt-cel}: bốn biến {@code actor}, {@code task}, {@code
 * deliverables}, {@code change_request} như khối {@code ngu_canh} của {@code task_lifecycle}; biểu
 * thức biên dịch một lần và được {@link CelEngine} lưu đệm.
 */
public class CelGuardEvaluator implements GuardEvaluator {

  /** Kiểu biến ngữ cảnh guard (TL-01 {@code task.activation(actor, now)}). */
  public static final Map<String, CelValueType> VARIABLES =
      Map.of(
          "actor", CelValueType.MAP_STRING_DYN,
          "task", CelValueType.MAP_STRING_DYN,
          "deliverables", CelValueType.LIST_DYN,
          "change_request", CelValueType.MAP_STRING_DYN);

  private final CelEngine engine;
  private final Map<String, CelCompiledProgram> programs = new ConcurrentHashMap<>();

  public CelGuardEvaluator(CelEngine engine) {
    this.engine = Objects.requireNonNull(engine, "engine");
  }

  /** Biên dịch trước để lỗi cú pháp lộ ra khi nạp bảng, không phải lúc người dùng bấm nút. */
  public CelCompiledProgram compile(String expression) {
    return programs.computeIfAbsent(
        expression, e -> engine.compile(e, VARIABLES, CelValueType.BOOL));
  }

  @Override
  public boolean test(String celExpression, Map<String, Object> activation) {
    try {
      return Boolean.TRUE.equals(engine.evaluate(compile(celExpression), activation));
    } catch (CelExecutionException e) {
      // Thiếu dữ liệu hoặc lỗi lúc chạy: coi như chưa đáp ứng điều kiện, không cho chuyển.
      return false;
    }
  }
}
