package vn.viettel.okrkpi.config.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Nội dung soạn được của một phiên bản UI Pack, giữ ở dạng JSON đã chuẩn hoá để miền không phụ
 * thuộc thư viện JSON: token W3C (kể cả ghi đè chế độ cao tuổi trong {@code
 * $extensions.modes.elderly}), bố cục SDUI theo bề mặt, tài nguyên.
 *
 * @param tokensJson đối tượng JSON {@code config.ui_pack_version.tokens}
 * @param layoutsJson đối tượng JSON {@code layouts}
 * @param assetsJson mảng JSON {@code assets_json}
 * @param policyPackVersionId phiên bản Policy Pack đi kèm, có thể {@code null}
 * @param elderMode bật chế độ Đảng viên cao tuổi (BR-18-09)
 * @param fewSteps cờ "ít bước" ({@code flags.reducedSteps})
 * @param formsJson mảng JSON {@code forms_json}: biểu mẫu đã gắn {@code {formKey, version}}
 *     (CORE-08-13)
 */
public record UiPackContent(
    String tokensJson,
    String layoutsJson,
    String assetsJson,
    String componentRegistryVersion,
    UUID policyPackVersionId,
    boolean elderMode,
    boolean fewSteps,
    String formsJson) {

  public static final int REGISTRY_VERSION_MAX = 32;

  public UiPackContent {
    Objects.requireNonNull(tokensJson, "tokensJson");
    Objects.requireNonNull(layoutsJson, "layoutsJson");
    assetsJson = assetsJson == null ? "[]" : assetsJson;
    formsJson = formsJson == null ? "[]" : formsJson;
    Objects.requireNonNull(componentRegistryVersion, "componentRegistryVersion");
    if (componentRegistryVersion.isBlank()
        || componentRegistryVersion.length() > REGISTRY_VERSION_MAX) {
      throw new IllegalArgumentException("Phiên bản danh mục thành phần không hợp lệ");
    }
  }

  public UiPackContent(
      String tokensJson,
      String layoutsJson,
      String assetsJson,
      String componentRegistryVersion,
      UUID policyPackVersionId,
      boolean elderMode,
      boolean fewSteps) {
    this(
        tokensJson,
        layoutsJson,
        assetsJson,
        componentRegistryVersion,
        policyPackVersionId,
        elderMode,
        fewSteps,
        "[]");
  }

  /** Cùng nội dung, thay danh sách biểu mẫu đã gắn. */
  public UiPackContent withForms(String forms) {
    return new UiPackContent(
        tokensJson,
        layoutsJson,
        assetsJson,
        componentRegistryVersion,
        policyPackVersionId,
        elderMode,
        fewSteps,
        forms);
  }
}
