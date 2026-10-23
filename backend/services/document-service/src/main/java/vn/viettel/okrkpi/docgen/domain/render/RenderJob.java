package vn.viettel.okrkpi.docgen.domain.render;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Tác vụ sinh một biểu mẫu ({@code docgen.render_job}): {@code CHO} → {@code DANG_SINH} → {@code
 * XONG} hoặc {@code LOI}; lỗi tạm thời trả tác vụ về {@code CHO} cho tới khi hết số lần thử.
 *
 * <p>{@code inputSha256} là băm dữ liệu đã chuẩn hoá mà MOD-06 công bố khi yêu cầu sinh ({@code
 * dossier.form_instance.data_snapshot_hash}); worker từ chối sinh khi dữ liệu nhận được lệch băm.
 */
public final class RenderJob {

  /** Độ dài tối đa của cột {@code error}. */
  public static final int MAX_ERROR = 2000;

  private final UUID id;
  private final UUID tenantId;
  private final UUID templateId;
  private final UUID requestEventId;
  private final UUID requestId;
  private final UUID formInstanceId;
  private final byte[] inputSha256;
  private final UUID createdBy;
  private final Instant createdAt;
  private RenderJobStatus status;
  private int attempts;
  private String error;
  private Instant updatedAt;
  private final long rowVersion;
  private Provenance provenance = Provenance.NONE;

  /**
   * Nguồn gốc của tệp để đóng tem (DV-11-04, BR-11-03): gói hồ sơ và phiên bản Policy Pack gắn với
   * kỳ mà MOD-06 gửi trong lệnh sinh. Tác vụ tạo trước DV-11-04 không có, các trường là {@code
   * null}.
   */
  public record Provenance(UUID packageId, String policyPackCode, String policyPackVersion) {
    public static final Provenance NONE = new Provenance(null, null, null);
  }

  public RenderJob(
      UUID id,
      UUID tenantId,
      UUID templateId,
      UUID requestEventId,
      UUID requestId,
      UUID formInstanceId,
      byte[] inputSha256,
      RenderJobStatus status,
      int attempts,
      String error,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt,
      long rowVersion) {
    this.id = Objects.requireNonNull(id, "id");
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.templateId = Objects.requireNonNull(templateId, "templateId");
    this.requestEventId = Objects.requireNonNull(requestEventId, "requestEventId");
    this.requestId = Objects.requireNonNull(requestId, "requestId");
    this.formInstanceId = Objects.requireNonNull(formInstanceId, "formInstanceId");
    Objects.requireNonNull(inputSha256, "inputSha256");
    if (inputSha256.length != 32) {
      throw new IllegalArgumentException("Băm dữ liệu phải là SHA-256 (32 byte)");
    }
    this.inputSha256 = inputSha256.clone();
    this.status = Objects.requireNonNull(status, "status");
    this.attempts = attempts;
    this.error = error;
    this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    this.rowVersion = rowVersion;
  }

  /** Tác vụ mới chờ sinh. */
  public static RenderJob create(
      UUID id,
      UUID tenantId,
      UUID templateId,
      UUID requestEventId,
      UUID requestId,
      UUID formInstanceId,
      byte[] inputSha256,
      UUID createdBy,
      Instant now) {
    return new RenderJob(
        id,
        tenantId,
        templateId,
        requestEventId,
        requestId,
        formInstanceId,
        inputSha256,
        RenderJobStatus.CHO,
        0,
        null,
        createdBy,
        now,
        now,
        1);
  }

  /** Worker nhận tác vụ: tăng số lần thử. */
  public void start(Instant now) {
    require(RenderJobStatus.CHO);
    status = RenderJobStatus.DANG_SINH;
    attempts++;
    updatedAt = now;
  }

  public void complete(Instant now) {
    require(RenderJobStatus.DANG_SINH);
    status = RenderJobStatus.XONG;
    error = null;
    updatedAt = now;
  }

  /** Lỗi không sửa được bằng thử lại (dữ liệu lệch băm, mẫu hỏng, PDF không đạt chuẩn). */
  public void fail(String reason, Instant now) {
    require(RenderJobStatus.DANG_SINH);
    status = RenderJobStatus.LOI;
    error = truncate(reason);
    updatedAt = now;
  }

  /**
   * Lỗi tạm thời (Gotenberg, kho tệp, module nguồn không trả lời): trả về hàng đợi khi còn lượt
   * thử, hết lượt thì chuyển {@code LOI}.
   *
   * @return {@code true} khi tác vụ được xếp lại hàng đợi
   */
  public boolean retryLater(String reason, int maxAttempts, Instant now) {
    require(RenderJobStatus.DANG_SINH);
    error = truncate(reason);
    updatedAt = now;
    if (attempts >= maxAttempts) {
      status = RenderJobStatus.LOI;
      return false;
    }
    status = RenderJobStatus.CHO;
    return true;
  }

  public boolean inputMatches(byte[] sha256) {
    return Arrays.equals(inputSha256, sha256);
  }

  private void require(RenderJobStatus expected) {
    if (status != expected) {
      throw new IllegalStateException(
          "Tác vụ sinh " + id + " đang ở " + status + ", không phải " + expected);
    }
  }

  private static String truncate(String reason) {
    if (reason == null) {
      return null;
    }
    return reason.length() <= MAX_ERROR ? reason : reason.substring(0, MAX_ERROR);
  }

  public UUID id() {
    return id;
  }

  public UUID tenantId() {
    return tenantId;
  }

  public UUID templateId() {
    return templateId;
  }

  public UUID requestEventId() {
    return requestEventId;
  }

  public UUID requestId() {
    return requestId;
  }

  public UUID formInstanceId() {
    return formInstanceId;
  }

  public byte[] inputSha256() {
    return inputSha256.clone();
  }

  public RenderJobStatus status() {
    return status;
  }

  public int attempts() {
    return attempts;
  }

  public String error() {
    return error;
  }

  public UUID createdBy() {
    return createdBy;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public long rowVersion() {
    return rowVersion;
  }

  public Provenance provenance() {
    return provenance;
  }

  /** Gắn nguồn gốc khi tạo hoặc khi đọc lại từ kho; {@code null} là không có. */
  public RenderJob withProvenance(Provenance value) {
    this.provenance = value == null ? Provenance.NONE : value;
    return this;
  }
}
