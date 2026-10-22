package vn.viettel.okrkpi.appraisal.infrastructure.client;

import java.util.Objects;
import java.util.UUID;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SignRequests;
import vn.viettel.okrkpi.shared.infrastructure.esign.EsignRequestClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Yêu cầu ký phiếu tự đánh giá ở SVC-10: mục đích {@code TU_DANH_GIA}, nguồn {@code appraisal}, mã
 * nguồn là mã hồ sơ; SVC-10 hỏi lại tài liệu qua {@code EsignSourceController}. 401 (cần xác thực
 * bổ sung) trả nguyên cho client.
 */
public class EsignSignRequests implements SignRequests {

  static final String PURPOSE = "TU_DANH_GIA";
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
    try {
      return client.create(
          tenant, PURPOSE, SOURCE_MODULE, profileId, provider, idempotencyKey, authorization);
    } catch (ResponseStatusException e) {
      throw new Relayed(e);
    }
  }
}
