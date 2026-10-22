package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.WorkflowBindings;
import vn.viettel.okrkpi.policy.application.port.WorkflowBindings.Versioned;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lớp công bố cấu hình luồng duyệt của MOD-05 (CORE-05-11, QT-15, SCR-1504, BR-15-11, BR-15-12):
 * kiểm phiên bản Policy Pack thuộc tenant, chỉ cho lưu khi phiên bản còn {@code NHAP} (từ {@code
 * DAT_KIEM_THU} trở đi là {@code POLICY-409-VERSION-FROZEN}) và kỳ bắt đầu chưa khoá ({@code
 * POLICY-409-PERIOD-LOCKED}), rồi đọc, lưu, mô phỏng qua {@link WorkflowBindings} của SVC-06.
 *
 * <p>Tham số đã lưu được chép vào {@code manifest.workflow_bindings.<processKind>} trong cùng giao
 * dịch, nên {@code content_hash} của phiên bản (CORE-05-09) phủ cả tham số luồng.
 */
public class WorkflowBindingService {

  static final String TX = PolicyPackService.TX;
  static final String OBJECT_TYPE = "wf_binding";

  /** Phiên bản gói cùng các bản ghi {@code wf_binding} của nó. */
  public record VersionBindings(
      PolicyPack pack, PolicyPackVersion version, List<Map<String, Object>> items) {}

  /** Nội dung {@code WorkflowBindingUpdate}; {@code fromPeriodId} tách riêng để kiểm khoá kỳ. */
  public record BindingEdit(
      String templateKey,
      Integer templateVersionNo,
      UUID fromPeriodId,
      Map<String, Object> scope,
      Map<String, Object> params) {}

  private final PolicyPackRepository packs;
  private final WorkflowBindings bindings;
  private final Supplier<PeriodApi> periods;
  private final PolicyAudit audit;
  private final ObjectMapper json;

  public WorkflowBindingService(
      PolicyPackRepository packs,
      WorkflowBindings bindings,
      Supplier<PeriodApi> periods,
      PolicyAudit audit,
      ObjectMapper json) {
    this.packs = Objects.requireNonNull(packs, "packs");
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
  }

  /**
   * {@code listWorkflowBindings}; gói hoặc phiên bản không có ném {@link NoSuchElementException}.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public VersionBindings list(TenantId tenant, UUID packId, String version) {
    PolicyPack pack = packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    PolicyPackVersion v = version(tenant, packId, version);
    return new VersionBindings(pack, v, bindings.list(tenant, v.id()));
  }

  /** {@code getWorkflowBinding}. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<Versioned> find(
      TenantId tenant, UUID packId, String version, String processKind) {
    PolicyPackVersion v = version(tenant, packId, version);
    return bindings.find(tenant, v.id(), processKind);
  }

  /**
   * {@code saveWorkflowBinding}. SVC-06 lưu trước rồi mới chép vào manifest; nếu phần chép lỗi thì
   * giao dịch của MOD-05 (gồm khoá Idempotency) quay lui và lần gửi lại cùng khoá được SVC-06 phát
   * lại phản hồi đã lưu.
   */
  @Transactional(TX)
  public Versioned save(
      TenantId tenant,
      UUID actor,
      UUID packId,
      String version,
      String processKind,
      long expectedRowVersion,
      String idempotencyKey,
      BindingEdit edit) {
    Objects.requireNonNull(edit, "edit");
    PolicyPack pack = packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    PolicyPackVersion v = version(tenant, packId, version);
    if (v.status() != PolicyVersionStatus.NHAP) {
      throw frozen(version);
    }
    if (edit.templateKey() == null || edit.templateKey().isBlank()) {
      throw invalid("templateKey", "Cần chọn mẫu luồng");
    }
    if (edit.templateVersionNo() == null || edit.templateVersionNo() < 1) {
      throw invalid("templateVersionNo", "Cần chọn phiên bản mẫu luồng");
    }
    if (edit.params() == null) {
      throw invalid("params", "Cần nhập tham số luồng duyệt");
    }
    checkPeriod(tenant, edit.fromPeriodId());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("processKind", processKind);
    body.put("templateKey", edit.templateKey());
    body.put("templateVersionNo", edit.templateVersionNo());
    body.put("scope", edit.scope());
    body.put("params", edit.params());
    Versioned saved =
        bindings.save(
            tenant,
            v.id(),
            processKind,
            expectedRowVersion,
            "policy:" + v.id() + ":" + processKind + ":" + idempotencyKey,
            body);

    Map<String, Object> copy = new LinkedHashMap<>(body);
    copy.remove("processKind");
    if (!packs.storeWorkflowBinding(tenant, v.id(), processKind, write(copy), actor)) {
      throw frozen(version);
    }
    audit.changed(
        tenant,
        actor,
        "LUU_THAM_SO_LUONG_DUYET",
        OBJECT_TYPE,
        pack.code() + "@" + v.version() + "/" + processKind,
        idOf(saved, v.id()),
        saved.rowVersion());
    return saved;
  }

  /** {@code simulateWorkflowBinding}: chuyển tiếp, không ghi gì ở MOD-05 hay SVC-06. */
  public Map<String, Object> simulate(
      TenantId tenant, Map<String, Object> request, String idempotencyKey) {
    return bindings.simulate(tenant, request, "policy:mo-phong:" + idempotencyKey);
  }

  /** {@code listPolicyWorkflowTemplates}. */
  public Map<String, Object> templates(
      TenantId tenant, String processKind, String q, int limit, String cursor) {
    return bindings.templates(tenant, processKind, q, limit, cursor);
  }

  /** {@code getPolicyWorkflowTemplateVersion}. */
  public Optional<Versioned> template(TenantId tenant, String templateKey, int versionNo) {
    return bindings.template(tenant, templateKey, versionNo);
  }

  private PolicyPackVersion version(TenantId tenant, UUID packId, String version) {
    return packs.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
  }

  /** Kỳ bắt đầu áp dụng phải có trong tenant và chưa khoá. */
  private void checkPeriod(TenantId tenant, UUID fromPeriodId) {
    if (fromPeriodId == null) {
      throw invalid("scope.fromPeriodId", "Cần chọn kỳ bắt đầu áp dụng");
    }
    PeriodApi api = periods.get();
    if (api == null) {
      return;
    }
    PeriodId period = PeriodId.of(fromPeriodId);
    if (api.findById(tenant, period).isEmpty()) {
      throw invalid("scope.fromPeriodId", "Kỳ bắt đầu áp dụng không có");
    }
    try {
      api.assertWritable(tenant, period);
    } catch (PeriodLockedException e) {
      throw new BusinessException(
          "POLICY-409-PERIOD-LOCKED",
          "Kỳ bắt đầu áp dụng đã khoá, không lưu được tham số luồng duyệt",
          List.of(new ProblemFieldError("scope.fromPeriodId", "Kỳ bắt đầu áp dụng đã khoá", null)));
    }
  }

  private static UUID idOf(Versioned saved, UUID fallback) {
    Object id = saved.body().get("id");
    try {
      return id == null ? fallback : UUID.fromString(id.toString());
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }

  private String write(Map<String, Object> value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static BusinessException frozen(String version) {
    return new BusinessException(
        "POLICY-409-VERSION-FROZEN",
        "Phiên bản "
            + version
            + " đã đạt kiểm thử, đang chờ phê duyệt hoặc đã phát hành nên không sửa được tham số"
            + " luồng duyệt");
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
