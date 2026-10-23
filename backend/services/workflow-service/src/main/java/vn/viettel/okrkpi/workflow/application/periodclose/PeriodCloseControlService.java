package vn.viettel.okrkpi.workflow.application.periodclose;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.workflow.application.saga.SagaJournal.Action;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/**
 * Lệnh điều khiển riêng của saga chốt kỳ (DV-06-07, QT-08 bước 9): huỷ và bù trừ toàn bộ sau khi
 * hai người duyệt (BR-08-09), chạy lại hồ sơ tính lỗi thành lô mới, chấp nhận để lại hồ sơ lỗi kèm
 * lý do. MOD-08 kiểm vai trò và bốn mắt; SVC-06 kiểm lại người duyệt khác người đề nghị và trạng
 * thái saga. Hợp đồng chưa có điểm vào HTTP cho các lệnh này (wiki trang 14).
 */
public class PeriodCloseControlService {

  static final String SAME_PERSON = "PERIOD-403-SAME-PERSON";
  static final String VALIDATION = "COMMON-422-VALIDATION";

  private final SagaService sagas;
  private final Clock clock;

  public PeriodCloseControlService(SagaService sagas, Clock clock) {
    this.sagas = Objects.requireNonNull(sagas, "sagas");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Huỷ và bù trừ toàn bộ: chỉ trước khi bước ký số lô hoàn tất, sau đó 409 {@code
   * PERIOD-409-POINT-OF-NO-RETURN}. Bù trừ chạy ngược: huỷ yêu cầu ký đang chờ, thu hồi tệp, đánh
   * dấu lô điểm bị thay thế, trả kỳ về {@code MO} (MOD-08 phát EVT-26).
   */
  public SagaInstance cancel(UUID sagaId, UUID requestedBy, UUID approvedBy) {
    Objects.requireNonNull(requestedBy, "requestedBy");
    Objects.requireNonNull(approvedBy, "approvedBy");
    if (requestedBy.equals(approvedBy)) {
      throw new BusinessException(SAME_PERSON, "Người duyệt huỷ khoá kỳ phải khác người đề nghị.");
    }
    requireCloseSaga(sagaId);
    return sagas.control(sagaId, SagaService.Control.BU_TRU);
  }

  /**
   * Chạy lại hồ sơ tính lỗi: một lô mới chỉ gồm hồ sơ trong hàng đợi lỗi, mã lô tất định theo
   * {@code Idempotency-Key} nên gọi trùng không tạo lô thứ hai. Trả mã tác vụ của lô.
   */
  public UUID retryFailedProfiles(UUID sagaId, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new BusinessException(VALIDATION, "Thiếu khoá chống lặp của yêu cầu chạy lại.");
    }
    requireCloseSaga(sagaId);
    UUID jobId =
        UUID.nameUUIDFromBytes(
            (sagaId + ":chay-lai-ho-so-loi:" + idempotencyKey.strip())
                .getBytes(StandardCharsets.UTF_8));
    sagas.annotateWaitingStep(
        sagaId,
        PeriodCloseSaga.TINH_DIEM_CHOT_KY,
        Action.CHAY_LAI_LOI,
        Map.of(PeriodCloseSteps.Scoring.RETRY_NOTE, jobId.toString()));
    return jobId;
  }

  /** Chấp nhận để lại hồ sơ lỗi kèm lý do (TL-02a QT-08 ngoại lệ) để bước tính điểm hoàn tất. */
  public SagaInstance acceptFailedProfiles(UUID sagaId, UUID acceptedBy, String reason) {
    Objects.requireNonNull(acceptedBy, "acceptedBy");
    if (reason == null || reason.isBlank()) {
      throw new BusinessException(VALIDATION, "Cần ghi lý do chấp nhận để lại hồ sơ lỗi.");
    }
    requireCloseSaga(sagaId);
    Map<String, Object> note = new LinkedHashMap<>();
    note.put("nguoiChapNhan", acceptedBy.toString());
    note.put("lyDo", reason.strip());
    note.put("luc", clock.instant().toString());
    return sagas.annotateWaitingStep(
        sagaId,
        PeriodCloseSaga.TINH_DIEM_CHOT_KY,
        Action.CHAP_NHAN_LOI,
        Map.of(PeriodCloseSteps.Scoring.ACCEPT_NOTE, note));
  }

  private void requireCloseSaga(UUID sagaId) {
    SagaInstance saga =
        sagas
            .find(sagaId)
            .filter(s -> s.type() == SagaType.CHOT_KY)
            .orElseThrow(
                () ->
                    new BusinessException(
                        VALIDATION, "Không tìm thấy quy trình chốt kỳ được yêu cầu."));
    Objects.requireNonNull(saga);
  }
}
