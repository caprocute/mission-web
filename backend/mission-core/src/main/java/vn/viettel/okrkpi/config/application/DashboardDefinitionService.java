package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.DashboardDefinitionApi;
import vn.viettel.okrkpi.config.application.port.ConfigAudit;
import vn.viettel.okrkpi.config.application.port.DashboardDefinitionRepository;
import vn.viettel.okrkpi.config.domain.DashboardDefinition;
import vn.viettel.okrkpi.config.domain.DashboardDefinition.ShareScope;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kho định nghĩa dashboard tự dựng cho analytics-api (QT-16, SCR-1602, BR-16-02; CORE-08-17). Kiểm
 * các quy tắc của SCR-1602 (tên, số widget, số chiều, phạm vi chia sẻ) và trả mọi lỗi theo trường
 * trong một lần 422; tên chỉ số và chiều chỉ kiểm cú pháp, analytics-api kiểm theo lớp ngữ nghĩa.
 * Chỉ người tạo được thay; đổi phạm vi chia sẻ ghi sự kiện kiểm toán.
 */
public class DashboardDefinitionService implements DashboardDefinitionApi {

  static final String TX = "configTransactionManager";
  static final String MODULE = "CONFIG";
  static final String OBJECT_TYPE = "config.dashboard_def";
  static final String AUDIT_SHARE = "CHIA_SE_DASHBOARD";

  private static final Set<String> CHART_TYPES = Set.of("THE_SO", "COT", "DUONG", "THANH", "BANG");
  private static final Set<String> OPERATORS = Set.of("equals", "notEquals", "in", "gte", "lte");
  private static final int MAX_WIDGET_ID = 32;
  private static final int MAX_FILTERS = 20;
  private static final int MAX_FILTER_VALUES = 100;
  private static final int MAX_SEMANTIC_VERSION = 32;

  private final DashboardDefinitionRepository repository;
  private final ConfigAudit audit;
  private final ObjectMapper json;
  private final Clock clock;

  public DashboardDefinitionService(
      DashboardDefinitionRepository repository, ConfigAudit audit, ObjectMapper json, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Một trang dashboard người xem thấy được, mới nhất trước. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<DashboardDefinition> visible(
      TenantId tenantId,
      UUID userId,
      List<UUID> viewerUnits,
      boolean ownOnly,
      UUID beforeId,
      int limit) {
    return repository.visible(tenantId, userId, viewerUnits, ownOnly, beforeId, limit);
  }

  /**
   * @throws NoSuchElementException khi không có hoặc người xem không được chia sẻ
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public DashboardDefinition get(TenantId tenantId, UUID userId, List<UUID> viewerUnits, UUID id) {
    return repository
        .find(tenantId, id)
        .filter(d -> d.visibleTo(userId, viewerUnits))
        .orElseThrow();
  }

  @Override
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<DashboardDefinitionView> find(
      TenantId tenantId, UUID viewerUserId, List<UUID> viewerUnits, UUID id) {
    return repository
        .find(tenantId, id)
        .filter(d -> d.visibleTo(viewerUserId, viewerUnits))
        .map(this::view);
  }

  /** Dạng chỉ đọc của cổng {@link DashboardDefinitionApi}. */
  public DashboardDefinitionView view(DashboardDefinition d) {
    return new DashboardDefinitionView(
        d.id(),
        d.ownerUserId(),
        d.title(),
        d.shareScope().name(),
        d.sharedUnitIds(),
        d.measures(),
        d.dimensions(),
        layout(d),
        d.updatedAt(),
        d.rowVersion());
  }

  /** Lưu định nghĩa mới của người thực hiện. */
  @Transactional(TX)
  public DashboardDefinition create(TenantId tenantId, UUID userId, JsonNode body) {
    Input in = parse(body);
    repository.lockOwner(tenantId, userId);
    requireUniqueTitle(tenantId, userId, in.title(), null);
    DashboardDefinition created =
        new DashboardDefinition(
            Ids.newUuidV7(),
            userId,
            in.title(),
            in.measures(),
            in.dimensions(),
            in.layoutJson(),
            in.scope(),
            in.units(),
            clock.instant(),
            0);
    repository.insert(tenantId, created, userId);
    if (created.shareScope() != ShareScope.RIENG) {
      audit.changed(
          tenantId, userId, AUDIT_SHARE, OBJECT_TYPE, created.id().toString(), created.id(), 0);
    }
    return created;
  }

  /**
   * Thay toàn bộ định nghĩa (cả {@code updateDashboard} và {@code shareDashboard} của
   * analytics-api).
   *
   * @param expectedVersion phiên bản trong {@code If-Match}; {@code null} khi người gọi không gửi
   * @throws NoSuchElementException khi không có định nghĩa
   */
  @Transactional(TX)
  public DashboardDefinition replace(
      TenantId tenantId, UUID userId, UUID id, Long expectedVersion, JsonNode body) {
    DashboardDefinition current = repository.find(tenantId, id).orElseThrow();
    if (!current.ownerUserId().equals(userId)) {
      throw new BusinessException(
          "CONFIG-403-NOT-OWNER", "Chỉ người tạo dashboard được sửa hoặc chia sẻ dashboard này.");
    }
    if (expectedVersion == null) {
      throw new PreconditionRequiredException();
    }
    if (expectedVersion != current.rowVersion()) {
      throw VersionConflictException.of(MODULE, expectedVersion, current.rowVersion());
    }
    Input in = parse(body);
    repository.lockOwner(tenantId, userId);
    requireUniqueTitle(tenantId, userId, in.title(), id);
    DashboardDefinition next =
        current.replaced(
            in.title(),
            in.measures(),
            in.dimensions(),
            in.layoutJson(),
            in.scope(),
            in.units(),
            clock.instant());
    if (!repository.update(tenantId, next, current.rowVersion(), userId)) {
      throw VersionConflictException.of(MODULE, expectedVersion, current.rowVersion() + 1L);
    }
    boolean shareChanged =
        current.shareScope() != next.shareScope()
            || !Set.copyOf(current.sharedUnitIds()).equals(Set.copyOf(next.sharedUnitIds()));
    if (shareChanged) {
      audit.changed(
          tenantId, userId, AUDIT_SHARE, OBJECT_TYPE, id.toString(), id, next.rowVersion());
    }
    return next;
  }

  /** Bố cục đã lưu ({@code widgets}, {@code semanticModelVersion}). */
  public JsonNode layout(DashboardDefinition d) {
    try {
      return json.readTree(d.layoutJson());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cột bố cục của dashboard hỏng", e);
    }
  }

  private void requireUniqueTitle(TenantId tenantId, UUID userId, String title, UUID exceptId) {
    if (repository.titleTaken(tenantId, userId, title, exceptId)) {
      throw new BusinessException(
          "CONFIG-409-DUPLICATE-TITLE",
          "Bạn đã có dashboard mang tên này. Vui lòng đặt tên khác.",
          List.of(new ProblemFieldError("title", "Tên dashboard đã được dùng.", null)));
    }
  }

  private record Input(
      String title,
      List<String> measures,
      List<String> dimensions,
      String layoutJson,
      ShareScope scope,
      List<UUID> units) {}

  /** Đọc và kiểm thân yêu cầu; gom mọi lỗi theo trường rồi trả 422 một lần. */
  private Input parse(JsonNode body) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (body == null || !body.isObject()) {
      throw invalid(List.of(error("body", "Phải là đối tượng.")));
    }
    JsonNode t = body.get("title");
    String title = t != null && t.isTextual() ? t.asText().strip() : "";
    if (title.isEmpty() || title.length() > DashboardDefinition.MAX_TITLE_LENGTH) {
      errors.add(
          error(
              "title",
              "Tên dashboard bắt buộc, tối đa "
                  + DashboardDefinition.MAX_TITLE_LENGTH
                  + " ký tự."));
    }
    Set<String> measures = new LinkedHashSet<>();
    Set<String> dimensions = new LinkedHashSet<>();
    ArrayNode widgets = json.createArrayNode();
    JsonNode layout = body.get("layout");
    if (layout == null || !layout.isArray() || layout.isEmpty()) {
      errors.add(error("layout", "Dashboard cần ít nhất một widget."));
    } else if (layout.size() > DashboardDefinition.MAX_WIDGETS) {
      errors.add(
          error(
              "layout", "Mỗi dashboard có tối đa " + DashboardDefinition.MAX_WIDGETS + " widget."));
    } else {
      Set<String> ids = new HashSet<>();
      for (int i = 0; i < layout.size(); i++) {
        widgets.add(widget(layout.get(i), "layout[" + i + "]", ids, measures, dimensions, errors));
      }
    }
    ShareScope scope = ShareScope.RIENG;
    JsonNode s = body.get("shareScope");
    if (s != null && !s.isNull()) {
      try {
        scope = ShareScope.valueOf(s.asText());
      } catch (IllegalArgumentException e) {
        errors.add(error("shareScope", "Phạm vi chia sẻ không hợp lệ."));
      }
    }
    List<UUID> units = new ArrayList<>();
    JsonNode u = body.get("sharedUnitIds");
    if (u != null && !u.isNull()) {
      if (!u.isArray() || u.size() > DashboardDefinition.MAX_SHARED_UNITS) {
        errors.add(
            error(
                "sharedUnitIds",
                "Chia sẻ cho tối đa " + DashboardDefinition.MAX_SHARED_UNITS + " đơn vị."));
      } else {
        for (JsonNode n : u) {
          try {
            UUID id = UUID.fromString(n.asText());
            if (!units.contains(id)) {
              units.add(id);
            }
          } catch (IllegalArgumentException e) {
            errors.add(error("sharedUnitIds", "Mã đơn vị không hợp lệ."));
            break;
          }
        }
      }
    }
    if (scope == ShareScope.DON_VI && units.isEmpty()) {
      errors.add(error("sharedUnitIds", "Chọn ít nhất một đơn vị khi chia sẻ theo đơn vị."));
    } else if (scope != ShareScope.DON_VI && !units.isEmpty()) {
      errors.add(error("sharedUnitIds", "Chỉ chọn đơn vị khi chia sẻ theo đơn vị."));
    }
    JsonNode v = body.get("semanticModelVersion");
    String semantic = null;
    if (v != null && !v.isNull()) {
      if (!v.isTextual() || v.asText().length() > MAX_SEMANTIC_VERSION) {
        errors.add(error("semanticModelVersion", "Phiên bản lớp ngữ nghĩa không hợp lệ."));
      } else {
        semantic = v.asText();
      }
    }
    if (!errors.isEmpty()) {
      throw invalid(errors);
    }
    ObjectNode stored = json.createObjectNode();
    stored.set("widgets", widgets);
    if (semantic != null) {
      stored.put("semanticModelVersion", semantic);
    }
    return new Input(
        title,
        List.copyOf(measures),
        List.copyOf(dimensions),
        stored.toString(),
        scope,
        List.copyOf(units));
  }

  /** Kiểm một widget và dựng lại chỉ với các trường của hợp đồng ({@code DashboardWidget}). */
  private ObjectNode widget(
      JsonNode w,
      String path,
      Set<String> ids,
      Set<String> measures,
      Set<String> dimensions,
      List<ProblemFieldError> errors) {
    ObjectNode out = json.createObjectNode();
    if (w == null || !w.isObject()) {
      errors.add(error(path, "Widget phải là đối tượng."));
      return out;
    }
    JsonNode id = w.get("id");
    if (id == null
        || !id.isTextual()
        || id.asText().isBlank()
        || id.asText().length() > MAX_WIDGET_ID
        || !ids.add(id.asText())) {
      errors.add(error(path + ".id", "Mã widget bắt buộc, không trùng, tối đa 32 ký tự."));
    } else {
      out.put("id", id.asText());
    }
    position(w, path, "x", 0, out, errors);
    position(w, path, "y", 0, out, errors);
    position(w, path, "w", 1, out, errors);
    position(w, path, "h", 1, out, errors);
    JsonNode m = w.get("measure");
    if (m == null || !m.isTextual() || !DashboardDefinition.MEMBER.matcher(m.asText()).matches()) {
      errors.add(error(path + ".measure", "Mỗi widget cần đúng một chỉ số hợp lệ."));
    } else {
      out.put("measure", m.asText());
      measures.add(m.asText());
    }
    ArrayNode dims = out.putArray("dimensions");
    JsonNode d = w.get("dimensions");
    if (d != null && !d.isNull()) {
      if (!d.isArray() || d.size() > DashboardDefinition.MAX_DIMENSIONS_PER_WIDGET) {
        errors.add(
            error(
                path + ".dimensions",
                "Mỗi widget có tối đa "
                    + DashboardDefinition.MAX_DIMENSIONS_PER_WIDGET
                    + " chiều phân tích."));
      } else {
        for (JsonNode n : d) {
          if (!n.isTextual() || !DashboardDefinition.MEMBER.matcher(n.asText()).matches()) {
            errors.add(error(path + ".dimensions", "Tên chiều phân tích không hợp lệ."));
            break;
          }
          dims.add(n.asText());
          dimensions.add(n.asText());
        }
      }
    }
    JsonNode c = w.get("chartType");
    if (c == null || !c.isTextual() || !CHART_TYPES.contains(c.asText())) {
      errors.add(error(path + ".chartType", "Kiểu biểu đồ không hợp lệ."));
    } else {
      out.put("chartType", c.asText());
    }
    out.set("filters", filters(w.get("filters"), path + ".filters", errors));
    JsonNode drill = w.get("drillDown");
    if (drill != null && !drill.isNull() && !drill.isBoolean()) {
      errors.add(error(path + ".drillDown", "Phải là đúng hoặc sai."));
    }
    out.put("drillDown", drill == null || drill.isNull() || drill.asBoolean());
    return out;
  }

  private ArrayNode filters(JsonNode f, String path, List<ProblemFieldError> errors) {
    ArrayNode out = json.createArrayNode();
    if (f == null || f.isNull()) {
      return out;
    }
    if (!f.isArray() || f.size() > MAX_FILTERS) {
      errors.add(error(path, "Mỗi widget có tối đa " + MAX_FILTERS + " điều kiện lọc."));
      return out;
    }
    for (int i = 0; i < f.size(); i++) {
      JsonNode n = f.get(i);
      String p = path + "[" + i + "]";
      JsonNode member = n.get("member");
      JsonNode op = n.get("operator");
      JsonNode values = n.get("values");
      boolean ok =
          n.isObject()
              && member != null
              && member.isTextual()
              && DashboardDefinition.MEMBER.matcher(member.asText()).matches()
              && op != null
              && OPERATORS.contains(op.asText())
              && values != null
              && values.isArray()
              && values.size() <= MAX_FILTER_VALUES;
      if (ok) {
        for (JsonNode x : values) {
          ok &= x.isTextual() || x.isNumber();
        }
      }
      if (!ok) {
        errors.add(error(p, "Điều kiện lọc không hợp lệ."));
        continue;
      }
      ObjectNode o = out.addObject();
      o.put("member", member.asText());
      o.put("operator", op.asText());
      o.set("values", values.deepCopy());
    }
    return out;
  }

  private static void position(
      JsonNode w,
      String path,
      String field,
      int min,
      ObjectNode out,
      List<ProblemFieldError> errors) {
    JsonNode n = w.get(field);
    if (n == null || !n.canConvertToInt() || !n.isIntegralNumber() || n.asInt() < min) {
      errors.add(error(path + "." + field, "Vị trí, kích thước widget không hợp lệ."));
    } else {
      out.put(field, n.asInt());
    }
  }

  private static ProblemFieldError error(String field, String message) {
    return new ProblemFieldError(field, message, null);
  }

  private static BusinessException invalid(List<ProblemFieldError> errors) {
    return new BusinessException(
        "COMMON-422-VALIDATION", "Định nghĩa dashboard chưa hợp lệ.", List.copyOf(errors));
  }
}
