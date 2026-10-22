package vn.viettel.okrkpi.config.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Một phiên bản biểu mẫu động {@code config.form_schema} theo {@code (tenant, form_key, version)}
 * (CORE-08-13, QT-18, SCR-1802): JSON Schema dữ liệu có {@code x-cel}, {@code x-classification},
 * cấu hình Formly, quy tắc CEL, Policy Pack gắn kèm và kết quả bộ test gần nhất. Chỉ sửa được khi
 * {@code NHAP}.
 */
public record FormSchema(
    UUID id,
    String formKey,
    String version,
    FormSchemaStatus status,
    String schemaJson,
    String uiJson,
    String celRulesJson,
    UUID policyPackVersionId,
    String lastTestRunJson,
    UUID createdBy,
    Instant createdAt,
    UUID updatedBy,
    Instant updatedAt,
    int rowVersion) {

  /** Khoá biểu mẫu theo hợp đồng {@code FormKeyPath}. */
  public static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

  /** Phiên bản: chuỗi ngắn không khoảng trắng. */
  public static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z.\\-]{0,31}$");

  public FormSchema {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(formKey, "formKey");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(schemaJson, "schemaJson");
    Objects.requireNonNull(uiJson, "uiJson");
    celRulesJson = celRulesJson == null ? "[]" : celRulesJson;
  }

  /** Phiên bản nháp mới. */
  public static FormSchema draft(
      UUID id,
      String formKey,
      String version,
      String schemaJson,
      String uiJson,
      String celRulesJson,
      UUID policyPackVersionId,
      UUID actor,
      Instant at) {
    return new FormSchema(
        id,
        formKey,
        version,
        FormSchemaStatus.NHAP,
        schemaJson,
        uiJson,
        celRulesJson,
        policyPackVersionId,
        null,
        actor,
        at,
        actor,
        at,
        0);
  }

  /**
   * Lưu nháp; kết quả test cũ giữ lại, bên gọi so băm phần tính điểm để biết còn hiệu lực không.
   */
  public FormSchema edit(
      String schema, String ui, String celRules, UUID policyPack, UUID actor, Instant at) {
    requireDraft();
    return new FormSchema(
        id,
        formKey,
        version,
        status,
        schema,
        ui,
        celRules,
        policyPack,
        lastTestRunJson,
        createdBy,
        createdAt,
        actor,
        at,
        rowVersion + 1);
  }

  /** Ghi kết quả bộ test (CORE-08-14). */
  public FormSchema tested(String testRunJson, UUID actor, Instant at) {
    return new FormSchema(
        id,
        formKey,
        version,
        status,
        schemaJson,
        uiJson,
        celRulesJson,
        policyPackVersionId,
        testRunJson,
        createdBy,
        createdAt,
        actor,
        at,
        rowVersion + 1);
  }

  /** Phát hành cùng UI Pack chứa nó: {@code NHAP → DA_PHAT_HANH}; đã phát hành thì giữ nguyên. */
  public FormSchema publish(UUID actor, Instant at) {
    if (status == FormSchemaStatus.DA_PHAT_HANH) {
      return this;
    }
    if (status != FormSchemaStatus.NHAP) {
      throw new IllegalStateException("Biểu mẫu đã ngừng không phát hành lại được");
    }
    return new FormSchema(
        id,
        formKey,
        version,
        FormSchemaStatus.DA_PHAT_HANH,
        schemaJson,
        uiJson,
        celRulesJson,
        policyPackVersionId,
        lastTestRunJson,
        createdBy,
        createdAt,
        actor,
        at,
        rowVersion + 1);
  }

  public boolean isDraft() {
    return status == FormSchemaStatus.NHAP;
  }

  private void requireDraft() {
    if (status != FormSchemaStatus.NHAP) {
      throw new IllegalStateException("Chỉ sửa được biểu mẫu nháp");
    }
  }
}
