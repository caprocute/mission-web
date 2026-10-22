package vn.viettel.okrkpi.config.domain;

import java.util.List;
import java.util.Objects;

/**
 * Một bản dựng plugin chuyên ngành trong danh mục đã duyệt của nền tảng (CORE-08-15, BR-18-12,
 * ADR-05 lớp 4, ADR-16), khai báo bằng tệp seed {@code data/seed/plugins/catalog.json}.
 *
 * @param integrity mã toàn vẹn SRI SHA-384 của bản dựng
 * @param signature chữ ký cosign (ECDSA P-256 SHA-256, DER base64) trên chuỗi {@code integrity}
 * @param publicKey khoá công khai cosign dạng PEM
 * @param scanStatus kết quả quét bảo mật, chỉ {@code DAT} được dùng
 * @param widgets widget SDUI plugin cung cấp, {@link UiComponent#plugin()} là {@code name}
 */
public record PluginRelease(
    String name,
    String displayName,
    String version,
    String remoteEntry,
    String integrity,
    String signature,
    String publicKey,
    String scanStatus,
    List<Widget> widgets) {

  /** Kết quả quét đạt. */
  public static final String SCAN_PASSED = "DAT";

  public PluginRelease {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(version, "version");
    displayName = displayName == null || displayName.isBlank() ? name : displayName;
    widgets = widgets == null ? List.of() : List.copyOf(widgets);
  }

  /**
   * @param registry phiên bản registry áp dụng, {@code null} là mọi phiên bản
   */
  public record Widget(
      String name,
      String propsSchemaJson,
      String allowedClassification,
      String since,
      boolean mobile,
      String registry) {}

  public UiComponent component(String registryVersion, Widget w) {
    return new UiComponent(
        w.name(),
        registryVersion,
        w.propsSchemaJson(),
        w.allowedClassification() == null ? "C1" : w.allowedClassification(),
        UiComponent.Status.HIEU_LUC,
        false,
        w.since(),
        w.mobile(),
        name);
  }
}
