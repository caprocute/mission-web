package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PolicySchemaPort;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.FormSchemaRepository;
import vn.viettel.okrkpi.config.application.port.UiPackRepository;
import vn.viettel.okrkpi.config.domain.FormSchema;
import vn.viettel.okrkpi.config.domain.IllegalUiPackTransitionException;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.config.domain.UiPackVersionStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Biểu mẫu động theo phiên bản (CORE-08-13, QT-18, SCR-1802): soạn, lưu nháp có kiểm, phục vụ bản
 * hiện hành, gắn vào UI Pack nháp. Biểu mẫu chuyển {@code DA_PHAT_HANH} khi UI Pack chứa nó được
 * phát hành ({@link #publishAttached}).
 */
public class FormSchemaService {

  public static final String OBJECT_TYPE = "form_schema";

  /** Khoá của băm phần tính điểm ghi kèm {@code last_test_run}. */
  public static final String SCORING_HASH = "scoringHash";

  static final String EMPTY_SCHEMA =
      "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
          + "\"properties\":{}}";

  private final FormSchemaRepository repo;
  private final UiPackRepository packs;
  private final FormSchemaValidator validator;
  private final ConfigAudit audit;
  private final ObjectMapper json;
  private final UiPackCompiler canonical;
  private final Clock clock;
  private PolicySchemaPort schemas;

  public FormSchemaService(
      FormSchemaRepository repo,
      UiPackRepository packs,
      FormSchemaValidator validator,
      ConfigAudit audit,
      ObjectMapper json,
      Clock clock) {
    this.repo = Objects.requireNonNull(repo, "repo");
    this.packs = Objects.requireNonNull(packs, "packs");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
    this.canonical = new UiPackCompiler(json);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Cổng Policy Pack của MOD-05; chưa có thì bỏ qua kiểm trường tính điểm. */
  public void setPolicySchemas(PolicySchemaPort schemas) {
    this.schemas = schemas;
  }

  @Transactional(transactionManager = UiPackService.TX, readOnly = true)
  public List<FormSchema> list(TenantId tenantId, String query, String afterKey, int limit) {
    return repo.listLatest(tenantId, query, afterKey, limit);
  }

  @Transactional(transactionManager = UiPackService.TX, readOnly = true)
  public FormSchema get(TenantId tenantId, String formKey, String version) {
    return repo.find(tenantId, formKey, version).orElseThrow();
  }

  @Transactional(transactionManager = UiPackService.TX, readOnly = true)
  public Optional<FormSchema> current(TenantId tenantId, String formKey) {
    return repo.findCurrent(tenantId, formKey);
  }

  /** Tạo phiên bản nháp, nhân bản từ {@code cloneFrom} hoặc trống. */
  @Transactional(UiPackService.TX)
  public FormSchema create(
      TenantId tenantId,
      UUID actorId,
      String formKey,
      String version,
      String cloneFrom,
      UUID policyPackVersionId) {
    if (formKey == null || !FormSchema.KEY.matcher(formKey).matches()) {
      throw invalid("formKey", "Khoá biểu mẫu chỉ gồm chữ thường, số và dấu gạch ngang");
    }
    if (version == null || !FormSchema.VERSION.matcher(version).matches()) {
      throw invalid("version", "Phiên bản không hợp lệ");
    }
    String schema = EMPTY_SCHEMA;
    String ui = "{}";
    String rules = "[]";
    UUID policyPack = policyPackVersionId;
    if (cloneFrom != null && !cloneFrom.isBlank()) {
      FormSchema source =
          repo.find(tenantId, formKey, cloneFrom)
              .orElseThrow(
                  () -> invalid("cloneFromVersion", "Không có phiên bản " + cloneFrom + " để sao"));
      schema = source.schemaJson();
      ui = source.uiJson();
      rules = source.celRulesJson();
      policyPack = policyPack == null ? source.policyPackVersionId() : policyPack;
    }
    Instant at = clock.instant();
    FormSchema draft =
        FormSchema.draft(
            Ids.newUuidV7(), formKey, version, schema, ui, rules, policyPack, actorId, at);
    if (!repo.insert(tenantId, draft)) {
      throw invalid("version", "Phiên bản " + version + " đã có");
    }
    audit.changed(
        tenantId, actorId, "TAO_PHIEN_BAN_BIEU_MAU", OBJECT_TYPE, key(draft), draft.id(), 0);
    return draft;
  }

  /** Lưu nháp có kiểm (BR-18-04, BR-18-06, BR-18-11). */
  @Transactional(UiPackService.TX)
  public FormSchema update(
      TenantId tenantId,
      UUID actorId,
      String formKey,
      String version,
      long expectedVersion,
      JsonNode schema,
      JsonNode ui,
      JsonNode celRules,
      UUID policyPackVersionId) {
    FormSchema current = repo.find(tenantId, formKey, version).orElseThrow();
    if (expectedVersion != current.rowVersion()) {
      throw VersionConflictException.of("CONFIG", expectedVersion, current.rowVersion());
    }
    if (!current.isDraft()) {
      throw new BusinessException(
          "UI-409-NOT-DRAFT",
          "Phiên bản " + version + " của biểu mẫu không còn ở trạng thái nháp.");
    }
    JsonNode rules = celRules == null || celRules.isNull() ? json.createArrayNode() : celRules;
    FormSchemaValidator.Result result = validator.validate(schema, ui, rules);
    if (!result.cel().isEmpty()) {
      throw new BusinessException(
          "UI-422-CEL-INVALID", result.cel().get(0).message(), result.cel(), "MSG-18-06");
    }
    if (!result.structure().isEmpty()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION", "Biểu mẫu chưa hợp lệ.", result.structure());
    }
    UUID policyPack =
        policyPackVersionId != null ? policyPackVersionId : current.policyPackVersionId();
    scoredFields(tenantId, policyPack, result.fields());
    FormSchema next =
        current.edit(write(schema), write(ui), write(rules), policyPack, actorId, clock.instant());
    if (!repo.update(tenantId, next, current.rowVersion())) {
      throw VersionConflictException.of("CONFIG", expectedVersion, current.rowVersion() + 1);
    }
    audit.changed(
        tenantId,
        actorId,
        "LUU_NHAP_BIEU_MAU",
        OBJECT_TYPE,
        key(next),
        next.id(),
        next.rowVersion());
    return next;
  }

  /** MSG-18-07: trường tính điểm của Policy Pack phải còn và đúng kiểu. */
  private void scoredFields(TenantId tenantId, UUID policyPack, Map<String, String> fields) {
    if (schemas == null || policyPack == null) {
      return;
    }
    for (Map.Entry<String, String> scored : schemas.scoredFields(tenantId, policyPack).entrySet()) {
      String type = fields.get(scored.getKey());
      if (type == null || !compatible(scored.getValue(), type)) {
        String message =
            "Trường "
                + scored.getKey()
                + " được Policy Pack "
                + policyPack
                + " dùng để tính điểm nên không thể đổi kiểu hoặc xoá.";
        throw new BusinessException(
            "UI-409-SCORED-FIELD",
            message,
            List.of(
                new ProblemFieldError("schemaJson.properties." + scored.getKey(), message, null)),
            "MSG-18-07");
      }
    }
  }

  private static boolean compatible(String declared, String actual) {
    return declared.equals(actual) || ("number".equals(declared) && "integer".equals(actual));
  }

  /**
   * Băm phần tính điểm (lược đồ dữ liệu và quy tắc CEL), không gồm phần trình bày: đổi nhãn, thứ
   * tự, widget không làm mất hiệu lực bộ test (BR-18-07).
   */
  public String scoringHash(FormSchema form) {
    try {
      ObjectNode scoring = json.createObjectNode();
      scoring.set("schema", json.readTree(form.schemaJson()));
      scoring.set("celRules", json.readTree(form.celRulesJson()));
      return HexFormat.of().formatHex(UiPackPublishService.sha256(canonical.canonical(scoring)));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Bộ test gần nhất đạt hết, hai engine khớp và còn hiệu lực với phần tính điểm hiện tại. */
  public boolean testsPassed(FormSchema form) {
    if (form.lastTestRunJson() == null) {
      return false;
    }
    try {
      JsonNode run = json.readTree(form.lastTestRunJson());
      return run.path("parity").asBoolean(false)
          && run.path("total").asInt(-1) == run.path("passed").asInt(-2)
          && run.path(SCORING_HASH).asText("").equals(scoringHash(form));
    } catch (JsonProcessingException e) {
      return false;
    }
  }

  /** Gắn biểu mẫu vào UI Pack nháp ({@code attachUiPackForm}, BR-18-05, BR-18-06). */
  @Transactional(UiPackService.TX)
  public UiPackVersion attach(
      TenantId tenantId,
      UUID actorId,
      UUID packId,
      String packVersion,
      long expectedVersion,
      String formKey,
      String formVersion) {
    packs.findPack(tenantId, packId).orElseThrow();
    UiPackVersion target = packs.findVersion(tenantId, packId, packVersion).orElseThrow();
    UiPackService.expect(target, expectedVersion);
    if (target.status() != UiPackVersionStatus.NHAP) {
      throw new BusinessException(
          "UI-409-NOT-DRAFT", "Phiên bản " + packVersion + " không còn ở trạng thái nháp.");
    }
    FormSchema form =
        repo.find(tenantId, formKey, formVersion)
            .orElseThrow(
                () ->
                    invalid(
                        "formVersion",
                        "Không có phiên bản " + formVersion + " của biểu mẫu " + formKey));
    if (!testsPassed(form)) {
      throw new BusinessException(
          "UI-422-FORM-TEST-FAILED",
          "Biểu mẫu chưa đạt bộ test, vui lòng chạy lại bộ test trước khi đưa vào giao diện.");
    }
    UUID packPolicy = target.content().policyPackVersionId();
    if (packPolicy != null
        && form.policyPackVersionId() != null
        && !packPolicy.equals(form.policyPackVersionId())) {
      throw invalid(
          "formVersion", "Biểu mẫu tham chiếu bộ quy tắc đánh giá khác với giao diện đang soạn");
    }
    ArrayNode forms;
    try {
      forms = (ArrayNode) json.readTree(target.content().formsJson());
    } catch (JsonProcessingException | ClassCastException e) {
      forms = json.createArrayNode();
    }
    ArrayNode next = json.createArrayNode();
    for (JsonNode f : forms) {
      if (!formKey.equals(f.path("formKey").asText())) {
        next.add(f);
      }
    }
    next.addObject().put("formKey", formKey).put("version", formVersion);
    UiPackVersion edited;
    try {
      edited = target.edit(target.content().withForms(write(next)), actorId, clock.instant());
    } catch (IllegalUiPackTransitionException e) {
      throw new BusinessException(
          "UI-409-NOT-DRAFT", "Phiên bản " + packVersion + " không còn ở trạng thái nháp.");
    }
    if (!packs.updateVersion(tenantId, edited, target.rowVersion())) {
      throw VersionConflictException.of("CONFIG", expectedVersion, target.rowVersion() + 1);
    }
    audit.changed(
        tenantId,
        actorId,
        "GAN_BIEU_MAU_UI_PACK",
        UiPackService.OBJECT_TYPE,
        packVersion,
        edited.id(),
        edited.rowVersion());
    return edited;
  }

  /** Khi UI Pack được phát hành: các biểu mẫu nó gắn chuyển {@code DA_PHAT_HANH}. */
  public void publishAttached(TenantId tenantId, UiPackVersion version, UUID actorId, Instant at) {
    for (UiPackFormRef ref : refs(version)) {
      repo.find(tenantId, ref.formKey(), ref.version())
          .filter(FormSchema::isDraft)
          .ifPresent(
              f -> {
                if (!repo.update(tenantId, f.publish(actorId, at), f.rowVersion())) {
                  throw VersionConflictException.of("CONFIG", f.rowVersion(), -1);
                }
              });
    }
  }

  /** Biểu mẫu gắn trong một phiên bản UI Pack. */
  public record UiPackFormRef(String formKey, String version) {}

  public List<UiPackFormRef> refs(UiPackVersion version) {
    try {
      JsonNode forms = json.readTree(version.content().formsJson());
      return java.util.stream.StreamSupport.stream(forms.spliterator(), false)
          .map(f -> new UiPackFormRef(f.path("formKey").asText(), f.path("version").asText()))
          .toList();
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  private String write(JsonNode node) {
    try {
      return json.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String key(FormSchema f) {
    return f.formKey() + "@" + f.version();
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
