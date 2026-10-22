package vn.viettel.okrkpi.config.domain;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Một component SDUI được phép trong một phiên bản registry ({@code config.component_registry},
 * CORE-08-09, ADR-05 lớp 2).
 *
 * @param propsSchemaJson JSON Schema của thuộc tính, luôn có {@code additionalProperties: false}
 * @param allowedClassification mức phân loại dữ liệu cao nhất component được hiển thị
 * @param container được chứa nút con hay không
 * @param since phiên bản registry phía web đưa component vào, {@code null} nếu không rõ
 * @param mobile hỗ trợ trên ứng dụng di động
 * @param plugin plugin cung cấp widget, {@code null} với component của nền tảng
 */
public record UiComponent(
    String componentKey,
    String registryVersion,
    String propsSchemaJson,
    String allowedClassification,
    Status status,
    boolean container,
    String since,
    boolean mobile,
    String plugin) {

  /** Tên hợp lệ: {@code vt.widget.*}, {@code vt.layout.*}, {@code vt.input.*}. */
  public static final Pattern KEY =
      Pattern.compile("^vt\\.(widget|layout|input)\\.[a-zA-Z][a-zA-Z0-9-]{0,48}$");

  public static final Set<String> CLASSIFICATIONS = Set.of("C0", "C1", "C2", "C3");

  /** Trạng thái trong registry. */
  public enum Status {
    HIEU_LUC,
    NGUNG
  }

  public UiComponent {
    Objects.requireNonNull(componentKey, "componentKey");
    Objects.requireNonNull(registryVersion, "registryVersion");
    Objects.requireNonNull(propsSchemaJson, "propsSchemaJson");
    Objects.requireNonNull(status, "status");
    if (!KEY.matcher(componentKey).matches()) {
      throw new IllegalArgumentException("Tên component không hợp lệ: " + componentKey);
    }
    if (!RegistryVersion.VERSION.matcher(registryVersion).matches()) {
      throw new IllegalArgumentException("Phiên bản registry không hợp lệ: " + registryVersion);
    }
    if (!CLASSIFICATIONS.contains(allowedClassification)) {
      throw new IllegalArgumentException("Mức phân loại không hợp lệ: " + allowedClassification);
    }
  }

  /** Component là widget (được đếm vào giới hạn 12 widget mỗi bố cục). */
  public boolean widget() {
    return componentKey.startsWith("vt.widget.");
  }
}
