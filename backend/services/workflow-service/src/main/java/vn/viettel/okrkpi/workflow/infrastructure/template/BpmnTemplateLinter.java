package vn.viettel.okrkpi.workflow.infrastructure.template;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Linter "chống mạng nhện" cho mẫu luồng BPMN (TL-01 G2 "Quy tắc quản trị chống mạng nhện", BS-01):
 * kiểm tám quy tắc trên nội dung BPMN, khai báo đi kèm và ảnh chụp engine. Chạy trong {@code mvn
 * verify} (kiểm thử {@code TemplateLinterTest}) và khi triển khai mẫu lúc khởi động; vi phạm làm
 * việc đăng ký mẫu thất bại.
 *
 * <p>Bộ phân tích XML tắt DOCTYPE và thực thể ngoài (chống XXE).
 */
public final class BpmnTemplateLinter {

  /** Không gian tên BPMN 2.0. */
  static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

  /** Không gian tên mở rộng Flowable. */
  static final String FLOWABLE_NS = "http://flowable.org/bpmn";

  /** Ngân sách biến (TL-01 G2 quy tắc 5: dưới 4 KB). */
  static final int MAX_VARIABLE_BYTES = 4096;

  /** Số phần tử tối đa của đa thể hiện song song (quy tắc 6). */
  static final int MAX_PARALLEL = 20;

  /** Số phiên bản chính tối đa còn tiến trình chạy (quy tắc 8). */
  static final int MAX_RUNNING_VERSIONS = 3;

  /** Số user task tối đa trên một nhánh (BS-01 "Giới hạn tải", quy tắc 9). */
  static final int MAX_USER_TASKS_PER_BRANCH = 12;

  /** Số phần tử tối đa của một đa thể hiện (BS-01 "Giới hạn tải", quy tắc 10). */
  static final int MAX_MULTI_INSTANCE = 50;

  private static final Set<String> FLOW_CONTAINERS =
      Set.of("process", "subProcess", "transaction", "adHocSubProcess");

  /** Loại service task được phép: external worker và DMN (tính toán thuần trong engine). */
  static final Set<String> ALLOWED_SERVICE_TYPES = Set.of("external-worker", "dmn");

  private static final Pattern UUID_LITERAL =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern EXPRESSION = Pattern.compile("[$#]\\{([^}]*)}");
  private static final Pattern TENANT_TOKEN = Pattern.compile("[A-Za-z_]*[Tt]enant[A-Za-z_]*");
  private static final Pattern DELEGATE_BEAN = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9]*)}");
  private static final Set<String> FORBIDDEN_TASKS =
      Set.of("sendTask", "scriptTask", "businessRuleTask", "receiveTask");
  private static final Set<String> THROW_EVENTS = Set.of("intermediateThrowEvent", "endEvent");

  private final Set<String> allowedDelegateBeans;
  private final Predicate<String> testClassExists;

  /**
   * @param allowedDelegateBeans bean listener tính toán thuần đã đăng ký (ví dụ {@code
   *     phanGiaiCapDuyet})
   * @param testClassExists kiểm lớp kiểm thử khai báo có trong bộ kiểm thử (CI); lúc chạy dịch vụ
   *     truyền hàm luôn đúng vì lớp kiểm thử không đóng gói
   */
  public BpmnTemplateLinter(Set<String> allowedDelegateBeans, Predicate<String> testClassExists) {
    this.allowedDelegateBeans = Set.copyOf(allowedDelegateBeans);
    this.testClassExists = Objects.requireNonNull(testClassExists, "testClassExists");
  }

  /**
   * Kiểm một bộ mẫu.
   *
   * @param templates các mẫu
   * @param decisionKeys khoá các bảng quyết định đang có
   * @param snapshot ảnh chụp engine cho quy tắc 8
   * @return danh sách vi phạm, rỗng khi đạt
   */
  public List<LintViolation> lint(
      Collection<TemplateSource> templates, Set<String> decisionKeys, RuntimeSnapshot snapshot) {
    List<LintViolation> violations = new ArrayList<>();
    Map<String, Parsed> parsed = new HashMap<>();
    for (TemplateSource source : templates) {
      Parsed p = parse(source, violations);
      if (p != null) {
        parsed.put(p.processId(), p);
      }
    }
    for (Parsed p : parsed.values()) {
      checkTenant(p, violations);
      checkCallActivities(p, parsed, violations);
      checkCrossTenant(p, violations);
      checkMachineTasks(p, violations);
      checkVariables(p, violations);
      checkParallel(p, violations);
      checkGovernance(p, decisionKeys, violations);
      checkVersions(p, snapshot, violations);
      checkUserTasksPerBranch(p, violations);
      checkMultiInstanceSize(p, violations);
    }
    return violations;
  }

  /**
   * Kiểm một mẫu soạn trên SCR-1505 (BS-01 "Kiểm tra khi soạn"): mẫu chưa có tệp khai báo, bộ kiểm
   * thử nằm trong repo mẫu luồng và chạy ở engine thử, nên bỏ phần của quy tắc 5, 7 dựa vào khai
   * báo (vẫn cấm data object) và quy tắc 8 (kiểm khi triển khai lên cell); mã process phải trùng mã
   * mẫu.
   *
   * @param templateCode mã mẫu
   * @param bpmnXml nội dung BPMN
   * @return danh sách vi phạm, rỗng khi đạt
   */
  public List<LintViolation> lintAuthored(String templateCode, String bpmnXml) {
    List<LintViolation> violations = new ArrayList<>();
    Parsed p =
        parse(new TemplateSource(templateCode + ".bpmn20.xml", bpmnXml, null, null), violations);
    if (p == null) {
      return violations;
    }
    if (!p.processId().equals(templateCode)) {
      violations.add(violation(LintRule.R1_KHONG_GAN_TENANT, p, "Mã process khác mã mẫu"));
    }
    checkTenant(p, violations);
    for (Element call : descendants(p.process(), "callActivity")) {
      String called = call.getAttribute("calledElement");
      if (called.isBlank() || called.contains("${") || called.contains("#{")) {
        violations.add(
            violation(
                LintRule.R2_MOT_TANG_CALL_ACTIVITY,
                p,
                "Call activity " + id(call) + " phải gọi mẫu cố định"));
      }
    }
    checkCrossTenant(p, violations);
    checkMachineTasks(p, violations);
    if (!descendants(p.process(), "dataObject").isEmpty()) {
      violations.add(violation(LintRule.R5_BIEN_DUOI_4KB, p, "Không dùng data object làm biến"));
    }
    checkParallel(p, violations);
    checkUserTasksPerBranch(p, violations);
    checkMultiInstanceSize(p, violations);
    return violations;
  }

  private Parsed parse(TemplateSource source, List<LintViolation> violations) {
    Document doc;
    try {
      doc = builder().parse(new InputSource(new StringReader(source.bpmnXml())));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      violations.add(
          new LintViolation(
              LintRule.R7_KIEM_THU_MO_TA_CHU_SO_HUU,
              source.resourceName(),
              "Không đọc được BPMN: " + e.getClass().getSimpleName()));
      return null;
    }
    List<Element> processes = children(doc.getDocumentElement(), "process");
    if (processes.size() != 1) {
      violations.add(
          new LintViolation(
              LintRule.R1_KHONG_GAN_TENANT,
              source.resourceName(),
              "Mỗi tệp mẫu chứa đúng một process"));
      return null;
    }
    Element process = processes.get(0);
    return new Parsed(process.getAttribute("id"), source, doc.getDocumentElement(), process);
  }

  private static DocumentBuilder builder() throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory.newDocumentBuilder();
  }

  // Quy tắc 1: không gắn tenant, không gắn cứng mã định danh.
  private static void checkTenant(Parsed p, List<LintViolation> out) {
    String tenant = p.source().deploymentTenantId();
    if (tenant != null && !tenant.isBlank()) {
      out.add(violation(LintRule.R1_KHONG_GAN_TENANT, p, "Lần triển khai gắn tenant"));
    }
    if (p.descriptor() != null && !p.processId().equals(p.descriptor().templateCode())) {
      out.add(violation(LintRule.R1_KHONG_GAN_TENANT, p, "Mã process khác mã mẫu trong khai báo"));
    }
    for (Element e : descendants(p.definitions())) {
      NamedNodeMap attrs = e.getAttributes();
      for (int i = 0; i < attrs.getLength(); i++) {
        Attr a = (Attr) attrs.item(i);
        String name = a.getLocalName() == null ? a.getName() : a.getLocalName();
        // fallbackToDefaultTenant chỉ cho phép dùng định nghĩa chung, không gắn tenant.
        if (name.toLowerCase(Locale.ROOT).contains("tenant")
            && !"fallbackToDefaultTenant".equals(name)) {
          out.add(
              violation(
                  LintRule.R1_KHONG_GAN_TENANT, p, "Thuộc tính " + a.getName() + " gắn tenant"));
        }
        if (UUID_LITERAL.matcher(a.getValue()).find()) {
          out.add(
              violation(
                  LintRule.R1_KHONG_GAN_TENANT,
                  p,
                  "Phần tử " + id(e) + " gắn cứng mã định danh ở " + a.getName()));
        }
      }
      if (hasOwnText(e) && UUID_LITERAL.matcher(ownText(e)).find()) {
        out.add(
            violation(
                LintRule.R1_KHONG_GAN_TENANT, p, "Phần tử " + id(e) + " gắn cứng mã định danh"));
      }
    }
  }

  // Quy tắc 2: tối đa một tầng call activity.
  private static void checkCallActivities(
      Parsed p, Map<String, Parsed> all, List<LintViolation> out) {
    for (Element call : descendants(p.process(), "callActivity")) {
      String called = call.getAttribute("calledElement");
      if (called.isBlank() || called.contains("${") || called.contains("#{")) {
        out.add(
            violation(
                LintRule.R2_MOT_TANG_CALL_ACTIVITY,
                p,
                "Call activity " + id(call) + " phải gọi mẫu cố định"));
        continue;
      }
      Parsed child = all.get(called);
      if (child != null && !descendants(child.process(), "callActivity").isEmpty()) {
        out.add(
            violation(
                LintRule.R2_MOT_TANG_CALL_ACTIVITY,
                p,
                "Call activity " + id(call) + " gọi mẫu " + called + " có call activity tiếp"));
      }
    }
  }

  // Quy tắc 3: không signal, không message phát ra, không biểu thức tham chiếu tenant khác.
  private static void checkCrossTenant(Parsed p, List<LintViolation> out) {
    if (!descendants(p.definitions(), "signal").isEmpty()
        || !descendants(p.definitions(), "signalEventDefinition").isEmpty()) {
      out.add(violation(LintRule.R3_KHONG_CHEO_TENANT, p, "Dùng signal phát rộng"));
    }
    for (Element message : descendants(p.process(), "messageEventDefinition")) {
      Node parent = message.getParentNode();
      if (parent instanceof Element owner && THROW_EVENTS.contains(owner.getLocalName())) {
        out.add(
            violation(
                LintRule.R3_KHONG_CHEO_TENANT,
                p,
                "Sự kiện " + id(owner) + " phát message sang tiến trình khác"));
      }
    }
    for (String expression : expressions(p.process())) {
      Matcher m = TENANT_TOKEN.matcher(expression);
      while (m.find()) {
        if (!"tenantId".equals(m.group())) {
          out.add(
              violation(
                  LintRule.R3_KHONG_CHEO_TENANT,
                  p,
                  "Biểu thức tham chiếu tenant khác: " + m.group()));
        }
      }
    }
  }

  // Quy tắc 4: tác vụ máy là external worker, DMN, hoặc listener tính toán thuần đã đăng ký.
  private void checkMachineTasks(Parsed p, List<LintViolation> out) {
    for (Element task : descendants(p.process(), "serviceTask")) {
      String type = task.getAttributeNS(FLOWABLE_NS, "type");
      if (!ALLOWED_SERVICE_TYPES.contains(type)) {
        out.add(
            violation(
                LintRule.R4_KHONG_GOI_DONG_BO,
                p,
                "Service task " + id(task) + " không phải external worker"));
      }
    }
    for (String name : FORBIDDEN_TASKS) {
      for (Element task : descendants(p.process(), name)) {
        out.add(
            violation(
                LintRule.R4_KHONG_GOI_DONG_BO, p, "Không dùng " + name + " (" + id(task) + ")"));
      }
    }
    List<Element> listeners = new ArrayList<>(descendants(p.process(), "executionListener"));
    listeners.addAll(descendants(p.process(), "taskListener"));
    for (Element listener : listeners) {
      Matcher bean = DELEGATE_BEAN.matcher(listener.getAttribute("delegateExpression"));
      boolean allowed =
          listener.getAttribute("class").isBlank()
              && listener.getAttribute("expression").isBlank()
              && bean.matches()
              && allowedDelegateBeans.contains(bean.group(1));
      if (!allowed) {
        out.add(
            violation(
                LintRule.R4_KHONG_GOI_DONG_BO,
                p,
                "Listener ở "
                    + id((Element) listener.getParentNode().getParentNode())
                    + " không thuộc danh sách tính toán thuần"));
      }
    }
  }

  // Quy tắc 5: ngân sách biến dưới 4 KB, không data object.
  private static void checkVariables(Parsed p, List<LintViolation> out) {
    if (!descendants(p.process(), "dataObject").isEmpty()) {
      out.add(violation(LintRule.R5_BIEN_DUOI_4KB, p, "Không dùng data object làm biến"));
    }
    TemplateDescriptor d = p.descriptor();
    if (d == null || d.variableBudget().isEmpty()) {
      out.add(violation(LintRule.R5_BIEN_DUOI_4KB, p, "Thiếu khai báo ngân sách biến"));
    } else if (d.totalVariableBytes() >= MAX_VARIABLE_BYTES) {
      out.add(
          violation(
              LintRule.R5_BIEN_DUOI_4KB,
              p,
              "Ngân sách biến " + d.totalVariableBytes() + " byte, vượt 4 KB"));
    }
  }

  // Quy tắc 6: đa thể hiện song song tối đa 20 phần tử.
  private static void checkParallel(Parsed p, List<LintViolation> out) {
    for (Element mi : descendants(p.process(), "multiInstanceLoopCharacteristics")) {
      if ("true".equals(mi.getAttribute("isSequential"))) {
        continue;
      }
      String owner = id((Element) mi.getParentNode());
      Integer limit = literalCardinality(mi);
      if (limit == null && p.descriptor() != null) {
        limit = p.descriptor().parallelLimits().get(owner);
      }
      if (limit == null || limit > MAX_PARALLEL) {
        out.add(
            violation(
                LintRule.R6_SONG_SONG_TOI_DA_20,
                p,
                "Đa thể hiện song song " + owner + " không giới hạn tối đa 20 phần tử"));
      }
    }
  }

  // Quy tắc 7: kiểm thử luồng, kiểm thử DMN, mô tả, người sở hữu.
  private void checkGovernance(Parsed p, Set<String> decisionKeys, List<LintViolation> out) {
    TemplateDescriptor d = p.descriptor();
    if (d == null) {
      out.add(violation(LintRule.R7_KIEM_THU_MO_TA_CHU_SO_HUU, p, "Thiếu tệp khai báo mẫu"));
      return;
    }
    if (blank(d.description()) || blank(d.owner())) {
      out.add(violation(LintRule.R7_KIEM_THU_MO_TA_CHU_SO_HUU, p, "Thiếu mô tả hoặc người sở hữu"));
    }
    if (d.flowTests().isEmpty()) {
      out.add(violation(LintRule.R7_KIEM_THU_MO_TA_CHU_SO_HUU, p, "Thiếu kiểm thử Flowable"));
    }
    Set<String> used = referencedDecisions(p.process());
    if (!used.isEmpty() && d.decisionTests().isEmpty()) {
      out.add(violation(LintRule.R7_KIEM_THU_MO_TA_CHU_SO_HUU, p, "Thiếu kiểm thử DMN"));
    }
    for (String key : used) {
      if (!decisionKeys.contains(key) || !d.decisionTables().contains(key)) {
        out.add(
            violation(
                LintRule.R7_KIEM_THU_MO_TA_CHU_SO_HUU,
                p,
                "Bảng quyết định " + key + " chưa có hoặc chưa khai báo"));
      }
    }
    List<String> tests = new ArrayList<>(d.flowTests());
    tests.addAll(d.decisionTests());
    for (String test : tests) {
      if (!testClassExists.test(test)) {
        out.add(
            violation(
                LintRule.R7_KIEM_THU_MO_TA_CHU_SO_HUU, p, "Không tìm thấy lớp kiểm thử " + test));
      }
    }
  }

  // Quy tắc 8: tối đa ba phiên bản còn chạy, tính cả phiên bản sắp phát hành.
  private static void checkVersions(Parsed p, RuntimeSnapshot snapshot, List<LintViolation> out) {
    int versions = snapshot.versionsAfterDeploy(p.processId());
    if (versions > MAX_RUNNING_VERSIONS) {
      out.add(
          violation(
              LintRule.R8_TOI_DA_3_PHIEN_BAN,
              p,
              versions + " phiên bản còn tiến trình chạy sau khi phát hành"));
    }
  }

  // Quy tắc 9: mỗi nhánh (đường đi theo sequence flow, bỏ cạnh quay lui) tối đa 12 user task; tiến
  // trình con tính bằng nhánh dài nhất bên trong nó.
  private static void checkUserTasksPerBranch(Parsed p, List<LintViolation> out) {
    int longest = longestUserTaskPath(p.process());
    if (longest > MAX_USER_TASKS_PER_BRANCH) {
      out.add(
          violation(
              LintRule.R9_TOI_DA_12_USER_TASK_MOI_NHANH,
              p,
              "Một nhánh có " + longest + " user task, vượt 12"));
    }
  }

  static int longestUserTaskPath(Element container) {
    Map<String, Element> nodes = new HashMap<>();
    Map<String, List<String>> edges = new HashMap<>();
    Set<String> targets = new HashSet<>();
    NodeList children = container.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (!(children.item(i) instanceof Element e)) {
        continue;
      }
      if ("sequenceFlow".equals(e.getLocalName())) {
        edges
            .computeIfAbsent(e.getAttribute("sourceRef"), k -> new ArrayList<>())
            .add(e.getAttribute("targetRef"));
        targets.add(e.getAttribute("targetRef"));
      } else if (!e.getAttribute("id").isBlank()) {
        nodes.put(e.getAttribute("id"), e);
      }
    }
    Map<String, Integer> weight = new HashMap<>();
    for (Map.Entry<String, Element> n : nodes.entrySet()) {
      Element e = n.getValue();
      int w = 0;
      if ("userTask".equals(e.getLocalName())) {
        w = 1;
      } else if (FLOW_CONTAINERS.contains(e.getLocalName())) {
        w = longestUserTaskPath(e);
      }
      weight.put(n.getKey(), w);
    }
    // Điểm vào: nút không có cạnh tới (sự kiện bắt đầu, sự kiện biên gắn vào task).
    int best = 0;
    Map<String, Integer> memo = new HashMap<>();
    for (String id : nodes.keySet()) {
      if (!targets.contains(id)) {
        best = Math.max(best, longest(id, edges, weight, memo, new HashSet<>()));
      }
    }
    return best;
  }

  private static int longest(
      String node,
      Map<String, List<String>> edges,
      Map<String, Integer> weight,
      Map<String, Integer> memo,
      Set<String> onPath) {
    Integer known = memo.get(node);
    if (known != null) {
      return known;
    }
    onPath.add(node);
    int tail = 0;
    for (String next : edges.getOrDefault(node, List.of())) {
      if (!onPath.contains(next)) {
        tail = Math.max(tail, longest(next, edges, weight, memo, onPath));
      }
    }
    onPath.remove(node);
    int result = weight.getOrDefault(node, 0) + tail;
    memo.put(node, result);
    return result;
  }

  // Quy tắc 10: mỗi đa thể hiện tối đa 50 phần tử. Bản số cố định phải không quá 50; đa thể hiện
  // tuần tự theo tập tham số (ví dụ approvalLevels, tối đa số cấp của khai báo) được chấp nhận vì
  // số phần tử do tham số đã kiểm giới hạn quyết định (suy luận).
  private static void checkMultiInstanceSize(Parsed p, List<LintViolation> out) {
    for (Element mi : descendants(p.process(), "multiInstanceLoopCharacteristics")) {
      String owner = id((Element) mi.getParentNode());
      Integer limit = literalCardinality(mi);
      if (limit == null && p.descriptor() != null) {
        limit = p.descriptor().parallelLimits().get(owner);
      }
      if (limit != null && limit > MAX_MULTI_INSTANCE) {
        out.add(
            violation(
                LintRule.R10_DA_THE_HIEN_TOI_DA_50,
                p,
                "Đa thể hiện " + owner + " có " + limit + " phần tử, vượt 50"));
      }
    }
  }

  private static Set<String> referencedDecisions(Element process) {
    Set<String> keys = new HashSet<>();
    for (Element field : descendants(process, "field")) {
      if ("decisionTableReferenceKey".equals(field.getAttribute("name"))) {
        String value = field.getAttribute("stringValue");
        if (value.isBlank()) {
          value = field.getTextContent() == null ? "" : field.getTextContent().strip();
        }
        keys.add(value);
      }
    }
    return keys;
  }

  private static Integer literalCardinality(Element mi) {
    for (Element c : descendants(mi, "loopCardinality")) {
      try {
        return Integer.valueOf(c.getTextContent().strip());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private static List<String> expressions(Element root) {
    List<String> result = new ArrayList<>();
    for (Element e : descendants(root)) {
      NamedNodeMap attrs = e.getAttributes();
      for (int i = 0; i < attrs.getLength(); i++) {
        collectExpressions(attrs.item(i).getNodeValue(), result);
      }
      if (hasOwnText(e)) {
        collectExpressions(ownText(e), result);
      }
    }
    return result;
  }

  private static void collectExpressions(String text, List<String> out) {
    Matcher m = EXPRESSION.matcher(text);
    while (m.find()) {
      out.add(m.group(1));
    }
  }

  private static List<Element> children(Element parent, String localName) {
    List<Element> result = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      if (nodes.item(i) instanceof Element e && localName.equals(e.getLocalName())) {
        result.add(e);
      }
    }
    return result;
  }

  private static List<Element> descendants(Element root) {
    List<Element> result = new ArrayList<>();
    NodeList nodes = root.getElementsByTagName("*");
    for (int i = 0; i < nodes.getLength(); i++) {
      result.add((Element) nodes.item(i));
    }
    return result;
  }

  private static List<Element> descendants(Element root, String localName) {
    List<Element> result = new ArrayList<>();
    for (Element e : descendants(root)) {
      if (localName.equals(e.getLocalName())) {
        result.add(e);
      }
    }
    return result;
  }

  private static boolean hasOwnText(Element e) {
    return !ownText(e).isBlank();
  }

  private static String ownText(Element e) {
    StringBuilder text = new StringBuilder();
    NodeList nodes = e.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node n = nodes.item(i);
      if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
        text.append(n.getNodeValue());
      }
    }
    return text.toString();
  }

  private static String id(Element e) {
    String id = e.getAttribute("id");
    return id.isBlank() ? e.getLocalName() : id;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static LintViolation violation(LintRule rule, Parsed p, String detail) {
    return new LintViolation(rule, p.processId(), detail);
  }

  private record Parsed(
      String processId, TemplateSource source, Element definitions, Element process) {

    TemplateDescriptor descriptor() {
      return source.descriptor();
    }
  }
}
