package vn.viettel.okrkpi.esign.domain.sign;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Yêu cầu ký của một người tại một mốc pháp lý ({@code esign.sign_request}), có thể ký lô nhiều tài
 * liệu. Vòng đời: {@code TAO} → {@code CHO_XAC_NHAN} → {@code DA_KY} | {@code TU_CHOI} | {@code
 * HET_HAN} | {@code LOI} (TL-03, hợp đồng {@code EsignRequestStatus}).
 */
public final class SignRequest {

  private final UUID id;
  private final UUID tenantId;
  private final UUID signerPersonId;
  private final SignPurpose purpose;
  private final String sourceModule;
  private final UUID sourceRefId;
  private final ProviderCode provider;
  private final List<SignItem> items;
  private final Instant createdAt;
  private final long rowVersion;
  private String providerTxnId;
  private SignRequestStatus status;
  private Instant expiresAt;
  private Instant completedAt;
  private UUID certificateRefId;

  public SignRequest(
      UUID id,
      UUID tenantId,
      UUID signerPersonId,
      SignPurpose purpose,
      String sourceModule,
      UUID sourceRefId,
      ProviderCode provider,
      List<SignItem> items,
      String providerTxnId,
      SignRequestStatus status,
      Instant expiresAt,
      Instant completedAt,
      UUID certificateRefId,
      Instant createdAt,
      long rowVersion) {
    this.id = Objects.requireNonNull(id, "id");
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.signerPersonId = Objects.requireNonNull(signerPersonId, "signerPersonId");
    this.purpose = Objects.requireNonNull(purpose, "purpose");
    this.sourceModule = Objects.requireNonNull(sourceModule, "sourceModule");
    this.sourceRefId = Objects.requireNonNull(sourceRefId, "sourceRefId");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.items = new ArrayList<>(Objects.requireNonNull(items, "items"));
    if (this.items.isEmpty()) {
      throw new IllegalArgumentException("Yêu cầu ký phải có ít nhất một tài liệu");
    }
    this.providerTxnId = providerTxnId;
    this.status = Objects.requireNonNull(status, "status");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    this.completedAt = completedAt;
    this.certificateRefId = certificateRefId;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.rowVersion = rowVersion;
  }

  /** Yêu cầu mới ở {@code TAO}, chưa gửi nhà cung cấp. */
  public static SignRequest create(
      UUID id,
      UUID tenantId,
      UUID signerPersonId,
      SignPurpose purpose,
      String sourceModule,
      UUID sourceRefId,
      ProviderCode provider,
      List<SignItem> items,
      UUID certificateRefId,
      Instant now,
      Instant expiresAt) {
    return new SignRequest(
        id,
        tenantId,
        signerPersonId,
        purpose,
        sourceModule,
        sourceRefId,
        provider,
        items,
        null,
        SignRequestStatus.TAO,
        expiresAt,
        null,
        certificateRefId,
        now,
        1);
  }

  /**
   * Yêu cầu đã ghi nhận, chờ worker mở phiên ở nhà cung cấp (DV-10-02): API trả {@code
   * CHO_XAC_NHAN} ngay mà không gọi đồng bộ nhà cung cấp.
   */
  public void awaitProvider() {
    require(SignRequestStatus.TAO);
    this.status = SignRequestStatus.CHO_XAC_NHAN;
  }

  /** Đang chờ xác nhận nhưng chưa có phiên ở nhà cung cấp. */
  public boolean awaitingProvider() {
    return status == SignRequestStatus.CHO_XAC_NHAN && providerTxnId == null;
  }

  /** Nhà cung cấp đã nhận phiên ký; chờ người ký xác nhận trên thiết bị tới {@code expiresAt}. */
  public void submitted(String txnId, Instant sessionExpiresAt) {
    if (status != SignRequestStatus.TAO && !awaitingProvider()) {
      throw new IllegalStateException(
          "Yêu cầu " + id + " ở trạng thái " + status + " đã có phiên ở nhà cung cấp");
    }
    this.providerTxnId = Objects.requireNonNull(txnId, "txnId");
    if (sessionExpiresAt != null) {
      this.expiresAt = sessionExpiresAt;
    }
    this.status = SignRequestStatus.CHO_XAC_NHAN;
  }

  /**
   * Ghi chữ ký nhận về cho tài liệu có băm {@code sha256}. Trả {@code false} khi băm không thuộc
   * yêu cầu (nhà cung cấp trả sai tài liệu).
   */
  public boolean attachSignature(
      byte[] sha256, byte[] signature, String objectKey, UUID certificateId, Instant at) {
    require(SignRequestStatus.CHO_XAC_NHAN);
    for (SignItem item : items) {
      if (item.matches(sha256) && !item.signed()) {
        item.sign(signature, objectKey, certificateId, at);
        return true;
      }
    }
    return false;
  }

  /** Mọi tài liệu đã có chữ ký: yêu cầu hoàn tất ({@code DA_KY}), sẵn sàng phát EVT-18. */
  public void completeSigned(UUID certificateId, Instant now) {
    require(SignRequestStatus.CHO_XAC_NHAN);
    if (signedCount() != items.size()) {
      throw new IllegalStateException(
          "Yêu cầu " + id + " còn " + (items.size() - signedCount()) + " tài liệu chưa có chữ ký");
    }
    this.certificateRefId = certificateId;
    finish(SignRequestStatus.DA_KY, now);
  }

  public void rejected(Instant now) {
    require(SignRequestStatus.CHO_XAC_NHAN);
    finish(SignRequestStatus.TU_CHOI, now);
  }

  /** Quá hạn xác nhận; trả {@code true} khi vừa chuyển sang {@code HET_HAN}. */
  public boolean expireIfDue(Instant now) {
    if (status == SignRequestStatus.CHO_XAC_NHAN && !now.isBefore(expiresAt)) {
      finish(SignRequestStatus.HET_HAN, now);
      return true;
    }
    return false;
  }

  public void expired(Instant now) {
    require(SignRequestStatus.CHO_XAC_NHAN);
    finish(SignRequestStatus.HET_HAN, now);
  }

  public void failed(Instant now) {
    if (status.isTerminal()) {
      throw new IllegalStateException("Yêu cầu " + id + " đã kết thúc (" + status + ")");
    }
    finish(SignRequestStatus.LOI, now);
  }

  public int signedCount() {
    return (int) items.stream().filter(SignItem::signed).count();
  }

  private void finish(SignRequestStatus terminal, Instant now) {
    this.status = terminal;
    this.completedAt = now;
  }

  private void require(SignRequestStatus expected) {
    if (status != expected) {
      throw new IllegalStateException(
          "Yêu cầu " + id + " ở trạng thái " + status + ", cần " + expected);
    }
  }

  public UUID id() {
    return id;
  }

  public UUID tenantId() {
    return tenantId;
  }

  public UUID signerPersonId() {
    return signerPersonId;
  }

  public SignPurpose purpose() {
    return purpose;
  }

  public String sourceModule() {
    return sourceModule;
  }

  public UUID sourceRefId() {
    return sourceRefId;
  }

  public ProviderCode provider() {
    return provider;
  }

  public boolean batch() {
    return items.size() > 1;
  }

  public List<SignItem> items() {
    return List.copyOf(items);
  }

  public String providerTxnId() {
    return providerTxnId;
  }

  public SignRequestStatus status() {
    return status;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Instant completedAt() {
    return completedAt;
  }

  public UUID certificateRefId() {
    return certificateRefId;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public long rowVersion() {
    return rowVersion;
  }
}
