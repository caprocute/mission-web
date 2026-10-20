package vn.viettel.okrkpi.shared.idempotency;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import vn.viettel.okrkpi.shared.kernel.Classified;

/**
 * Phát hiện kiểu thân phản hồi có thuộc tính {@code @Classified} mức C2 trở lên (kể cả lồng trong
 * thuộc tính, phần tử danh sách, giá trị map), để kho idempotency không lưu thân đó (TL-03: {@code
 * response_body} không chứa trường C2, chỉ lưu {@code resource_ref}).
 *
 * <p>Chỉ duyệt kiểu của nền tảng ({@code vn.viettel.okrkpi..}); kiểu động ({@code Map}, {@code
 * JsonNode}) không có đánh dấu nên được coi là không nhạy cảm, controller trả dữ liệu C2 qua kiểu
 * động phải đặt {@link Idempotent#storeBody()} = {@code false}.
 */
final class SensitivePayloads {

  private static final String ROOT = "vn.viettel.okrkpi.";
  private static final ConcurrentMap<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

  private SensitivePayloads() {}

  static boolean containsSensitive(Class<?> type) {
    if (type == null) {
      return false;
    }
    Boolean cached = CACHE.get(type);
    if (cached != null) {
      return cached;
    }
    boolean result = scan(type, new HashSet<>());
    CACHE.putIfAbsent(type, result);
    return result;
  }

  private static boolean scan(Type type, Set<Class<?>> seen) {
    if (type instanceof ParameterizedType p) {
      if (scan(p.getRawType(), seen)) {
        return true;
      }
      for (Type arg : p.getActualTypeArguments()) {
        if (scan(arg, seen)) {
          return true;
        }
      }
      return false;
    }
    if (type instanceof GenericArrayType g) {
      return scan(g.getGenericComponentType(), seen);
    }
    if (type instanceof WildcardType w) {
      for (Type b : w.getUpperBounds()) {
        if (scan(b, seen)) {
          return true;
        }
      }
      return false;
    }
    if (!(type instanceof Class<?> c)) {
      return false;
    }
    if (c.isArray()) {
      return scan(c.getComponentType(), seen);
    }
    if (c.isPrimitive() || !c.getName().startsWith(ROOT) || !seen.add(c)) {
      return false;
    }
    if (sensitive(c.getAnnotation(Classified.class))) {
      return true;
    }
    if (c.isRecord()) {
      for (RecordComponent rc : c.getRecordComponents()) {
        if (sensitive(rc.getAnnotation(Classified.class)) || scan(rc.getGenericType(), seen)) {
          return true;
        }
      }
    }
    for (Class<?> k = c; k != null && k.getName().startsWith(ROOT); k = k.getSuperclass()) {
      for (Field f : k.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers())) {
          continue;
        }
        if (sensitive(f.getAnnotation(Classified.class)) || scan(f.getGenericType(), seen)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean sensitive(Classified c) {
    return c != null && c.value().isSensitive();
  }
}
