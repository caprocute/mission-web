package vn.viettel.okrkpi.scoring.infrastructure.policy;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import org.flowable.dmn.api.DecisionExecutionAuditContainer;
import org.flowable.dmn.api.DmnDecision;
import org.flowable.dmn.api.DmnDeploymentBuilder;
import org.flowable.dmn.api.RuleExecutionAuditContainer;
import org.flowable.dmn.engine.DmnEngine;
import org.flowable.dmn.engine.DmnEngineConfiguration;
import org.springframework.beans.factory.DisposableBean;
import org.xml.sax.SAXException;
import vn.viettel.okrkpi.scoring.application.port.DecisionTables;
import vn.viettel.okrkpi.scoring.domain.DecisionTable;
import vn.viettel.okrkpi.scoring.domain.DecisionTableSource;
import vn.viettel.okrkpi.shared.error.BusinessException;

/**
 * Bảng DMN chạy trên Flowable DMN độc lập (TL-01 SVC-07: không cần bộ máy BPMN). Mỗi phiên bản
 * Policy Pack là một deployment trong kho H2 trong bộ nhớ, {@code tenantId} của deployment là băm
 * nội dung nên các phiên bản trùng mã quyết định không lẫn nhau; Flowable phân tích XML một lần và
 * giữ định nghĩa đã phân tích trong đệm của nó. Flowable 7.2 chỉ nhận không gian tên DMN 1.3, cú
 * pháp bảng giữa 1.3 và 1.4 như nhau nên không gian tên 1.4 của MOD-05 được đổi khi nạp (cùng cách
 * CORE-05-05 kiểm thử).
 */
public class FlowableDecisionTables implements DecisionTables, DisposableBean {

  static final String DMN14 = "https://www.omg.org/spec/DMN/20211108/MODEL/";
  static final String DMN13 = "https://www.omg.org/spec/DMN/20191111/MODEL/";
  static final String FEEL = "POLICY-422-FEEL";
  static final Pattern LEADING_COMMENTS =
      Pattern.compile("\\A(\\s*<\\?xml[^>]*\\?>)?(?:\\s*<!--.*?-->)*", Pattern.DOTALL);

  private final DmnEngine engine;

  public FlowableDecisionTables() {
    this.engine =
        DmnEngineConfiguration.createStandaloneInMemDmnEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:scoring-dmn-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1")
            .setDatabaseSchemaUpdate(DmnEngineConfiguration.DB_SCHEMA_UPDATE_CREATE_DROP)
            .buildDmnEngine();
  }

  @Override
  public Compiled compile(String contentHash, List<DecisionTableSource> tables) {
    Map<String, String> keys = new HashMap<>();
    Map<String, List<String>> inputs = new HashMap<>();
    Map<String, DecisionTable> structures = new HashMap<>();
    if (!tables.isEmpty()) {
      try {
        DmnDeploymentBuilder deployment =
            engine
                .getDmnRepositoryService()
                .createDeployment()
                .name("policy-" + contentHash)
                .tenantId(contentHash);
        for (DecisionTableSource t : tables) {
          deployment.addString(t.tableKey() + ".dmn", prepare(t.dmnXml()));
          inputs.put(t.tableKey(), FeelUnaryLists.inputs(t.dmnXml()));
          DecisionTable structure = structure(t);
          if (structure != null) {
            structures.put(t.tableKey(), structure);
          }
        }
        String id = deployment.deploy().getId();
        for (DmnDecision d :
            engine.getDmnRepositoryService().createDecisionQuery().deploymentId(id).list()) {
          String resource = d.getResourceName();
          keys.put(resource.substring(0, resource.length() - ".dmn".length()), d.getKey());
        }
      } catch (RuntimeException e) {
        throw new BusinessException(
            FEEL, "Bảng quyết định của phiên bản quy tắc không nạp được: " + e.getMessage());
      }
    }
    return new FlowableCompiled(
        contentHash, Map.copyOf(keys), Map.copyOf(inputs), Map.copyOf(structures));
  }

  private static DecisionTable structure(DecisionTableSource t) {
    try {
      return DmnStructure.parse(t.tableKey(), t.dmnXml());
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new BusinessException(FEEL, "Bảng quyết định " + t.tableKey() + " không đọc được");
    }
  }

  /**
   * Đổi ô danh sách FEEL sang biểu thức Flowable hiểu, đổi không gian tên 1.4 sang 1.3 và bỏ chú
   * thích trước phần tử gốc: Flowable xác định lược đồ kiểm tra theo không gian tên của sự kiện XML
   * đầu tiên sau khai báo.
   */
  static String prepare(String xml) {
    return LEADING_COMMENTS
        .matcher(FeelUnaryLists.rewrite(xml).replace(DMN14, DMN13))
        .replaceFirst("$1");
  }

  @Override
  public void destroy() {
    engine.close();
  }

  private final class FlowableCompiled implements Compiled {

    private final String tenant;
    private final Map<String, String> decisionKeys;
    private final Map<String, List<String>> inputs;
    private final Map<String, DecisionTable> structures;

    FlowableCompiled(
        String tenant,
        Map<String, String> decisionKeys,
        Map<String, List<String>> inputs,
        Map<String, DecisionTable> structures) {
      this.tenant = tenant;
      this.decisionKeys = decisionKeys;
      this.inputs = inputs;
      this.structures = structures;
    }

    @Override
    public Optional<DecisionTable> structure(String tableKey) {
      return Optional.ofNullable(structures.get(tableKey));
    }

    @Override
    public Hit decide(String tableKey, Map<String, Object> variables) {
      DecisionExecutionAuditContainer audit =
          engine
              .getDmnDecisionService()
              .createExecuteDecisionBuilder()
              .decisionKey(key(tableKey))
              .tenantId(tenant)
              .variables(variables)
              .executeWithAuditTrail();
      if (Boolean.TRUE.equals(audit.isFailed())) {
        throw new IllegalStateException("Bảng quyết định " + tableKey + " chạy lỗi");
      }
      int rule = -1;
      for (RuleExecutionAuditContainer r : audit.getRuleExecutions().values()) {
        if (Boolean.TRUE.equals(r.isValid()) && (rule < 0 || r.getRuleNumber() - 1 < rule)) {
          rule = r.getRuleNumber() - 1;
        }
      }
      List<Map<String, Object>> rows = audit.getDecisionResult();
      return new Hit(rule, rows == null || rows.isEmpty() ? Map.of() : rows.get(0));
    }

    private String key(String tableKey) {
      String key = decisionKeys.get(tableKey);
      if (key == null) {
        throw new IllegalArgumentException("Phiên bản không có bảng quyết định " + tableKey);
      }
      return key;
    }

    @Override
    public List<String> inputs(String tableKey) {
      return inputs.getOrDefault(tableKey, List.of());
    }

    @Override
    public Set<String> tableKeys() {
      return decisionKeys.keySet();
    }

    @Override
    public Map<String, Object> evaluate(String tableKey, Map<String, Object> variables) {
      String key = key(tableKey);
      List<Map<String, Object>> rows =
          engine
              .getDmnDecisionService()
              .createExecuteDecisionBuilder()
              .decisionKey(key)
              .tenantId(tenant)
              .variables(variables)
              .execute();
      return rows == null || rows.isEmpty() ? Map.of() : rows.get(0);
    }
  }
}
