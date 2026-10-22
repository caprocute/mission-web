package vn.viettel.okrkpi.appraisal.infrastructure.client;

import java.util.Objects;
import java.util.UUID;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SignRequests;
import vn.viettel.okrkpi.shared.infrastructure.esign.EsignRequestClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Yêu cầu ký phiếu tự đánh giá ({@code TU_DANH_GIA}) và kết luận thẩm định ({@code THAM_DINH}) ở
 * SVC-10, nguồn {@code appraisal}, mã nguồn là mã hồ sơ; SVC-10 hỏi lại tài liệu qua {@code
 * EsignSourceController}. 401 (cần xác thực bổ sung) trả nguyên cho client.
 */
public class EsignSignRequests implements SignRequests {

  static final String PURPOSE = "TU_DANH_GIA";
  static final String APPRAISAL_PURPOSE = "THAM_DINH";
  static final String DECISION_PURPOSE = "QUYET_DINH";
  static final String SOURCE_MODULE = "appraisal";

  private final EsignRequestClient client;

  public EsignSignRequests(EsignRequestClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public UUID requestSelfAssessment(
      TenantId tenant,
      UUID profileId,
      String provider,
      String idempotencyKey,
      String authorization) {
    return create(tenant, PURPOSE, profileId, provider, idempotencyKey, authorization);
  }

  @Override
  public UUID requestAppraisal(
      TenantId tenant,
      UUID profileId,
      String provider,
      String idempotencyKey,
      String authorization) {
    return create(tenant, APPRAISAL_PURPOSE, profileId, provider, idempotencyKey, authorization);
  }

  @Override
  public UUID requestDecision(
      TenantId tenant, UUID batchId, String provider, String idempotencyKey, String authorization) {
    return create(tenant, DECISION_PURPOSE, batchId, provider, idempotencyKey, authorization);
  }

  private UUID create(
      TenantId tenant,
      String purpose,
      UUID profileId,
      String provider,
      String idempotencyKey,
      String authorization) {
    try {
      return client.create(
          tenant, purpose, SOURCE_MODULE, profileId, provider, idempotencyKey, authorization);
    } catch (ResponseStatusException e) {
      throw new Relayed(e);
    }
  }
}
