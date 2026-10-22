package vn.viettel.okrkpi.workflow.domain.saga;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Trạng thái bền của một bước saga (TL-03 {@code flowable.saga_step}). */
public final class SagaStep {

  static final int MAX_ERROR_LENGTH = 2000;

  private final UUID id;
  private final int stepNo;
  private final String stepCode;
  private final String compensationCode;
  private SagaStepStatus status;
  private int attempts;
  private UUID commandEventId;
  private UUID replyEventId;
  private String lastError;
  private Instant startedAt;
  private Instant finishedAt;

  public SagaStep(
      UUID id,
      int stepNo,
      String stepCode,
      String compensationCode,
      SagaStepStatus status,
      int attempts,
      UUID commandEventId,
      UUID replyEventId,
      String lastError,
      Instant startedAt,
      Instant finishedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.stepNo = stepNo;
    this.stepCode = Objects.requireNonNull(stepCode, "stepCode");
    this.compensationCode = compensationCode;
    this.status = Objects.requireNonNull(status, "status");
    this.attempts = attempts;
    this.commandEventId = commandEventId;
    this.replyEventId = replyEventId;
    this.lastError = lastError;
    this.startedAt = startedAt;
    this.finishedAt = finishedAt;
  }

  static SagaStep pending(UUID id, int stepNo, SagaStepDefinition definition) {
    return new SagaStep(
        id,
        stepNo,
        definition.stepCode(),
        definition.compensationCode(),
        SagaStepStatus.CHO,
        0,
        null,
        null,
        null,
        null,
        null);
  }

  void begin(Instant now) {
    status = SagaStepStatus.DANG_CHAY;
    attempts++;
    startedAt = now;
    finishedAt = null;
  }

  void commandSent(UUID eventId) {
    commandEventId = eventId;
  }

  void complete(UUID reply, Instant now) {
    status = SagaStepStatus.XONG;
    if (reply != null) {
      replyEventId = reply;
    }
    lastError = null;
    finishedAt = now;
  }

  void fail(String error, Instant now) {
    status = SagaStepStatus.LOI;
    lastError = truncate(error);
    finishedAt = now;
  }

  void compensated(Instant now) {
    status = SagaStepStatus.DA_BU_TRU;
    finishedAt = now;
  }

  void prepareCompensation() {
    attempts = 0;
    lastError = null;
  }

  void countCompensationAttempt(Instant now) {
    attempts++;
    startedAt = now;
  }

  void compensationFailed(String error) {
    lastError = truncate(error);
  }

  void resetCompensationAttempts() {
    attempts = 0;
  }

  void resetForRetry() {
    status = SagaStepStatus.CHO;
    attempts = 0;
  }

  void resetPending() {
    if (status != SagaStepStatus.XONG && status != SagaStepStatus.DA_BU_TRU) {
      status = SagaStepStatus.CHO;
    }
  }

  static String truncate(String error) {
    if (error == null) {
      return null;
    }
    return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
  }

  public UUID id() {
    return id;
  }

  public int stepNo() {
    return stepNo;
  }

  public String stepCode() {
    return stepCode;
  }

  public String compensationCode() {
    return compensationCode;
  }

  public SagaStepStatus status() {
    return status;
  }

  public int attempts() {
    return attempts;
  }

  public UUID commandEventId() {
    return commandEventId;
  }

  public UUID replyEventId() {
    return replyEventId;
  }

  public String lastError() {
    return lastError;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Instant finishedAt() {
    return finishedAt;
  }
}
