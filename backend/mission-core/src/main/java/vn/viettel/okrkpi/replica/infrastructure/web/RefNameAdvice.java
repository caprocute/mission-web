package vn.viettel.okrkpi.replica.infrastructure.web;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Điền tên hiển thị cho mọi {@code Ref} {@code {id, name}} của hợp đồng mà controller để tên rỗng
 * (QA-03/04: cây mục tiêu, lịch sử, hồ sơ… trả {@code name = ""}). Các module chỉ biết mã người, mã
 * đơn vị; tên nằm ở {@code org_replica} và được tra một lần cho cả thân trả về qua {@link
 * AccessDirectory} (họ tên trước, tên đơn vị phiên bản mới nhất sau, vì mã UUID v7 không trùng giữa
 * người và đơn vị). Chỉ duyệt lớp mô hình sinh từ hợp đồng {@code vn.viettel.okrkpi.contract}; tên
 * đã có giữ nguyên, {@code Ref} để {@code null} (ẩn danh) không bị đụng tới. Lỗi tra tên không làm
 * hỏng phản hồi: tên giữ rỗng như trước và ghi nhật ký.
 */
@ControllerAdvice
public class RefNameAdvice implements ResponseBodyAdvice<Object> {

  private static final Logger log = LoggerFactory.getLogger(RefNameAdvice.class);
  private static final String CONTRACT = "vn.viettel.okrkpi.contract.";

  private final ObjectProvider<AccessDirectory> directory;

  private final ClassValue<Shape> shapes =
      new ClassValue<>() {
        @Override
        protected Shape computeValue(Class<?> type) {
          return Shape.of(type);
        }
      };

  public RefNameAdvice(ObjectProvider<AccessDirectory> directory) {
    this.directory = directory;
  }

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (body == null) {
      return null;
    }
    Optional<TenantId> tenant = TenantContext.current();
    AccessDirectory dir = directory.getIfAvailable();
    if (tenant.isEmpty() || dir == null) {
      return body;
    }
    try {
      fill(tenant.get(), dir, body);
    } catch (RuntimeException e) {
      log.warn(
          "Không tra được tên hiển thị cho {}: {}", body.getClass().getSimpleName(), e.toString());
    }
    return body;
  }

  /** Gom các {@code Ref} tên rỗng trong {@code body}, tra tên một lượt rồi điền vào. */
  void fill(TenantId tenant, AccessDirectory dir, Object body) {
    List<Object> refs = new ArrayList<>();
    collect(body, refs, Collections.newSetFromMap(new IdentityHashMap<>()));
    if (refs.isEmpty()) {
      return;
    }
    Set<UUID> ids = new LinkedHashSet<>();
    for (Object ref : refs) {
      ids.add(shapes.get(ref.getClass()).id(ref));
    }
    Map<UUID, String> people = dir.personNames(tenant, ids);
    Set<UUID> rest = new LinkedHashSet<>(ids);
    rest.removeAll(people.keySet());
    Map<UUID, String> units = rest.isEmpty() ? Map.of() : dir.unitNames(tenant, rest);
    for (Object ref : refs) {
      Shape shape = shapes.get(ref.getClass());
      UUID id = shape.id(ref);
      String name = people.containsKey(id) ? people.get(id) : units.get(id);
      if (name != null && !name.isEmpty()) {
        shape.name(ref, name);
      }
    }
  }

  private void collect(Object node, List<Object> refs, Set<Object> seen) {
    if (node == null || !seen.add(node)) {
      return;
    }
    if (node instanceof Collection<?> c) {
      c.forEach(e -> collect(e, refs, seen));
      return;
    }
    if (node instanceof Map<?, ?> m) {
      m.values().forEach(e -> collect(e, refs, seen));
      return;
    }
    if (node instanceof Optional<?> o) {
      o.ifPresent(e -> collect(e, refs, seen));
      return;
    }
    Shape shape = shapes.get(node.getClass());
    if (!shape.contract()) {
      return;
    }
    if (shape.isRef() && shape.id(node) != null && blank(shape.currentName(node))) {
      refs.add(node);
    }
    for (Method child : shape.children()) {
      collect(Shape.invoke(child, node), refs, seen);
    }
  }

  private static boolean blank(String name) {
    return name == null || name.isEmpty();
  }

  /**
   * Siêu dữ liệu một lớp mô hình: có phải {@code Ref} không và các getter có thể chứa {@code Ref}.
   */
  private record Shape(
      boolean contract, Method getId, Method getName, Method setName, List<Method> children) {

    static Shape of(Class<?> type) {
      if (!type.getName().startsWith(CONTRACT) || type.isEnum()) {
        return new Shape(false, null, null, null, List.of());
      }
      Method getId = method(type, "getId");
      Method getName = method(type, "getName");
      Method setName = method(type, "setName", String.class);
      boolean ref =
          getId != null
              && getId.getReturnType() == UUID.class
              && getName != null
              && getName.getReturnType() == String.class
              && setName != null;
      List<Method> children = new ArrayList<>();
      for (Method m : type.getMethods()) {
        if (m.getParameterCount() == 0
            && !Modifier.isStatic(m.getModifiers())
            && m.getName().startsWith("get")
            && !m.getName().equals("getClass")
            && mayHoldRef(m.getReturnType())) {
          children.add(m);
        }
      }
      return new Shape(
          true, ref ? getId : null, getName, ref ? setName : null, List.copyOf(children));
    }

    boolean isRef() {
      return getId != null;
    }

    UUID id(Object ref) {
      return (UUID) invoke(getId, ref);
    }

    String currentName(Object ref) {
      return (String) invoke(getName, ref);
    }

    void name(Object ref, String name) {
      try {
        setName.invoke(ref, name);
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new IllegalStateException(e);
      }
    }

    private static boolean mayHoldRef(Class<?> type) {
      return !(type.isPrimitive()
          || type.isEnum()
          || type == String.class
          || type == UUID.class
          || Number.class.isAssignableFrom(type)
          || Boolean.class == type
          || Temporal.class.isAssignableFrom(type)
          || type.getName().startsWith("java.net."));
    }

    private static Method method(Class<?> type, String name, Class<?>... params) {
      try {
        return type.getMethod(name, params);
      } catch (NoSuchMethodException e) {
        return null;
      }
    }

    static Object invoke(Method m, Object target) {
      try {
        return m.invoke(target);
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
