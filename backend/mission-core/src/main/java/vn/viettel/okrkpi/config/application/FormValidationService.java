package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.cel.domain.WorkingCalendar;
import vn.viettel.okrkpi.config.api.FormValidationApi;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.FormSchemaRepository;
import vn.viettel.okrkpi.config.domain.FormSchema;
import vn.viettel.okrkpi.config.domain.FormSchemaStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểm dữ liệu biểu mẫu tại máy chủ và chạy bộ test hai engine (CORE-08-14, BR-18-04, BR-18-05,
 * BR-18-08, BR-18-11, MSG-18-08). Lược đồ biên dịch một lần và đệm theo {@code (tenant, form_key,
 * version, row_version)}; bộ máy CEL theo tenant mang lịch làm việc của tenant cho hàm {@code
 * ngay_lam_viec} (CORE-08-04), đệm 10 phút nên ngày nghỉ mới có hiệu lực chậm tối đa chừng đó.
 */
public class FormValidationService implements FormValidationApi {

  /** BR-18-08: dựng biểu mẫu 50 trường ≤ 50 ms. */
  public static final double RENDER_BUDGET_MS = 50;

  private final FormSchemaRepository repo;
  private final FormSchemaService forms;
  private final ConfigAudit audit;
  private final ObjectMapper json;
  private final Clock clock;
  private WorkingCalendarApi calendars;

  private final Cache<TenantId, CelEngine> engines =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(10_000).build();

  private record Key(TenantId tenant, String formKey, String version, int rowVersion) {}

  private final Cache<Key, CompiledForm> compiled =
      Caffeine.newBuilder().expireAfterAccess(Duration.ofHours(1)).maximumSize(20_000).build();

  public FormValidationService(
      FormSchemaRepository repo,
      FormSchemaService forms,
      ConfigAudit audit,
      ObjectMapper json,
      Clock clock) {
    this.repo = Objects.requireNonNull(repo, "repo");
    this.forms = Objects.requireNonNull(forms, "forms");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public void setCalendars(WorkingCalendarApi calendars) {
    this.calendars = calendars;
  }

  @Override
  @Transactional(transactionManager = UiPackService.TX, readOnly = true)
  public Result validate(
      TenantId tenant, String formKey, String schemaVersion, Map<String, Object> payload) {
    Optional<FormSchema> current = repo.findCurrent(tenant, formKey);
    Optional<FormSchema> used =
        schemaVersion == null ? current : repo.find(tenant, formKey, schemaVersion);
    if (used.isEmpty() || current.isEmpty() || !accepted(tenant, used.get(), current.get())) {
      return new Result(
          List.of(
              new Violation(
                  "schemaVersion",
                  "Mẫu phiếu đã thay đổi, vui lòng tải lại trang để lập phiếu theo mẫu mới.",
                  "BR-18-04")),
          Set.of(),
          schemaVersion);
    }
    FormSchema form = used.get();
    CompiledForm c = compiled(tenant, form);
    return new Result(
        c.check(json.valueToTree(payload == null ? Map.of() : payload)),
        c.c2Fields(),
        form.version());
  }

  /**
   * BR-18-04: nhận phiên bản hiện hành, hoặc phiên bản đã phát hành ngay trước nó khi cùng Policy
   * Pack (thời gian chuyển tiếp); bản nháp và phiên bản cũ hơn bị từ chối.
   */
  private boolean accepted(TenantId tenant, FormSchema used, FormSchema current) {
    if (used.id().equals(current.id())) {
      return true;
    }
    if (used.status() == FormSchemaStatus.NHAP
        || !Objects.equals(used.policyPackVersionId(), current.policyPackVersionId())) {
      return false;
    }
    FormSchema previous = null;
    for (FormSchema f : repo.listVersions(tenant, used.formKey())) {
      if (f.id().equals(current.id())) {
        break;
      }
      if (f.status() != FormSchemaStatus.NHAP) {
        previous = f;
      }
    }
    return previous != null && previous.id().equals(used.id());
  }

  /** Lược đồ đã biên dịch của một phiên bản (đệm). */
  public CompiledForm compiled(TenantId tenant, FormSchema form) {
    return compiled.get(
        new Key(tenant, form.formKey(), form.version(), form.rowVersion()),
        k -> {
          try {
            return new CompiledForm(
                engine(tenant),
                json.readTree(form.schemaJson()),
                json.readTree(form.celRulesJson()));
          } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private CelEngine engine(TenantId tenant) {
    return engines.get(
        tenant,
        t -> {
          WorkingCalendar calendar =
              calendars == null ? StaticWorkingCalendar.weekendsOnly() : calendars.celCalendar(t);
          return new CelEngine(calendar, FormSchemaValidator.LIMITS);
        });
  }

  /**
   * {@code runUiFormTests}: chạy từng ca bằng cel-java, so với kết quả cel-js gửi lên (cùng hợp lệ
   * hay không, và cùng tập trường lỗi khi trình duyệt gửi kèm), lưu kết quả vào phiên bản nháp.
   *
   * @return kết quả theo {@code UiFormTestRunResult}, kèm băm phần tính điểm
   */
  @Transactional(UiPackService.TX)
  public ObjectNode runTests(
      TenantId tenant, UUID actorId, String formKey, String version, JsonNode request) {
    FormSchema form = repo.find(tenant, formKey, version).orElseThrow();
    if (!form.isDraft()) {
      throw new BusinessException(
          "UI-409-NOT-DRAFT",
          "Phiên bản " + version + " của biểu mẫu không còn ở trạng thái nháp.");
    }
    JsonNode cases = request.path("cases");
    if (!cases.isArray() || cases.isEmpty() || cases.size() > 500) {
      throw new BusinessException(
          "COMMON-422-VALIDATION",
          "Bộ test cần từ 1 đến 500 ca.",
          List.of(new ProblemFieldError("cases", "Bộ test cần từ 1 đến 500 ca.", null)));
    }
    CompiledForm c;
    try {
      c = compiled(tenant, form);
    } catch (IllegalArgumentException e) {
      throw new BusinessException("UI-422-CEL-INVALID", e.getMessage(), List.of(), "MSG-18-06");
    }
    ObjectNode run = json.createObjectNode();
    ArrayNode out = json.createArrayNode();
    int passed = 0;
    for (JsonNode tc : cases) {
      List<Violation> errors = c.check(tc.path("input"));
      boolean serverValid = errors.isEmpty();
      boolean browserValid = tc.path("browserValid").asBoolean(false);
      boolean match = serverValid == browserValid;
      if (match && !tc.path("browserErrors").isEmpty()) {
        Set<String> browser = new HashSet<>();
        tc.get("browserErrors").forEach(e -> browser.add(e.path("field").asText()));
        Set<String> server = new HashSet<>();
        errors.forEach(e -> server.add(e.field()));
        match = browser.equals(server);
      }
      passed += match ? 1 : 0;
      ObjectNode r =
          out.addObject()
              .put("caseId", tc.path("caseId").asText())
              .put("serverValid", serverValid)
              .put("browserValid", browserValid)
              .put("match", match);
      if (!serverValid) {
        ArrayNode se = r.putArray("serverErrors");
        errors.forEach(e -> se.addObject().put("field", e.field()).put("message", e.message()));
      }
    }
    Instant at = clock.instant();
    run.put("total", cases.size());
    run.put("passed", passed);
    run.put("parity", passed == cases.size());
    JsonNode render = request.get("renderMs50Fields");
    if (render != null && render.isNumber()) {
      run.put("renderMs50Fields", render.doubleValue());
    } else {
      run.putNull("renderMs50Fields");
    }
    run.set("cases", out);
    run.put("ranAt", at.toString());
    run.put(FormSchemaService.SCORING_HASH, forms.scoringHash(form));
    FormSchema next;
    try {
      next = form.tested(json.writeValueAsString(run), actorId, at);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    if (!repo.update(tenant, next, form.rowVersion())) {
      throw VersionConflictException.of("CONFIG", form.rowVersion(), form.rowVersion() + 1);
    }
    audit.changed(
        tenant,
        actorId,
        "CHAY_BO_TEST_BIEU_MAU",
        FormSchemaService.OBJECT_TYPE,
        formKey + "@" + version,
        next.id(),
        next.rowVersion());
    return run;
  }
}
