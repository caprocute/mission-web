package vn.viettel.okrkpi.config.infrastructure.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.application.UserPreferenceService;
import vn.viettel.okrkpi.config.domain.SavedFilter;
import vn.viettel.okrkpi.config.domain.UserPreference;
import vn.viettel.okrkpi.contract.config.model.SavedFilterList;
import vn.viettel.okrkpi.contract.config.model.UserPreferenceItem;
import vn.viettel.okrkpi.contract.config.model.UserPreferenceMap;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Tuỳ chọn cá nhân và bộ lọc đã lưu của {@code config.yaml} (CNH-02, đề xuất CNH-01): {@code
 * getMyPreferences}, {@code putMyPreference}, {@code deleteMyPreference}, {@code
 * listMySavedFilters}, {@code createMySavedFilter}, {@code updateMySavedFilter}, {@code
 * deleteMySavedFilter}. Chủ bản ghi luôn là người gọi (phạm vi Bản thân); OPA cho mọi vai trò con
 * người qua nhánh tài nguyên tự phục vụ. Thân yêu cầu đọc dạng cây JSON để lỗi kiểu trả 422 theo
 * trường thay vì 400.
 */
@RestController
@RequestMapping("/api/v1/config/me")
public class UserPreferenceController {

  private static final String PREFS = "/api/v1/config/me/preferences";
  private static final String FILTERS = "/api/v1/config/me/saved-filters";
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private final UserPreferenceService preferences;
  private final Callers callers;
  private final ConfigIdempotency idempotency;
  private final ObjectMapper json;

  public UserPreferenceController(
      UserPreferenceService preferences,
      ObjectProvider<AuthzSubjectResolver> subjects,
      ObjectProvider<PeriodCaller> periodCallers,
      ConfigIdempotency idempotency,
      ObjectProvider<ObjectMapper> json) {
    this.preferences = Objects.requireNonNull(preferences, "preferences");
    this.callers = new Callers(subjects, periodCallers);
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.json = json.getIfAvailable(ObjectMapper::new);
  }

  @GetMapping("/preferences")
  @RequiresPermission(
      operationId = "getMyPreferences",
      module = "config",
      resourceType = "user_preference")
  public ResponseEntity<UserPreferenceMap> getMyPreferences(
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    UserPreferenceService.Preferences p =
        preferences.preferences(TenantContext.require(), callers.current());
    String etag = "\"" + p.etag() + "\"";
    if (matches(ifNoneMatch, etag)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, etag).build();
    }
    Map<String, UserPreferenceItem> items = new LinkedHashMap<>();
    p.items().forEach(i -> items.put(i.prefKey(), toDto(i)));
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, etag)
        .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
        .body(new UserPreferenceMap(UserPreferenceService.SCHEMA_VERSION, items));
  }

  @PutMapping("/preferences/{prefKey}")
  @RequiresPermission(
      operationId = "putMyPreference",
      module = "config",
      resourceType = "user_preference")
  public ResponseEntity<UserPreferenceItem> putMyPreference(
      @PathVariable("prefKey") String prefKey,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    Long expected =
        ifMatch == null || ifMatch.isBlank() ? null : ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        PREFS + "/" + prefKey,
        body,
        UserPreferenceItem.class,
        UserPreferenceItem::getRowVersion,
        () -> {
          JsonNode in = requireObject(body, "body");
          JsonNode schema = in.get("schemaVersion");
          if (schema != null && !schema.isIntegralNumber()) {
            throw invalid("schemaVersion", "Phiên bản lược đồ phải là số nguyên.");
          }
          UserPreferenceService.Saved saved =
              preferences.put(
                  tenant,
                  actor,
                  prefKey,
                  in.get("value"),
                  schema == null ? null : schema.asInt(),
                  expected);
          UserPreferenceItem dto = toDto(saved.preference());
          return saved.created()
              ? ResponseEntity.status(HttpStatus.CREATED)
                  .eTag(ETags.of(dto.getRowVersion()))
                  .body(dto)
              : ETags.ok(dto, dto.getRowVersion());
        });
  }

  @DeleteMapping("/preferences/{prefKey}")
  @RequiresPermission(
      operationId = "deleteMyPreference",
      module = "config",
      resourceType = "user_preference")
  public ResponseEntity<Void> deleteMyPreference(
      @PathVariable("prefKey") String prefKey,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "DELETE",
        PREFS + "/" + prefKey,
        null,
        Void.class,
        v -> 0L,
        () ->
            notFoundAs404(
                () -> {
                  preferences.delete(tenant, actor, prefKey, expected);
                  return ResponseEntity.noContent().build();
                }));
  }

  @GetMapping("/saved-filters")
  @RequiresPermission(
      operationId = "listMySavedFilters",
      module = "config",
      resourceType = "saved_filter")
  public SavedFilterList listMySavedFilters(
      @RequestParam(value = "screenCode", required = false) String screenCode) {
    return new SavedFilterList(
        preferences.filters(TenantContext.require(), callers.current(), screenCode).stream()
            .map(this::toDto)
            .toList());
  }

  @PostMapping("/saved-filters")
  @RequiresPermission(
      operationId = "createMySavedFilter",
      module = "config",
      resourceType = "saved_filter")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.SavedFilter> createMySavedFilter(
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "POST",
        FILTERS,
        body,
        vn.viettel.okrkpi.contract.config.model.SavedFilter.class,
        vn.viettel.okrkpi.contract.config.model.SavedFilter::getRowVersion,
        () -> {
          FilterInput in = filterInput(body);
          SavedFilter created =
              preferences.createFilter(
                  tenant,
                  actor,
                  text(requireObject(body, "body").get("screenCode")),
                  in.name(),
                  in.criteria(),
                  in.isDefault(),
                  in.displayOrder());
          return ResponseEntity.created(URI.create(FILTERS + "/" + created.id()))
              .eTag(ETags.of(created.rowVersion()))
              .body(toDto(created));
        });
  }

  @PutMapping("/saved-filters/{id}")
  @RequiresPermission(
      operationId = "updateMySavedFilter",
      module = "config",
      resourceType = "saved_filter")
  public ResponseEntity<vn.viettel.okrkpi.contract.config.model.SavedFilter> updateMySavedFilter(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody JsonNode body) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "PUT",
        FILTERS + "/" + id,
        body,
        vn.viettel.okrkpi.contract.config.model.SavedFilter.class,
        vn.viettel.okrkpi.contract.config.model.SavedFilter::getRowVersion,
        () ->
            notFoundAs404(
                () -> {
                  FilterInput in = filterInput(body);
                  SavedFilter saved =
                      preferences.updateFilter(
                          tenant,
                          actor,
                          id,
                          expected,
                          in.name(),
                          in.criteria(),
                          in.isDefault(),
                          in.displayOrder());
                  return ETags.ok(toDto(saved), saved.rowVersion());
                }));
  }

  @DeleteMapping("/saved-filters/{id}")
  @RequiresPermission(
      operationId = "deleteMySavedFilter",
      module = "config",
      resourceType = "saved_filter")
  public ResponseEntity<Void> deleteMySavedFilter(
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    TenantId tenant = TenantContext.require();
    UUID actor = callers.current();
    long expected = ETags.expectedVersion(ifMatch, "CONFIG");
    return idempotency.run(
        tenant,
        actor,
        idempotencyKey,
        "DELETE",
        FILTERS + "/" + id,
        null,
        Void.class,
        v -> 0L,
        () ->
            notFoundAs404(
                () -> {
                  preferences.deleteFilter(tenant, actor, id, expected);
                  return ResponseEntity.noContent().build();
                }));
  }

  private record FilterInput(String name, JsonNode criteria, boolean isDefault, int displayOrder) {}

  private static FilterInput filterInput(JsonNode body) {
    JsonNode in = requireObject(body, "body");
    JsonNode isDefault = in.get("isDefault");
    if (isDefault != null && !isDefault.isBoolean()) {
      throw invalid("isDefault", "Phải là đúng hoặc sai.");
    }
    JsonNode order = in.get("displayOrder");
    if (order != null && !order.isIntegralNumber()) {
      throw invalid("displayOrder", "Thứ tự hiển thị phải là số nguyên.");
    }
    return new FilterInput(
        text(in.get("name")),
        in.get("criteria"),
        isDefault != null && isDefault.asBoolean(),
        order == null ? 0 : order.asInt());
  }

  private UserPreferenceItem toDto(UserPreference p) {
    UserPreferenceItem dto =
        new UserPreferenceItem(
            p.prefKey(),
            json.convertValue(preferences.read(p.valueJson()), OBJECT),
            p.schemaVersion(),
            p.rowVersion());
    dto.setUpdatedAt(offset(p.updatedAt()));
    return dto;
  }

  private vn.viettel.okrkpi.contract.config.model.SavedFilter toDto(SavedFilter f) {
    vn.viettel.okrkpi.contract.config.model.SavedFilter dto =
        new vn.viettel.okrkpi.contract.config.model.SavedFilter(
            f.id(),
            f.screenCode(),
            f.name(),
            json.convertValue(preferences.read(f.criteriaJson()), OBJECT),
            f.isDefault(),
            f.displayOrder(),
            f.rowVersion());
    dto.setUpdatedAt(offset(f.updatedAt()));
    return dto;
  }

  private static JsonNode requireObject(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      throw invalid(field, "Phải là đối tượng.");
    }
    return node;
  }

  private static String text(JsonNode node) {
    return node == null || !node.isTextual() ? null : node.asText();
  }

  private static boolean matches(String ifNoneMatch, String etag) {
    if (ifNoneMatch == null) {
      return false;
    }
    for (String candidate : ifNoneMatch.split(",")) {
      String c = candidate.strip();
      if (c.startsWith("W/")) {
        c = c.substring(2);
      }
      if (c.equals("*") || c.equals(etag)) {
        return true;
      }
    }
    return false;
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static <T> T notFoundAs404(Supplier<T> work) {
    try {
      return work.get();
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static OffsetDateTime offset(Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
