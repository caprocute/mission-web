package vn.viettel.okrkpi.shared.authz;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ErrorCatalog;
import vn.viettel.okrkpi.shared.error.ErrorCode;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bộ chặn AOP thực thi {@link RequiresPermission}: kiểm step-up AAL2, dựng {@link OpaInput}, gọi
 * {@link OpaClient#decide(OpaInput)}, ánh xạ lý do từ chối sang mã lỗi hợp đồng trong {@link
 * ErrorCatalog} và ghi nhật ký kiểm toán khi từ chối hoặc khi có cờ nghĩa vụ (NEN-014).
 */
public final class PermissionInterceptor implements MethodInterceptor {

  private static final Map<String, String> MODULE_SOD_CODES =
      Map.ofEntries(
          Map.entry("TASK", "TASK-403-SAME-AUTHOR"),
          Map.entry("PLAN", "TASK-403-SAME-AUTHOR"),
          Map.entry("OKR", "OKR-403-SELF-APPROVAL"),
          Map.entry("PRODUCT", "PRODUCT-403-SELF-REVIEW"),
          Map.entry("APPRAISAL", "APPRAISAL-403-SEPARATION-OF-DUTIES"),
          Map.entry("OVERSIGHT", "OVERSIGHT-403-SEPARATION-OF-DUTIES"),
          Map.entry("CONFIG", "CONFIG-403-SELF-GRANT"),
          Map.entry("POLICY", "POLICY-403-SAME-AUTHOR"),
          Map.entry("WORKFLOW", "WORKFLOW-403-SELF-APPROVAL"),
          Map.entry("PERIOD", "PERIOD-403-SAME-PERSON"),
          Map.entry("UI", "UI-403-SAME-PERSON"),
          Map.entry("REGISTRY", "REGISTRY-403-SAME-APPROVER"));

  private final OpaClient opaClient;
  private final OperationPolicyCatalog policyCatalog;
  private final StepUpValidator stepUpValidator;
  private final AuthzSubjectResolver subjectResolver;
  private final ErrorCatalog errorCatalog;
  private final AuditLogger auditLogger;
  private final Clock clock;

  public PermissionInterceptor(
      OpaClient opaClient,
      OperationPolicyCatalog policyCatalog,
      StepUpValidator stepUpValidator,
      AuthzSubjectResolver subjectResolver,
      ErrorCatalog errorCatalog,
      AuditLogger auditLogger) {
    this(
        opaClient,
        policyCatalog,
        stepUpValidator,
        subjectResolver,
        errorCatalog,
        auditLogger,
        Clock.systemUTC());
  }

  public PermissionInterceptor(
      OpaClient opaClient,
      OperationPolicyCatalog policyCatalog,
      StepUpValidator stepUpValidator,
      AuthzSubjectResolver subjectResolver,
      ErrorCatalog errorCatalog,
      AuditLogger auditLogger,
      Clock clock) {
    this.opaClient = Objects.requireNonNull(opaClient, "opaClient must not be null");
    this.policyCatalog = Objects.requireNonNull(policyCatalog, "policyCatalog must not be null");
    this.stepUpValidator =
        Objects.requireNonNull(stepUpValidator, "stepUpValidator must not be null");
    this.subjectResolver =
        Objects.requireNonNull(subjectResolver, "subjectResolver must not be null");
    this.errorCatalog = Objects.requireNonNull(errorCatalog, "errorCatalog must not be null");
    this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Object invoke(MethodInvocation invocation) throws Throwable {
    RequiresPermission ann = findAnnotation(invocation);
    if (ann == null) {
      return invocation.proceed();
    }

    Optional<OperationPolicy> opPolicy = policyCatalog.find(ann.operationId());
    OpaInput.Subject subject = subjectResolver.currentSubject();

    boolean requiresStepUp =
        ann.stepUp() || opPolicy.map(OperationPolicy::requiresStepUp).orElse(false);
    if (requiresStepUp) {
      stepUpValidator.verifyStepUp(
          subject, ann.operationId().isBlank() ? ann.action() : ann.operationId());
    }

    String action = ann.action().isBlank() ? null : ann.action();
    String apiModule =
        !ann.module().isBlank() ? ann.module() : opPolicy.map(OperationPolicy::module).orElse(null);
    String apiMethod =
        !ann.method().isBlank()
            ? ann.method().toUpperCase(Locale.ROOT)
            : opPolicy.map(OperationPolicy::method).orElse(null);
    OpaInput.Request request =
        (apiModule != null && !apiModule.isBlank() && apiMethod != null && !apiMethod.isBlank())
            ? new OpaInput.Request(
                apiModule, apiMethod, ann.operationId().isBlank() ? null : ann.operationId())
            : null;

    AuthzResource authzResource = extractResource(invocation.getArguments(), ann, subject);
    OpaInput.Resource resource =
        authzResource.toOpaResource(subject.tenantId(), ann.classification());
    OpaInput.Context context = new OpaInput.Context(clock.instant().getEpochSecond());

    OpaInput opaInput = new OpaInput(subject, action, request, resource, context);
    OpaDecision decision = opaClient.decide(opaInput);

    if (!decision.isPermitted()) {
      handleDenied(ann, opPolicy.orElse(null), subject, resource, decision);
    }

    emitObligationAuditIfNeeded(ann, opPolicy.orElse(null), subject, resource, decision);
    return AuthzDecisionContext.callWith(decision, invocation::proceed);
  }

  private void handleDenied(
      RequiresPermission ann,
      OperationPolicy opPolicy,
      OpaInput.Subject subject,
      OpaInput.Resource resource,
      OpaDecision decision) {
    Set<String> deny = decision.deny();
    String opName = !ann.operationId().isBlank() ? ann.operationId() : ann.action();

    if (deny.contains("step_up_required")) {
      stepUpValidator.verifyStepUp(subject, opName);
      throw new StepUpRequiredException(
          opName, 300L, "Thao tác yêu cầu xác thực bước lên AAL2 còn hiệu lực.");
    }

    ErrorCode errorCode = resolveErrorCode(ann, opPolicy, deny);
    AuditEvent.Builder eventBuilder =
        AuditEvent.builder(
                AuditEventType.ADMIN_ACTION,
                resolveActorId(subject),
                (opName == null || opName.isBlank() ? "AUTHZ" : opName) + ":DENIED")
            .outcome(AuditOutcome.DENIED)
            .object(resource.type() != null ? resource.type() : "resource", resource.id())
            .attribute("deny", String.join(",", deny))
            .attribute("code", errorCode.code())
            .attribute("bundleRevision", decision.bundleRevision());
    resolveTenantId(subject).ifPresent(eventBuilder::tenant);
    auditLogger.record(eventBuilder.build());
    throw new BusinessException(errorCode.code());
  }

  private void emitObligationAuditIfNeeded(
      RequiresPermission ann,
      OperationPolicy opPolicy,
      OpaInput.Subject subject,
      OpaInput.Resource resource,
      OpaDecision decision) {
    Set<String> flags = decision.flags();
    if (flags.isEmpty()) {
      return;
    }
    if (flags.contains("c2_access_log")
        || flags.contains("inferred_grant")
        || flags.contains("sod_small_unit_exception")
        || flags.contains("ledger_record")) {
      String opName =
          !ann.operationId().isBlank()
              ? ann.operationId()
              : (opPolicy != null
                  ? opPolicy.operationId()
                  : (ann.action().isBlank() ? "AUTHZ" : ann.action()));
      boolean c2Log = flags.contains("c2_access_log");
      AuditEventType eventType =
          c2Log ? AuditEventType.SENSITIVE_DATA_ACCESS : AuditEventType.ADMIN_ACTION;
      AuditEvent.Builder eventBuilder =
          AuditEvent.builder(eventType, resolveActorId(subject), opName + ":OBLIGATION")
              .outcome(AuditOutcome.SUCCESS)
              .object(resource.type() != null ? resource.type() : "resource", resource.id())
              .attribute("flags", String.join(",", flags))
              .attribute("bundleRevision", decision.bundleRevision());
      if (c2Log) {
        eventBuilder.classification(resolveClassification(resource.classification()));
      }
      resolveTenantId(subject).ifPresent(eventBuilder::tenant);
      auditLogger.record(eventBuilder.build());
    }
  }

  private static ActorId resolveActorId(OpaInput.Subject subject) {
    String raw =
        subject.userId() != null && !subject.userId().isBlank()
            ? subject.userId()
            : (subject.clientId() != null && !subject.clientId().isBlank()
                ? subject.clientId()
                : "anonymous");
    try {
      return ActorId.of(UUID.fromString(raw));
    } catch (IllegalArgumentException ignored) {
      return ActorId.of(UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)));
    }
  }

  private static Optional<TenantId> resolveTenantId(OpaInput.Subject subject) {
    if (subject.tenantId() == null || subject.tenantId().isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(TenantId.of(UUID.fromString(subject.tenantId())));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  private static Classification resolveClassification(String raw) {
    if ("C3".equalsIgnoreCase(raw)) {
      return Classification.C3;
    }
    return Classification.C2;
  }

  private ErrorCode resolveErrorCode(
      RequiresPermission ann, OperationPolicy opPolicy, Set<String> deny) {
    String errorModule = resolveErrorModule(ann, opPolicy);
    if (deny.contains("four_eyes_required")) {
      if ("CONFIG".equals(errorModule)) {
        return errorCatalog.require("CONFIG-403-SELF-GRANT");
      }
      return errorCatalog.require("OVERSIGHT-403-FOUR-EYES");
    }
    if (deny.contains("separation_of_duties")) {
      String code = MODULE_SOD_CODES.get(errorModule);
      if (code != null && errorCatalog.find(code).isPresent()) {
        return errorCatalog.require(code);
      }
      return errorCatalog.require("APPRAISAL-403-SEPARATION-OF-DUTIES");
    }
    return errorCatalog.require("COMMON-403-OUT-OF-SCOPE");
  }

  private static String resolveErrorModule(RequiresPermission ann, OperationPolicy opPolicy) {
    if (opPolicy != null && opPolicy.errorModule() != null) {
      return opPolicy.errorModule();
    }
    if (!ann.module().isBlank()) {
      String m = ann.module().toUpperCase(Locale.ROOT);
      if ("PLANS".equals(m)) {
        return "PLAN";
      }
      if (m.endsWith("S") && m.length() > 3 && !"OVERSIGHTS".equals(m)) {
        return m.substring(0, m.length() - 1);
      }
      return m;
    }
    if (!ann.resourceType().isBlank()) {
      return ann.resourceType().toUpperCase(Locale.ROOT);
    }
    if (ann.action().startsWith("SCR-02") || ann.action().startsWith("SCR-03")) {
      return "OKR";
    }
    if (ann.action().startsWith("SCR-04")) {
      return "PLAN";
    }
    if (ann.action().startsWith("SCR-05") || ann.action().startsWith("SCR-06")) {
      return "TASK";
    }
    if (ann.action().startsWith("SCR-07") || ann.action().startsWith("SCR-08")) {
      return "PRODUCT";
    }
    if (ann.action().startsWith("SCR-09")
        || ann.action().startsWith("SCR-10")
        || ann.action().startsWith("SCR-11")) {
      return "APPRAISAL";
    }
    if (ann.action().startsWith("SCR-12")) {
      return "DOSSIER";
    }
    if (ann.action().startsWith("SCR-14")) {
      return "OVERSIGHT";
    }
    if (ann.action().startsWith("SCR-01")) {
      return "CONFIG";
    }
    return "COMMON";
  }

  private static AuthzResource extractResource(
      Object[] args, RequiresPermission ann, OpaInput.Subject subject) {
    if (args != null) {
      for (Object arg : args) {
        if (arg instanceof AuthzResourceProvider provider) {
          AuthzResource provided = provider.toAuthzResource();
          if (provided != null) {
            return provided;
          }
        }
      }
    }
    return AuthzResource.builder()
        .type(ann.resourceType().isBlank() ? null : ann.resourceType())
        .ownerUnitId(subject.unitId())
        .classification(ann.classification())
        .build();
  }

  private static RequiresPermission findAnnotation(MethodInvocation invocation) {
    Method method = invocation.getMethod();
    RequiresPermission ann =
        AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
    if (ann != null) {
      return ann;
    }
    Class<?> targetClass =
        invocation.getThis() != null ? invocation.getThis().getClass() : method.getDeclaringClass();
    return AnnotatedElementUtils.findMergedAnnotation(targetClass, RequiresPermission.class);
  }
}
