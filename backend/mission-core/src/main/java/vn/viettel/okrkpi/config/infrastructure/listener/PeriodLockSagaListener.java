package vn.viettel.okrkpi.config.infrastructure.listener;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.common.header.Headers;
import vn.viettel.okrkpi.config.application.PeriodLockProgressService;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * Consumer vết saga {@code sys.audit.v1} của MOD-08: chiếu tiến độ saga chốt kỳ (mục đích {@code
 * CHOT_KY}) sang {@code config.period_lock_step} (SCR-0801, QA-06). Đối tượng {@code SAGA} mang mã
 * saga, {@code SAGA_BUOC} mang {@code <saga>:<bước>:<mã bước>} ({@code OutboxSagaJournal} của
 * workflow-service). Bản ghi kiểm toán khác bỏ qua. Được gọi qua {@code InboxMessageProcessor} của
 * schema config nên khử trùng lặp và ghi bản chiếu nằm trong cùng giao dịch.
 */
public class PeriodLockSagaListener {

  /** Định danh consumer trong {@code config.inbox_processed}. */
  public static final String CONSUMER = "config.period-lock-saga";

  static final String PURPOSE = "CHOT_KY";
  static final String OBJECT_SAGA = "SAGA";
  static final String OBJECT_STEP = "SAGA_BUOC";
  static final String ACTION_PREFIX = "SAGA_";

  private final PeriodLockProgressService progress;

  public PeriodLockSagaListener(PeriodLockProgressService progress) {
    this.progress = Objects.requireNonNull(progress, "progress");
  }

  /**
   * Bản ghi theo hợp đồng chung (thân Avro đăng ký ở Apicurio). {@code sys.audit.v1} là topic dùng
   * chung: org-service ghi kiểm toán đổi ngữ cảnh (BR-00-08) dạng JSON, tiêu đề camelCase, không
   * mang mã lược đồ. Vết saga chốt kỳ luôn là Avro (OutboxSagaJournal của workflow-service), nên
   * bản ghi không có mã lược đồ không thuộc consumer này: bỏ qua thay vì đưa vào inbox (inbox đòi
   * tiêu đề tenantid và chuyển thư chết).
   */
  public static boolean avroContract(Headers headers) {
    return headers != null && headers.lastHeader(OutboxHeaders.APICURIO_GLOBAL_ID) != null;
  }

  /**
   * Bản ghi kiểm toán là vết saga chốt kỳ: lọc trước inbox để mọi bản ghi {@code sys.audit.v1} khác
   * không tạo dòng {@code config.inbox_processed}.
   */
  public static boolean relevant(Object payload) {
    return payload instanceof AuditEvent event
        && PURPOSE.equals(str(event.getPurpose()))
        && event.getAction() != null
        && event.getAction().toString().startsWith(ACTION_PREFIX);
  }

  public void on(AuditEvent event, InboxEnvelope<?> envelope) {
    if (!relevant(event)) {
      return;
    }
    String objectType = str(event.getObjectType());
    String objectId = str(event.getObjectId());
    if (objectId == null) {
      return;
    }
    UUID sagaId;
    Integer stepNo = null;
    try {
      if (OBJECT_STEP.equals(objectType)) {
        String[] parts = objectId.split(":", 3);
        sagaId = UUID.fromString(parts[0]);
        stepNo = Integer.valueOf(parts[1]);
      } else if (OBJECT_SAGA.equals(objectType)) {
        sagaId = UUID.fromString(objectId);
      } else {
        return;
      }
    } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
      return;
    }
    String action = event.getAction().toString().substring(ACTION_PREFIX.length());
    boolean success = "THANH_CONG".equals(str(event.getOutcome()));
    UUID actor = envelope.actorId() == null ? null : envelope.actorId().value();
    Instant at = envelope.occurredAt() != null ? envelope.occurredAt() : Instant.now();
    progress.apply(envelope.tenantId(), sagaId, action, stepNo, success, actor, at);
  }

  private static String str(CharSequence value) {
    return value == null ? null : value.toString();
  }
}
