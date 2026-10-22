package vn.viettel.okrkpi.workflow.application.saga;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import vn.viettel.okrkpi.workflow.domain.saga.SagaDefinition;
import vn.viettel.okrkpi.workflow.domain.saga.SagaStepDefinition;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/**
 * Danh mục loại saga đã đăng ký cùng bộ xử lý của từng bước. Kiểm lúc khởi động: mỗi bước của mỗi
 * định nghĩa phải có đúng một bộ xử lý, để lỗi khai báo lộ ra ngay chứ không phải giữa lúc chạy.
 */
public final class SagaCatalog {

  private final Map<SagaType, SagaBlueprint> blueprints = new EnumMap<>(SagaType.class);
  private final Map<String, SagaStepHandler> handlers = new HashMap<>();

  public SagaCatalog(List<SagaBlueprint> blueprints, List<SagaStepHandler> handlers) {
    for (SagaStepHandler handler : handlers) {
      String key = key(handler.sagaType(), handler.stepCode());
      if (this.handlers.putIfAbsent(key, handler) != null) {
        throw new IllegalStateException("Trùng bộ xử lý bước " + key);
      }
    }
    for (SagaBlueprint blueprint : blueprints) {
      SagaDefinition definition = blueprint.definition();
      if (this.blueprints.putIfAbsent(definition.type(), blueprint) != null) {
        throw new IllegalStateException("Trùng định nghĩa saga " + definition.type());
      }
      for (SagaStepDefinition step : definition.steps()) {
        if (!this.handlers.containsKey(key(definition.type(), step.stepCode()))) {
          throw new IllegalStateException(
              "Saga " + definition.type() + " thiếu bộ xử lý cho bước " + step.stepCode());
        }
      }
    }
  }

  public Optional<SagaBlueprint> blueprint(SagaType type) {
    return Optional.ofNullable(blueprints.get(type));
  }

  public SagaDefinition definition(SagaType type) {
    SagaBlueprint blueprint = blueprints.get(type);
    if (blueprint == null) {
      throw new IllegalStateException("Chưa đăng ký saga " + type);
    }
    return blueprint.definition();
  }

  public SagaStepHandler handler(SagaType type, String stepCode) {
    SagaStepHandler handler = handlers.get(key(type, stepCode));
    if (handler == null) {
      throw new IllegalStateException("Thiếu bộ xử lý bước " + key(type, stepCode));
    }
    return handler;
  }

  private static String key(SagaType type, String stepCode) {
    return type + "/" + stepCode;
  }
}
