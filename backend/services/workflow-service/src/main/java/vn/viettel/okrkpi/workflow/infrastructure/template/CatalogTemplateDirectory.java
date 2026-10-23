package vn.viettel.okrkpi.workflow.infrastructure.template;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.port.TemplateDirectory;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalTemplate;
import vn.viettel.okrkpi.workflow.domain.binding.TemplateInfo;
import vn.viettel.okrkpi.workflow.domain.binding.TemplateParamRules;

/**
 * {@link TemplateDirectory} trên danh mục mẫu đóng gói cùng dịch vụ (repo mẫu luồng của TL-01 G2):
 * mọi mẫu kèm khai báo đều đã qua linter và được triển khai lúc khởi động nên là {@code HIEU_LUC};
 * phiên bản mẫu là {@code phienBanChinh} của khai báo, loại quy trình lấy từ {@link
 * ApprovalTemplate}. Khi DV-06-09 đưa mẫu nội bộ vào {@code wf_template}, danh mục này đọc thêm
 * bảng đó.
 */
@Component
public class CatalogTemplateDirectory implements TemplateDirectory {

  private final List<TemplateInfo> published;

  public CatalogTemplateDirectory(TemplateCatalog catalog) {
    List<TemplateInfo> items = new ArrayList<>();
    for (TemplateSource source : catalog.templates()) {
      TemplateDescriptor d = source.descriptor();
      if (d == null || d.templateCode() == null) {
        continue;
      }
      ApprovalTemplate.byCode(d.templateCode())
          .ifPresent(
              t ->
                  items.add(
                      new TemplateInfo(
                          d.templateCode(),
                          name(d),
                          Math.max(1, d.majorVersion()),
                          t.processKind(),
                          rules(d.params()))));
    }
    items.sort(Comparator.comparing(TemplateInfo::key).thenComparing(TemplateInfo::versionNo));
    this.published = List.copyOf(items);
  }

  @Override
  public List<TemplateInfo> published() {
    return published;
  }

  @Override
  public Optional<TemplateInfo> find(String key, int versionNo) {
    return published.stream()
        .filter(t -> t.key().equals(key) && t.versionNo() == versionNo)
        .findFirst();
  }

  private static String name(TemplateDescriptor d) {
    Object name = d.params().get("ten");
    return name == null ? d.templateCode() : name.toString();
  }

  @SuppressWarnings("unchecked")
  static TemplateParamRules rules(Map<String, Object> p) {
    if (p.isEmpty()) {
      return TemplateParamRules.defaults();
    }
    List<Map<String, Object>> levels = new ArrayList<>();
    if (p.get("capMacDinh") instanceof List<?> list) {
      list.stream().filter(Map.class::isInstance).forEach(m -> levels.add((Map<String, Object>) m));
    }
    return new TemplateParamRules(
        number(p.get("soCapToiThieu"), TemplateParamRules.MIN_LEVELS),
        number(p.get("soCapToiDa"), TemplateParamRules.MAX_LEVELS),
        strings(p.get("kySoBatBuoc")),
        strings(p.get("boQuaDuoc")),
        levels);
  }

  private static int number(Object value, int fallback) {
    return value instanceof Number n ? n.intValue() : fallback;
  }

  private static Set<String> strings(Object value) {
    Set<String> out = new HashSet<>();
    if (value instanceof List<?> list) {
      list.forEach(v -> out.add(String.valueOf(v)));
    }
    return out;
  }
}
