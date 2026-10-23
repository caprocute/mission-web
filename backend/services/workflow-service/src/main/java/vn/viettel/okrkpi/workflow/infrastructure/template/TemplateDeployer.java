package vn.viettel.okrkpi.workflow.infrastructure.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.repository.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Triển khai mẫu luồng dùng chung khi khởi động (thay cho tự triển khai của Flowable Spring Boot,
 * đã tắt bằng {@code flowable.check-process-definitions=false}): chạy linter tám quy tắc, rồi triển
 * khai BPMN cùng DMN trong một lần triển khai không gắn tenant, có lọc trùng nên khởi động lại
 * không sinh phiên bản mới. Linter đỏ thì dịch vụ không lên (vi phạm làm việc đăng ký mẫu thất
 * bại).
 */
@Component
@ConditionalOnProperty(
    name = "mission.workflow.templates.deploy-on-start",
    havingValue = "true",
    matchIfMissing = true)
public class TemplateDeployer implements ApplicationRunner {

  /** Tên lần triển khai (lọc trùng theo tên). */
  public static final String DEPLOYMENT_NAME = "mau-luong-duyet";

  /** Listener tính toán thuần được phép dùng trong mẫu. */
  public static final Set<String> PURE_DELEGATES = Set.of("phanGiaiCapDuyet", "lapLichBuoc");

  private static final Logger log = LoggerFactory.getLogger(TemplateDeployer.class);

  private final RepositoryService repository;
  private final RuntimeService runtime;
  private final ObjectMapper json;

  public TemplateDeployer(RepositoryService repository, RuntimeService runtime, ObjectMapper json) {
    this.repository = repository;
    this.runtime = runtime;
    this.json = json;
  }

  @Override
  public void run(ApplicationArguments args) {
    deploy(TemplateCatalog.load(json));
  }

  /** Kiểm và triển khai danh mục; trả mã lần triển khai. */
  public String deploy(TemplateCatalog catalog) {
    // Lớp kiểm thử không đóng gói: sự tồn tại của chúng được kiểm ở CI (TemplateLinterTest).
    BpmnTemplateLinter linter = new BpmnTemplateLinter(PURE_DELEGATES, test -> true);
    List<LintViolation> violations =
        linter.lint(catalog.templates(), catalog.decisionKeys(), snapshot(catalog));
    if (!violations.isEmpty()) {
      throw new IllegalStateException("Mẫu luồng vi phạm quy tắc quản trị: " + violations);
    }
    DeploymentBuilder builder =
        repository.createDeployment().name(DEPLOYMENT_NAME).enableDuplicateFiltering();
    catalog.bpmnResources().forEach((name, xml) -> builder.addBytes(name, bytes(xml)));
    catalog.decisionResources().forEach((name, xml) -> builder.addBytes(name, bytes(xml)));
    String id = builder.deploy().getId();
    log.info("Đã triển khai {} mẫu luồng dùng chung", catalog.bpmnResources().size());
    return id;
  }

  /** Ảnh chụp các phiên bản còn tiến trình chạy và các mẫu sắp có phiên bản mới (quy tắc 8). */
  RuntimeSnapshot snapshot(TemplateCatalog catalog) {
    Map<String, Set<Integer>> running = new HashMap<>();
    Set<String> changed = new HashSet<>();
    for (TemplateSource source : catalog.templates()) {
      if (source.descriptor() == null || source.descriptor().templateCode() == null) {
        continue;
      }
      String key = source.descriptor().templateCode();
      List<ProcessDefinition> definitions =
          repository
              .createProcessDefinitionQuery()
              .processDefinitionKey(key)
              .processDefinitionWithoutTenantId()
              .orderByProcessDefinitionVersion()
              .desc()
              .list();
      if (definitions.isEmpty() || !sameContent(definitions.get(0), source.bpmnXml())) {
        changed.add(key);
      }
      for (ProcessDefinition definition : definitions) {
        long count =
            runtime.createProcessInstanceQuery().processDefinitionId(definition.getId()).count();
        if (count > 0) {
          running.computeIfAbsent(key, k -> new HashSet<>()).add(definition.getVersion());
        }
      }
    }
    return new RuntimeSnapshot(running, changed);
  }

  private boolean sameContent(ProcessDefinition definition, String xml) {
    try (InputStream in =
        repository.getResourceAsStream(
            definition.getDeploymentId(), definition.getResourceName())) {
      return Arrays.equals(in.readAllBytes(), bytes(xml));
    } catch (IOException e) {
      return false;
    }
  }

  private static byte[] bytes(String xml) {
    return xml.getBytes(StandardCharsets.UTF_8);
  }
}
