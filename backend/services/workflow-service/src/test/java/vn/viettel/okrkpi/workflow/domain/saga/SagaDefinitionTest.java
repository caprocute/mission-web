package vn.viettel.okrkpi.workflow.domain.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ràng buộc định nghĩa saga, chính sách thử lại và các nhánh biên của máy trạng thái. */
class SagaDefinitionTest {

  private static final Instant T0 = Instant.parse("2026-10-22T00:00:00Z");

  private static SagaStepDefinition step(String code, String comp, boolean pnr, String reply) {
    return new SagaStepDefinition(
        code, "LENH", reply, Duration.ofMinutes(1), RetryPolicy.standard(), comp, pnr);
  }

  @Test
  void retryPolicyBacksOffExponentiallyUpToCap() {
    RetryPolicy policy = RetryPolicy.standard();
    assertThat(policy.allowsAnotherAttempt(4)).isTrue();
    assertThat(policy.allowsAnotherAttempt(5)).isFalse();
    assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.backoffAfter(3)).isEqualTo(Duration.ofSeconds(8));
    assertThat(policy.backoffAfter(20)).isEqualTo(Duration.ofMinutes(5));
    assertThatThrownBy(() -> new RetryPolicy(0, Duration.ZERO, 1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryPolicy(1, Duration.ZERO, 0.5, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryPolicy(1, Duration.ofSeconds(2), 1, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void definitionRejectsInvalidShapes() {
    assertThatThrownBy(() -> new SagaDefinition(SagaType.CHOT_KY, List.of(), true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SagaDefinition(
                    SagaType.CHOT_KY,
                    List.of(step("A", null, false, null), step("A", null, false, null)),
                    true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SagaDefinition(
                    SagaType.CHOT_KY,
                    List.of(step("A", null, true, null), step("B", null, true, null)),
                    true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SagaDefinition(
                    SagaType.CHOT_KY,
                    List.of(step("A", null, true, null), step("B", "HOAN_B", false, null)),
                    true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SagaStepDefinition(
                    "A", "L", null, Duration.ZERO, RetryPolicy.standard(), null, false))
        .isInstanceOf(IllegalArgumentException.class);
    SagaDefinition ok =
        new SagaDefinition(SagaType.CHOT_KY, List.of(step("A", "HOAN_A", false, "TRA")), true);
    assertThat(ok.step(1).awaitsReply()).isTrue();
    assertThat(ok.step(1).compensation()).contains("HOAN_A");
    assertThatThrownBy(() -> ok.step(2)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void replyOverdueAndOutOfOrderCalls() {
    SagaDefinition def =
        new SagaDefinition(SagaType.CHOT_KY, List.of(step("A", null, false, "TRA")), false);
    SagaInstance saga =
        SagaInstance.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            def,
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            Map.of(),
            UUID::randomUUID,
            T0);
    assertThat(saga.isReplyOverdue(def, T0)).isFalse();
    saga.beginAttempt(1, T0);
    saga.awaitReply(1, UUID.randomUUID());
    assertThat(saga.isReplyOverdue(def, T0.plusSeconds(30))).isFalse();
    assertThat(saga.isReplyOverdue(def, T0.plusSeconds(61))).isTrue();
    assertThatThrownBy(() -> saga.beginAttempt(2, T0)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(saga::retryFailedStep).isInstanceOf(SagaRuleViolation.class);
    assertThatThrownBy(saga::resume)
        .isInstanceOfSatisfying(
            SagaRuleViolation.class,
            e -> assertThat(e.reason()).isEqualTo(SagaRuleViolation.Reason.KHONG_TAM_DUNG));
    saga.requestPause();
    saga.resume();
    assertThat(saga.pauseRequested()).isFalse();
    // Không bù trừ khi hết lượt thử: chờ người vận hành.
    saga.recordFailure(1, "x".repeat(3000), def, T0);
    while (saga.status() != SagaStatus.LOI_CAN_XU_LY) {
      saga.beginAttempt(1, T0);
      saga.recordFailure(1, "x".repeat(3000), def, T0);
    }
    assertThat(saga.step(1).attempts()).isEqualTo(5);
    assertThat(saga.status()).isEqualTo(SagaStatus.LOI_CAN_XU_LY);
    assertThat(saga.step(1).lastError()).hasSizeLessThanOrEqualTo(2000);
    assertThat(saga.decideNext(T0)).isInstanceOf(NextWork.Wait.class);
    assertThatThrownBy(() -> saga.step(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
