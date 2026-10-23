package vn.viettel.okrkpi.policy.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Một lần chạy saga phát hành phiên bản Policy Pack ({@code policy.publish_saga}, CORE-05-10, QT-15
 * ngày 4–5). Năm bước theo thứ tự; bước lỗi thì bù trừ ngược các bước đã xong và phiên bản giữ
 * {@code CHO_PHE_DUYET} có chữ ký.
 */
public record PolicyPublishSaga(
    UUID id,
    UUID versionId,
    UUID requestId,
    Status status,
    Step step,
    int attempts,
    String lastError,
    Context context,
    UUID startedBy,
    Instant startedAt,
    Instant finishedAt,
    UUID updatedBy,
    Instant updatedAt,
    long rowVersion) {

  /** Bộ trạng thái của {@code flowable.saga_instance} (TL-03). */
  public enum Status {
    DANG_CHAY,
    CHO_SU_KIEN,
    HOAN_TAT,
    DANG_BU_TRU,
    DA_BU_TRU,
    LOI_CAN_XU_LY;

    public boolean running() {
      return this == DANG_CHAY || this == CHO_SU_KIEN || this == DANG_BU_TRU;
    }
  }

  /** Các bước theo thứ tự chạy (CORE-05-10 mục 3). */
  public enum Step {
    KIEM_TUONG_THICH_UI,
    GAN_KY,
    DONG_BO_CELL,
    KICH_HOAT_LUONG_DUYET,
    PHAT_HANH;

    public int no() {
      return ordinal() + 1;
    }

    public static Step of(int no) {
      return values()[no - 1];
    }
  }

  /**
   * Một {@code policy_binding} saga đã ghi: {@code previousVersionId} là phiên bản cũ khi ghi đè,
   * {@code null} khi saga thêm mới.
   */
  public record Bound(UUID bindingId, UUID previousVersionId) {}

  /** Phần {@code context} saga cần để bù trừ hoặc thu hồi. */
  public record Context(List<Bound> bindings, boolean workflowActivated) {
    public Context {
      bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }

    public static Context empty() {
      return new Context(List.of(), false);
    }

    public Context withBinding(Bound bound) {
      List<Bound> next = new ArrayList<>(bindings);
      next.add(bound);
      return new Context(next, workflowActivated);
    }

    public Context withWorkflowActivated(boolean activated) {
      return new Context(bindings, activated);
    }
  }

  public PolicyPublishSaga {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(versionId, "versionId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(step, "step");
    context = context == null ? Context.empty() : context;
  }

  /** Lần chạy mới ở bước 1. */
  public static PolicyPublishSaga start(
      UUID id, UUID versionId, UUID requestId, UUID actor, Instant at) {
    return new PolicyPublishSaga(
        id,
        versionId,
        requestId,
        Status.DANG_CHAY,
        Step.KIEM_TUONG_THICH_UI,
        0,
        null,
        Context.empty(),
        actor,
        at,
        null,
        actor,
        at,
        0);
  }

  /** Bước hiện tại xong, sang bước kế; bước cuối xong thì {@code HOAN_TAT}. */
  public PolicyPublishSaga advance(Context next, Instant at) {
    if (step == Step.PHAT_HANH) {
      return copy(Status.HOAN_TAT, step, 0, lastError, next, at, at);
    }
    return copy(Status.DANG_CHAY, Step.of(step.no() + 1), 0, null, next, null, at);
  }

  /** Một lần thử của bước hiện tại lỗi. */
  public PolicyPublishSaga failedAttempt(String error, Instant at) {
    return copy(status, step, attempts + 1, trim(error), context, null, at);
  }

  /** Bắt đầu bù trừ ngược từ bước hiện tại. */
  public PolicyPublishSaga compensating(String error, Instant at) {
    return copy(Status.DANG_BU_TRU, step, attempts, trim(error), context, null, at);
  }

  /** Bù trừ xong; dữ liệu đã quay về như trước khi chạy. */
  public PolicyPublishSaga compensated(Context next, Instant at) {
    return copy(Status.DA_BU_TRU, step, attempts, lastError, next, at, at);
  }

  /** Bù trừ không xong, cần người xử lý. */
  public PolicyPublishSaga stuck(String error, Instant at) {
    return copy(Status.LOI_CAN_XU_LY, step, attempts, trim(error), context, null, at);
  }

  private PolicyPublishSaga copy(
      Status newStatus,
      Step newStep,
      int newAttempts,
      String newError,
      Context newContext,
      Instant newFinishedAt,
      Instant at) {
    return new PolicyPublishSaga(
        id,
        versionId,
        requestId,
        newStatus,
        newStep,
        newAttempts,
        newError,
        newContext,
        startedBy,
        startedAt,
        newFinishedAt,
        updatedBy,
        at,
        rowVersion + 1);
  }

  private static String trim(String error) {
    if (error == null) {
      return null;
    }
    return error.length() > 2000 ? error.substring(0, 2000) : error;
  }
}
