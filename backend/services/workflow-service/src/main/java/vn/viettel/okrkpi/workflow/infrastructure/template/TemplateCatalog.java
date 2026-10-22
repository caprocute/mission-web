package vn.viettel.okrkpi.workflow.infrastructure.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Danh mục mẫu luồng đóng gói cùng dịch vụ: {@code processes/*.bpmn20.xml} kèm {@code
 * processes/<mã>.mau.json} và các bảng quyết định {@code dmn/*.dmn}. Repo này đóng vai "repo mẫu
 * luồng" của TL-01 G2 cho tới khi saga phát hành mẫu (DV-06-09) có mặt.
 */
public final class TemplateCatalog {

  /** Vị trí mặc định của mẫu BPMN. */
  public static final String PROCESSES = "classpath*:processes/*.bpmn20.xml";

  /** Vị trí mặc định của bảng quyết định. */
  public static final String DECISIONS = "classpath*:dmn/*.dmn";

  private static final Pattern DECISION_ID = Pattern.compile("<decision\\s+[^>]*id=\"([^\"]+)\"");
  private static final String BPMN_SUFFIX = ".bpmn20.xml";
  private static final String DESCRIPTOR_SUFFIX = ".mau.json";

  private final List<TemplateSource> templates;
  private final Map<String, String> bpmn;
  private final Map<String, String> decisions;
  private final Map<String, String> decisionKeys;

  private TemplateCatalog(
      List<TemplateSource> templates,
      Map<String, String> bpmn,
      Map<String, String> decisions,
      Map<String, String> decisionKeys) {
    this.templates = List.copyOf(templates);
    this.bpmn = Map.copyOf(bpmn);
    this.decisions = Map.copyOf(decisions);
    this.decisionKeys = Map.copyOf(decisionKeys);
  }

  /** Đọc danh mục mặc định trên classpath. */
  public static TemplateCatalog load(ObjectMapper json) {
    return load(json, PROCESSES, DECISIONS);
  }

  /** Đọc danh mục từ hai mẫu đường dẫn (kiểm thử dùng thư mục fixture). */
  public static TemplateCatalog load(ObjectMapper json, String processes, String dmn) {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    try {
      List<TemplateSource> templates = new ArrayList<>();
      Map<String, String> bpmn = new LinkedHashMap<>();
      for (Resource r : resolver.getResources(processes)) {
        String name = r.getFilename();
        String xml = read(r);
        bpmn.put("processes/" + name, xml);
        Resource meta = r.createRelative(name.replace(BPMN_SUFFIX, DESCRIPTOR_SUFFIX));
        TemplateDescriptor descriptor = meta.exists() ? descriptor(json, read(meta)) : null;
        templates.add(new TemplateSource(name, xml, descriptor, null));
      }
      Map<String, String> decisions = new LinkedHashMap<>();
      Map<String, String> keys = new HashMap<>();
      for (Resource r : resolver.getResources(dmn)) {
        String xml = read(r);
        decisions.put("dmn/" + r.getFilename(), xml);
        Matcher m = DECISION_ID.matcher(xml);
        while (m.find()) {
          keys.put(m.group(1), r.getFilename());
        }
      }
      return new TemplateCatalog(templates, bpmn, decisions, keys);
    } catch (IOException e) {
      throw new UncheckedIOException("Không đọc được danh mục mẫu luồng", e);
    }
  }

  static TemplateDescriptor descriptor(ObjectMapper json, String raw) throws IOException {
    JsonNode n = json.readTree(raw);
    return new TemplateDescriptor(
        n.path("templateCode").asText(null),
        n.path("phienBanChinh").asInt(1),
        n.path("moTa").asText(null),
        n.path("chuSoHuu").asText(null),
        n.path("truyVet").asText(null),
        strings(n.path("bangQuyetDinh")),
        strings(n.path("kiemThu")),
        strings(n.path("kiemThuDmn")),
        ints(n.path("bien")),
        ints(n.path("songSongToiDa")));
  }

  private static List<String> strings(JsonNode array) {
    List<String> result = new ArrayList<>();
    array.forEach(e -> result.add(e.asText()));
    return result;
  }

  private static Map<String, Integer> ints(JsonNode object) {
    Map<String, Integer> result = new LinkedHashMap<>();
    object.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asInt()));
    return result;
  }

  private static String read(Resource r) throws IOException {
    try (InputStream in = r.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Các mẫu kèm khai báo. */
  public List<TemplateSource> templates() {
    return templates;
  }

  /** Nội dung BPMN theo tên tài nguyên triển khai. */
  public Map<String, String> bpmnResources() {
    return bpmn;
  }

  /** Nội dung DMN theo tên tài nguyên triển khai. */
  public Map<String, String> decisionResources() {
    return decisions;
  }

  /** Khoá các bảng quyết định. */
  public Set<String> decisionKeys() {
    return decisionKeys.keySet();
  }
}
