package vn.viettel.okrkpi.policy.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyDecisionTableRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyDecisionTableRepository.StoredTable;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Column;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.FeelError;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Row;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Shadow;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bảng quyết định xếp loại DMN 1.4 của phiên bản Policy Pack (CORE-05-05, QT-15, SCR-1502): kiểm
 * FEEL theo kiểu cột ({@code POLICY-422-FEEL}), điều khoản và giải thích bắt buộc mỗi dòng
 * (BR-15-06), biến của mẫu câu ({@code POLICY-422-TEMPLATE-VAR}, MSG-15-05), cảnh báo dòng bị che
 * (MSG-15-04), sinh {@code dmn_xml} và {@code dmn_hash}.
 */
public class PolicyDecisionTableService {

  static final String TX = PolicyPackService.TX;
  static final String OBJECT_TYPE = "decision_table";

  /** Biến mẫu câu luôn có trong ngữ cảnh xếp loại (gói mẫu, {@code classification.mau_cau}). */
  static final Set<String> TEMPLATE_VARIABLES =
      Set.of(
          "muc_tren",
          "muc",
          "diem",
          "nguong",
          "dieu_kien_thieu",
          "gia_tri",
          "dieu_khoan",
          "phien_ban",
          "cac_quy");

  private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

  /** Nội dung gửi lên ({@code DecisionTableUpdate}). */
  public record TableEdit(
      String hitPolicy,
      String legalRef,
      List<Column> inputs,
      List<Row> rules,
      Map<String, String> explanationTemplates) {}

  /** Bảng đã lưu và nội dung đã đọc từ {@code rules_json}. */
  public record TableView(
      StoredTable stored, DecisionTableSpec spec, Map<String, String> explanationTemplates) {}

  /** Kết quả lưu kèm cảnh báo dòng bị che. */
  public record SavedTable(TableView view, List<Shadow> shadowed) {}

  private final PolicyPackRepository packs;
  private final PolicyDecisionTableRepository tables;
  private final PolicyAudit audit;
  private final ObjectMapper json;

  public PolicyDecisionTableService(
      PolicyPackRepository packs,
      PolicyDecisionTableRepository tables,
      PolicyAudit audit,
      ObjectMapper json) {
    this.packs = Objects.requireNonNull(packs, "packs");
    this.tables = Objects.requireNonNull(tables, "tables");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.json = Objects.requireNonNull(json, "json");
  }

  /** {@code listDecisionTables}; gói hoặc phiên bản không có ném {@link NoSuchElementException}. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<TableView> listTables(TenantId tenant, UUID packId, String version) {
    packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    PolicyPackVersion v =
        packs.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
    return tables.storedTables(tenant, v.id()).stream().map(this::view).toList();
  }

  /** {@code getDecisionTable}, {@code exportDecisionTableDmn}. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<TableView> findTable(TenantId tenant, UUID packId, String version, String key) {
    return packs
        .findVersion(tenant, packId, version)
        .flatMap(v -> tables.storedTable(tenant, v.id(), key))
        .map(this::view);
  }

  /**
   * {@code saveDecisionTable}: chỉ khi phiên bản ở {@code NHAP} (SCR-1502 "Lưu nháp"); bảng chưa có
   * thì thêm mới, đã có thì ghi đè khi {@code expectedRowVersion} khớp.
   */
  @Transactional(TX)
  public SavedTable storeTable(
      TenantId tenant,
      UUID actor,
      UUID packId,
      String version,
      String key,
      long expectedRowVersion,
      TableEdit edit) {
    Objects.requireNonNull(edit, "edit");
    PolicyPack pack = packs.findPack(tenant, packId).orElseThrow(NoSuchElementException::new);
    PolicyPackVersion current =
        packs.findVersion(tenant, packId, version).orElseThrow(NoSuchElementException::new);
    if (current.status() != PolicyVersionStatus.NHAP) {
      throw new BusinessException(
          "POLICY-409-VERSION-FROZEN",
          "Phiên bản " + version + " không ở trạng thái nháp nên không sửa được bảng quyết định");
    }
    if (current.inheritsFromParent()
        && tables.storedTable(tenant, current.sourceVersionId(), key).isPresent()) {
      throw new BusinessException(
          "POLICY-403-NOT-OVERRIDABLE",
          "Bảng quyết định này thuộc gói gốc và không cho phép đơn vị điều chỉnh",
          List.of(
              new ProblemFieldError(
                  "tableKey",
                  "Bảng quyết định của gói gốc không cho phép điều chỉnh",
                  "BR-15-13")));
    }
    Optional<StoredTable> existing = tables.storedTable(tenant, current.id(), key);
    if (existing.isPresent() && existing.get().rowVersion() != expectedRowVersion) {
      throw new VersionConflictException("POLICY", expectedRowVersion, existing.get().rowVersion());
    }

    DecisionTableSpec spec = validate(edit);
    checkFeel(spec);
    Map<String, String> templates =
        edit.explanationTemplates() == null ? Map.of() : edit.explanationTemplates();
    checkTemplates(spec, templates, read(current.manifestJson()));

    String legalRef = edit.legalRef() == null ? "" : edit.legalRef().strip();
    String xml = DmnXmlWriter.write(pack.code(), key, legalRef, spec);
    DecisionTable table =
        new DecisionTable(
            existing.map(s -> s.table().id()).orElseGet(Ids::newUuidV7),
            current.id(),
            key,
            xml,
            spec.hitPolicy(),
            sha256(xml),
            legalRef,
            rulesJson(spec),
            write(templates));
    StoredTable stored;
    if (existing.isPresent()) {
      if (!tables.replaceTable(tenant, table, expectedRowVersion, actor)) {
        throw new VersionConflictException("POLICY", expectedRowVersion, null);
      }
      stored = new StoredTable(table, expectedRowVersion + 1);
    } else {
      tables.appendTable(tenant, table, actor);
      stored = new StoredTable(table, 0);
    }
    audit.changed(
        tenant,
        actor,
        "LUU_BANG_QUYET_DINH_POLICY_PACK",
        OBJECT_TYPE,
        pack.code() + "@" + current.version() + "/" + key,
        table.id(),
        stored.rowVersion());
    return new SavedTable(new TableView(stored, spec, templates), spec.shadowed());
  }

  /** Thông báo MSG-15-04. */
  public static String shadowMessage(Shadow s) {
    return "Dòng "
        + s.row()
        + " không bao giờ được chọn vì dòng "
        + s.shadowedBy()
        + " phía trên luôn thoả mãn trước (hit policy FIRST).";
  }

  private DecisionTableSpec validate(TableEdit edit) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (edit.hitPolicy() == null || !DecisionTableSpec.HIT_POLICIES.contains(edit.hitPolicy())) {
      errors.add(field("hitPolicy", "Hit policy chưa được hỗ trợ", null));
    }
    if (edit.legalRef() != null && edit.legalRef().length() > 200) {
      errors.add(field("legalRef", "Căn cứ pháp lý tối đa 200 ký tự", null));
    }
    List<Column> inputs = edit.inputs() == null ? List.of() : edit.inputs();
    if (inputs.isEmpty()) {
      errors.add(field("inputs", "Cần ít nhất một cột đầu vào", null));
    }
    Set<String> names = new HashSet<>();
    for (int c = 0; c < inputs.size(); c++) {
      Column col = inputs.get(c);
      if (col.name() == null || !NAME.matcher(col.name()).matches() || !names.add(col.name())) {
        errors.add(field("inputs[" + c + "].name", "Tên cột chưa hợp lệ hoặc bị trùng", null));
      }
      if (!List.of("number", "string", "boolean").contains(col.type())) {
        errors.add(field("inputs[" + c + "].type", "Kiểu dữ liệu chưa được hỗ trợ", null));
      }
    }
    List<Row> rules = edit.rules() == null ? List.of() : edit.rules();
    if (rules.isEmpty()) {
      errors.add(field("rules", "Cần ít nhất một dòng", null));
    }
    for (int r = 0; r < rules.size(); r++) {
      Row row = rules.get(r);
      String at = "rules[" + r + "]";
      if (!DecisionTableSpec.LEVELS.contains(row.ratingLevel())) {
        errors.add(field(at + ".ratingLevel", "Mức xếp loại chưa hợp lệ", null));
      }
      if (blank(row.clause()) || row.clause().length() > 100) {
        errors.add(
            field(at + ".clause", "Mỗi dòng cần điều khoản căn cứ, tối đa 100 ký tự", "BR-15-06"));
      }
      if (blank(row.explanation()) || row.explanation().length() > 200) {
        errors.add(
            field(
                at + ".explanation", "Mỗi dòng cần lời giải thích, tối đa 200 ký tự", "BR-15-06"));
      }
      for (String k : row.inputs().keySet()) {
        if (!names.contains(k)) {
          errors.add(field(at + ".inputs." + k, "Cột không có trong khai báo đầu vào", null));
        }
      }
    }
    if (edit.explanationTemplates() != null) {
      edit.explanationTemplates()
          .forEach(
              (k, v) -> {
                if (v == null || v.length() > 1000) {
                  errors.add(
                      field("explanationTemplates." + k, "Mẫu câu tối đa 1.000 ký tự", null));
                }
              });
    }
    if (!errors.isEmpty()) {
      throw new BusinessException("COMMON-422-VALIDATION", "Bảng quyết định chưa hợp lệ", errors);
    }
    return new DecisionTableSpec(edit.hitPolicy(), inputs, rules);
  }

  private static void checkFeel(DecisionTableSpec spec) {
    List<FeelError> errors = spec.feelErrors();
    if (errors.isEmpty()) {
      return;
    }
    List<ProblemFieldError> fields =
        errors.stream()
            .map(
                e ->
                    field(
                        "rules[" + (e.row() - 1) + "].inputs." + e.column(),
                        "Biểu thức không hợp lệ ở dòng "
                            + e.row()
                            + ", cột "
                            + e.column()
                            + ", vị trí "
                            + e.position()
                            + ": "
                            + e.message()
                            + ".",
                        "BR-15-06"))
            .toList();
    throw new BusinessException("POLICY-422-FEEL", fields.get(0).message(), fields);
  }

  private static void checkTemplates(
      DecisionTableSpec spec, Map<String, String> templates, Map<String, Object> manifest) {
    Set<String> allowed = new HashSet<>(TEMPLATE_VARIABLES);
    spec.columns().forEach(c -> allowed.add(c.name()));
    for (String block : List.of("inputs", "inputs_nam", "ngu_canh")) {
      if (manifest.get(block) instanceof Map<?, ?> m) {
        m.keySet().forEach(k -> allowed.add(String.valueOf(k)));
      }
    }
    for (String block : List.of("definitions", "definitions_nam")) {
      if (manifest.get(block) instanceof Map<?, ?> scopes) {
        scopes.values().stream()
            .filter(Map.class::isInstance)
            .forEach(s -> ((Map<?, ?>) s).keySet().forEach(k -> allowed.add(String.valueOf(k))));
      }
    }
    Map<String, String> texts = new LinkedHashMap<>();
    templates.forEach((k, v) -> texts.put("explanationTemplates." + k, v));
    for (int r = 0; r < spec.rows().size(); r++) {
      texts.put("rules[" + r + "].explanation", spec.rows().get(r).explanation());
    }
    for (Map.Entry<String, String> t : texts.entrySet()) {
      for (String var : DecisionTableSpec.templateVariables(t.getValue())) {
        if (!allowed.contains(var)) {
          String message =
              "Mẫu câu giải thích dùng biến " + var + " không có trong ngữ cảnh xếp loại.";
          throw new BusinessException(
              "POLICY-422-TEMPLATE-VAR",
              message,
              List.of(field(t.getKey(), message, "BR-15-06")),
              "MSG-15-05");
        }
      }
    }
  }

  /** {@code rules_json} lưu dạng {@code {"inputs": [...], "rules": [...]}} theo hợp đồng. */
  private String rulesJson(DecisionTableSpec spec) {
    ObjectNode root = json.createObjectNode();
    ArrayNode inputs = root.putArray("inputs");
    for (Column c : spec.columns()) {
      ObjectNode n = inputs.addObject().put("name", c.name()).put("type", c.type());
      if (c.label() != null) {
        n.put("label", c.label());
      }
    }
    ArrayNode rules = root.putArray("rules");
    for (Row r : spec.rows()) {
      ObjectNode n = rules.addObject();
      ObjectNode cells = n.putObject("inputs");
      spec.columns()
          .forEach(
              c -> {
                String v = r.inputs().get(c.name());
                if (v != null && !v.isBlank() && !v.strip().equals("-")) {
                  cells.put(c.name(), v.strip());
                }
              });
      n.put("ratingLevel", r.ratingLevel())
          .put("clause", r.clause())
          .put("explanation", r.explanation());
    }
    return write(root);
  }

  TableView view(StoredTable stored) {
    DecisionTable t = stored.table();
    List<Column> columns = new ArrayList<>();
    List<Row> rows = new ArrayList<>();
    JsonNode root = tree(t.rulesJson());
    for (JsonNode c : root.path("inputs")) {
      columns.add(
          new Column(
              c.path("name").asText(),
              c.hasNonNull("label") ? c.get("label").asText() : null,
              c.path("type").asText("number")));
    }
    JsonNode rules = root.isArray() ? root : root.path("rules");
    for (JsonNode r : rules) {
      Map<String, String> cells = new LinkedHashMap<>();
      r.path("inputs").fields().forEachRemaining(e -> cells.put(e.getKey(), e.getValue().asText()));
      rows.add(
          new Row(
              cells,
              r.path("ratingLevel").asText(r.path("then").asText("")),
              r.path("clause").asText(""),
              r.path("explanation").asText("")));
    }
    Map<String, String> templates = new LinkedHashMap<>();
    tree(t.explanationTemplatesJson())
        .fields()
        .forEachRemaining(e -> templates.put(e.getKey(), e.getValue().asText()));
    return new TableView(stored, new DecisionTableSpec(t.hitPolicy(), columns, rows), templates);
  }

  private JsonNode tree(String value) {
    try {
      return value == null ? json.createObjectNode() : json.readTree(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Nội dung JSON của bảng quyết định không đọc được", e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> read(String value) {
    try {
      return value == null ? Map.of() : json.readValue(value, Map.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Manifest của phiên bản không đọc được", e);
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] sha256(String xml) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(xml.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static ProblemFieldError field(String field, String message, String rule) {
    return new ProblemFieldError(field, message, rule);
  }
}
