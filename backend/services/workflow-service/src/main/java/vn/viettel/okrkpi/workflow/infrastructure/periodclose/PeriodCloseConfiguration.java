package vn.viettel.okrkpi.workflow.infrastructure.periodclose;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import vn.viettel.okrkpi.workflow.application.periodclose.PeriodCloseAlarms;
import vn.viettel.okrkpi.workflow.application.periodclose.PeriodCloseSaga;
import vn.viettel.okrkpi.workflow.application.periodclose.PeriodCloseSteps;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway;
import vn.viettel.okrkpi.workflow.application.periodclose.port.PeriodCloseGateway.Participant;
import vn.viettel.okrkpi.workflow.application.saga.SagaBlueprint;
import vn.viettel.okrkpi.workflow.application.saga.SagaStepHandler;

/**
 * Lắp saga {@code CHOT_KY} (DV-06-06) vào khung saga: bản thiết kế, sáu bộ xử lý bước, cổng tới các
 * bên tham gia và cảnh báo quá ngân sách. Chế độ từng bên đọc từ {@code
 * okrkpi.saga.period-close.participants.<mod-08|svc-07|svc-11|svc-10|svc-09>}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "okrkpi.saga",
    name = {"enabled", "period-close.enabled"},
    matchIfMissing = true)
public class PeriodCloseConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(PeriodCloseConfiguration.class);

  /** Khoảng lùi đầu của bước ký số lô (mặc định 1 phút, tổng 13 lần thử khoảng 4 giờ). */
  @Bean
  SagaBlueprint periodCloseSaga(
      @Value("${okrkpi.saga.period-close.signing-backoff:1m}") Duration signingBackoff) {
    return new PeriodCloseSaga(signingBackoff);
  }

  @Bean
  @ConditionalOnMissingBean(PeriodCloseGateway.class)
  PeriodCloseGateway periodCloseGateway(Environment env) {
    Map<Participant, GatedPeriodCloseGateway.Mode> modes = new EnumMap<>(Participant.class);
    for (Participant p : Participant.values()) {
      String key =
          "okrkpi.saga.period-close.participants." + p.name().toLowerCase().replace('_', '-');
      modes.put(
          p,
          env.getProperty(
              key, GatedPeriodCloseGateway.Mode.class, GatedPeriodCloseGateway.Mode.CHUA_NOI));
    }
    return new GatedPeriodCloseGateway(modes);
  }

  @Bean
  @ConditionalOnMissingBean(PeriodCloseAlarms.class)
  PeriodCloseAlarms periodCloseAlarms(ObjectProvider<MeterRegistry> meters) {
    Set<String> raised = ConcurrentHashMap.newKeySet();
    MeterRegistry registry = meters.getIfAvailable();
    return (invocation, budget, elapsed) -> {
      // Mỗi lần thử của một bước chỉ cảnh báo một lần trên pod này.
      String key = invocation.sagaId() + ":" + invocation.stepNo() + ":" + invocation.attempt();
      if (!raised.add(key)) {
        return;
      }
      LOG.warn(
          "MSG-08-04 saga chốt kỳ {} bước {} chạy {} phút, vượt ngân sách {} phút",
          invocation.sagaId(),
          invocation.stepCode(),
          elapsed.toMinutes(),
          budget.toMinutes());
      if (registry != null) {
        Counter.builder("okrkpi.saga.period_close.over_budget")
            .tag("step", invocation.stepCode())
            .register(registry)
            .increment();
      }
    };
  }

  @Bean
  SagaStepHandler periodClosePrecondition(
      PeriodCloseGateway gateway, PeriodCloseAlarms alarms, Clock clock) {
    return PeriodCloseSteps.handler(PeriodCloseSaga.KIEM_TIEN_DIEU_KIEN, gateway, alarms, clock);
  }

  @Bean
  SagaStepHandler periodCloseLock(
      PeriodCloseGateway gateway, PeriodCloseAlarms alarms, Clock clock) {
    return PeriodCloseSteps.handler(PeriodCloseSaga.DAT_DANG_KHOA, gateway, alarms, clock);
  }

  @Bean
  SagaStepHandler periodCloseScoring(
      PeriodCloseGateway gateway, PeriodCloseAlarms alarms, Clock clock) {
    return PeriodCloseSteps.handler(PeriodCloseSaga.TINH_DIEM_CHOT_KY, gateway, alarms, clock);
  }

  @Bean
  SagaStepHandler periodCloseForms(
      PeriodCloseGateway gateway, PeriodCloseAlarms alarms, Clock clock) {
    return PeriodCloseSteps.handler(PeriodCloseSaga.SINH_BIEU_MAU, gateway, alarms, clock);
  }

  @Bean
  SagaStepHandler periodCloseSigning(
      PeriodCloseGateway gateway, PeriodCloseAlarms alarms, Clock clock) {
    return PeriodCloseSteps.handler(PeriodCloseSaga.KY_SO_LO, gateway, alarms, clock);
  }

  @Bean
  SagaStepHandler periodCloseCompletion(
      PeriodCloseGateway gateway, PeriodCloseAlarms alarms, Clock clock) {
    return PeriodCloseSteps.handler(PeriodCloseSaga.HOAN_TAT, gateway, alarms, clock);
  }
}
