package vn.viettel.okrkpi.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Hiện thực {@link Idempotent}: bao phương thức controller trong một giao dịch, gọi {@link
 * IdempotencyStore#begin}, chạy phương thức khi khoá mới, rồi {@link IdempotencyStore#complete} với
 * mã trạng thái, thân (nếu được lưu) và {@code Location} trong cùng giao dịch.
 *
 * <p>Phụ thuộc lấy lười qua {@link Supplier} để advisor không kéo theo việc khởi tạo sớm các bean
 * JDBC.
 */
public class IdempotencyInterceptor implements MethodInterceptor {

  /** Tên header của hợp đồng. */
  public static final String HEADER = "Idempotency-Key";

  /** Số giây gợi ý chờ khi cùng khoá đang được xử lý. */
  static final long RETRY_AFTER_SECONDS = 1;

  private static final String ROOT_PACKAGE = "vn.viettel.okrkpi.";
  private static final String SHARED = "shared";
  private static final int MAX_REF = 128;

  private final Supplier<IdempotencyStore> store;
  private final Supplier<TransactionTemplate> transactions;
  private final Supplier<ObjectMapper> mapper;
  private final Supplier<IdempotencyCallerResolver> callers;
  private final String defaultSchema;
  private final Supplier<IdempotencyTargets> targets;

  public IdempotencyInterceptor(
      Supplier<IdempotencyStore> store,
      Supplier<TransactionTemplate> transactions,
      Supplier<ObjectMapper> mapper,
      Supplier<IdempotencyCallerResolver> callers,
      String defaultSchema) {
    this(store, transactions, mapper, callers, defaultSchema, () -> null);
  }

  /**
   * @param targets đích theo schema ({@link IdempotencyTargets}); trả {@code null} khi service chỉ
   *     có một DataSource
   */
  public IdempotencyInterceptor(
      Supplier<IdempotencyStore> store,
      Supplier<TransactionTemplate> transactions,
      Supplier<ObjectMapper> mapper,
      Supplier<IdempotencyCallerResolver> callers,
      String defaultSchema,
      Supplier<IdempotencyTargets> targets) {
    this.targets = Objects.requireNonNull(targets, "targets");
    this.store = Objects.requireNonNull(store, "store");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.defaultSchema =
        defaultSchema == null || defaultSchema.isBlank() ? null : defaultSchema.trim();
  }

  @Override
  public Object invoke(MethodInvocation invocation) throws Throwable {
    Method method = invocation.getMethod();
    Class<?> target =
        invocation.getThis() == null
            ? method.getDeclaringClass()
            : AopUtils.getTargetClass(invocation.getThis());
    Method specific = AopUtils.getMostSpecificMethod(method, target);
    Idempotent annotation = AnnotatedElementUtils.findMergedAnnotation(specific, Idempotent.class);
    if (annotation == null) {
      return invocation.proceed();
    }
    HttpServletRequest request = currentRequest();
    String key = request.getHeader(HEADER);
    if (key == null || key.isEmpty()) {
      if (annotation.required()) {
        throw new IdempotencyExceptions.KeyRequired(false);
      }
      return invocation.proceed();
    }
    if (!JdbcIdempotencyStore.isValidKey(key)) {
      throw new IdempotencyExceptions.KeyRequired(true);
    }
    IdempotencyCallerResolver.Caller caller =
        callers
            .get()
            .resolve(request)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Không xác định được tenant và người gọi cho Idempotency-Key"));
    IdempotencyScope scope =
        new IdempotencyScope(schema(annotation, target), caller.tenant(), caller.actor());
    byte[] hash =
        new RequestHasher(mapper.get())
            .hash(request.getMethod(), pathAndQuery(request), body(specific, invocation));

    IdempotencyTargets.Target route = route(scope.schema());
    IdempotencyStore keys = route == null ? store.get() : route.store();
    TransactionTemplate transaction =
        route == null ? transactions.get() : template(route.transactionManager());
    Holder holder = new Holder();
    try {
      return transaction.execute(
          tx -> {
            BeginResult begin = keys.begin(scope, key, hash);
            return switch (begin) {
              case BeginResult.Replay replay -> replay(replay.record(), specific, target);
              case BeginResult.Mismatch m -> throw new IdempotencyExceptions.Mismatch();
              case BeginResult.InProgress p ->
                  throw new IdempotencyExceptions.InProgress(RETRY_AFTER_SECONDS);
              case BeginResult.Started started ->
                  proceed(invocation, keys, started, annotation, specific, holder);
            };
          });
    } catch (CheckedFailure e) {
      throw holder.failure;
    }
  }

  private IdempotencyTargets.Target route(String schema) {
    IdempotencyTargets resolver = targets.get();
    return resolver == null ? null : resolver.forSchema(schema).orElse(null);
  }

  private static TransactionTemplate template(PlatformTransactionManager manager) {
    TransactionTemplate tx = new TransactionTemplate(manager);
    tx.setTimeout(IdempotencyAutoConfiguration.TX_TIMEOUT_SECONDS);
    return tx;
  }

  private Object proceed(
      MethodInvocation invocation,
      IdempotencyStore keys,
      BeginResult.Started started,
      Idempotent annotation,
      Method method,
      Holder holder) {
    Object result;
    try {
      result = invocation.proceed();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      // Ngoại lệ checked: huỷ giao dịch bằng một RuntimeException rồi ném lại nguyên bản.
      holder.failure = t;
      throw new CheckedFailure();
    }
    int status = status(result, method);
    if (status < 200 || status > 299) {
      keys.abandon(started);
      return result;
    }
    keys.complete(started, status, body(result, annotation), location(result));
    return result;
  }

  private String body(Object result, Idempotent annotation) {
    Object body = result instanceof ResponseEntity<?> e ? e.getBody() : result;
    if (body == null
        || !annotation.storeBody()
        || SensitivePayloads.containsSensitive(body.getClass())) {
      return null;
    }
    try {
      return mapper.get().writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không tuần tự hoá được phản hồi để lưu khoá", e);
    }
  }

  private static String location(Object result) {
    if (result instanceof ResponseEntity<?> e) {
      String location = e.getHeaders().getFirst(HttpHeaders.LOCATION);
      if (location != null && location.length() <= MAX_REF) {
        return location;
      }
    }
    return null;
  }

  private static int status(Object result, Method method) {
    if (result instanceof ResponseEntity<?> e) {
      return e.getStatusCode().value();
    }
    ResponseStatus rs = AnnotatedElementUtils.findMergedAnnotation(method, ResponseStatus.class);
    return rs == null ? 200 : rs.code().value();
  }

  /** Dựng lại giá trị trả về từ phản hồi đã lưu. */
  private Object replay(IdempotencyRecord record, Method method, Class<?> target) {
    boolean entity = ResponseEntity.class.isAssignableFrom(method.getReturnType());
    ResolvableType returnType = ResolvableType.forMethodReturnType(method, target);
    Type bodyType =
        entity ? returnType.as(ResponseEntity.class).getGeneric(0).getType() : returnType.getType();
    Object body = null;
    if (record.responseBody() != null && method.getReturnType() != void.class) {
      body = readBody(record.responseBody(), bodyType);
    }
    if (!entity) {
      return body;
    }
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(record.responseCode() == null ? 200 : record.responseCode());
    if (record.resourceRef() != null) {
      builder.header(HttpHeaders.LOCATION, record.resourceRef());
    }
    return builder.body(body);
  }

  private Object readBody(String json, Type type) {
    ObjectMapper om = mapper.get();
    try {
      JavaType javaType = om.getTypeFactory().constructType(type);
      if (javaType.isJavaLangObject() || javaType.hasRawClass(Void.class)) {
        return om.readTree(json);
      }
      return om.readValue(json, javaType);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không đọc lại được phản hồi đã lưu", e);
    }
  }

  private String schema(Idempotent annotation, Class<?> target) {
    if (!annotation.schema().isBlank()) {
      return annotation.schema().trim();
    }
    String fromPackage = schemaFromPackage(target.getPackageName());
    if (fromPackage != null) {
      return fromPackage;
    }
    if (defaultSchema != null) {
      return defaultSchema;
    }
    throw new IllegalStateException(
        "Không xác định được schema idempotency_key: đặt @Idempotent(schema) hoặc"
            + " okrkpi.idempotency.schema");
  }

  /** {@code vn.viettel.okrkpi.<module>..} → {@code <module>} (TL-01 cấu trúc gói theo module). */
  static String schemaFromPackage(String packageName) {
    if (packageName == null || !packageName.startsWith(ROOT_PACKAGE)) {
      return null;
    }
    String rest = packageName.substring(ROOT_PACKAGE.length());
    int dot = rest.indexOf('.');
    String segment = (dot < 0 ? rest : rest.substring(0, dot)).toLowerCase(Locale.ROOT);
    if (segment.isEmpty() || SHARED.equals(segment)) {
      return null;
    }
    return IdempotencyScope.SCHEMA.matcher(segment).matches() ? segment : null;
  }

  private static Object body(Method method, MethodInvocation invocation) {
    Annotation[][] annotations = method.getParameterAnnotations();
    Object[] args = invocation.getArguments();
    for (int i = 0; i < annotations.length && i < args.length; i++) {
      for (Annotation a : annotations[i]) {
        if (a instanceof RequestBody) {
          return args[i];
        }
      }
    }
    return null;
  }

  private static String pathAndQuery(HttpServletRequest request) {
    String query = request.getQueryString();
    String uri = request.getRequestURI();
    return query == null ? uri : uri + '?' + query;
  }

  private static HttpServletRequest currentRequest() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes sra) {
      return sra.getRequest();
    }
    throw new IllegalStateException("@Idempotent chỉ dùng cho phương thức xử lý yêu cầu HTTP");
  }

  private static final class Holder {
    private Throwable failure;
  }

  /** Báo cho TransactionTemplate huỷ giao dịch khi phương thức ném ngoại lệ checked. */
  private static final class CheckedFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CheckedFailure() {
      super(null, null, false, false);
    }
  }
}
