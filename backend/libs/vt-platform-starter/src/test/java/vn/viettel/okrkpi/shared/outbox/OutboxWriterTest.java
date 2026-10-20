package vn.viettel.okrkpi.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static vn.viettel.okrkpi.shared.outbox.OutboxFixtures.TENANT;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;

/** OutboxWriter: bắt buộc giao dịch, đúng cột và tiêu đề TL-03 (CSDL thật ở OutboxDebeziumIT). */
class OutboxWriterTest {

  private static final Instant NOW = Instant.parse("2026-10-20T03:04:05Z");
  private static final SerializedPayload BYTES =
      new SerializedPayload(new byte[] {1, 2, 3}, 42L, 1);

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final OutboxPayloadSerializer serializer = (topic, payload) -> BYTES;
  private final OutboxWriter writer =
      new OutboxWriter(jdbc, "task", serializer, new ObjectMapper(), Clock.fixed(NOW));

  @Test
  void ngoaiGiaoDichThiNemLoiVaKhongGhi() {
    OutboxPayloadSerializer spy = mock(OutboxPayloadSerializer.class);
    OutboxWriter w = new OutboxWriter(jdbc, "task", spy, new ObjectMapper(), Clock.fixed(NOW));

    assertThatThrownBy(() -> w.append(OutboxFixtures.envelope(UUID.randomUUID(), 1)))
        .isInstanceOf(IllegalTransactionStateException.class)
        .hasMessageContaining("MANDATORY");
    verifyNoInteractions(spy, jdbc);
  }

  @Test
  void phanLoaiC2BiChanTruocKhiGhi() {
    UUID task = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                inTransaction(
                    () ->
                        writer.append(
                            EventEnvelope.of(
                                "task.task.progressed.v1",
                                TENANT,
                                "Task",
                                task,
                                1,
                                OutboxFixtures.tienDo(task, 1),
                                Classification.C2))))
        .isInstanceOf(SensitiveEventException.class);
    verifyNoInteractions(jdbc);
  }

  @Test
  void trongGiaoDichGhiDungCotTl03() throws Exception {
    UUID task = UUID.randomUUID();
    ActorId actor = ActorId.of(UUID.randomUUID());
    EventEnvelope e = OutboxFixtures.envelope(task, 7).withActor(actor).withPolicyVersion("pp-1");

    UUID id = inTransaction(() -> writer.append(e));

    assertThat(id).isEqualTo(e.eventId());
    ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(sql.capture(), args.capture());
    assertThat(sql.getValue())
        .startsWith("INSERT INTO task.outbox_event (id, tenant_id, aggregate_type, aggregate_id,")
        .endsWith("?::jsonb)");
    Object[] v = args.getValue();
    assertThat(v).hasSize(11);
    assertThat(v[0]).isEqualTo(e.eventId());
    assertThat(v[1]).isEqualTo(TENANT.value());
    assertThat(v[2]).isEqualTo("Task");
    assertThat(v[3]).isEqualTo(task);
    assertThat(v[4]).isEqualTo(7L);
    assertThat(v[5]).isEqualTo("TienDoDaCapNhat");
    assertThat(v[6]).isEqualTo("task.task.progressed.v1");
    assertThat(v[7]).isEqualTo(TENANT + ":" + task);
    assertThat((byte[]) v[8]).containsExactly(1, 2, 3);
    assertThat(v[9]).isEqualTo(1);
    JsonNode headers = new ObjectMapper().readTree((String) v[10]);
    assertThat(headers.has(OutboxHeaders.TRACEPARENT)).isFalse();
    assertThat(headers.get(OutboxHeaders.ACTOR_ID).asText()).isEqualTo(actor.toString());
    assertThat(headers.get(OutboxHeaders.TENANT_ID).asText()).isEqualTo(TENANT.toString());
    assertThat(headers.get(OutboxHeaders.CLASSIFICATION).asText()).isEqualTo("C1");
    assertThat(headers.get(OutboxHeaders.POLICY_VERSION).asText()).isEqualTo("pp-1");
    assertThat(headers.get(OutboxHeaders.AGGREGATE_VERSION).asText()).isEqualTo("7");
    assertThat(headers.get(OutboxHeaders.CE_TYPE).asText()).isEqualTo("TienDoDaCapNhat");
    assertThat(headers.get(OutboxHeaders.OCCURRED_AT).asText()).isEqualTo(NOW.toString());
    assertThat(headers.get(OutboxHeaders.APICURIO_GLOBAL_ID).asLong()).isEqualTo(42L);
  }

  @Test
  void tieuDeCoTraceparentCuaSpanHienHanhVaBoQuaTruongTrong() {
    SpanContext ctx =
        SpanContext.create(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            TraceFlags.getSampled(),
            TraceState.getDefault());
    EventEnvelope e =
        OutboxFixtures.envelope(UUID.randomUUID(), 1)
            .withOccurredAt(Instant.parse("2026-01-01T00:00:00Z"));
    try (Scope ignored = Span.wrap(ctx).makeCurrent()) {
      var headers = writer.headers(e, BYTES);
      assertThat(headers.get(OutboxHeaders.TRACEPARENT))
          .isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
      assertThat(headers).doesNotContainKeys(OutboxHeaders.ACTOR_ID, OutboxHeaders.POLICY_VERSION);
      assertThat(headers.get(OutboxHeaders.OCCURRED_AT)).isEqualTo("2026-01-01T00:00:00Z");
    }
  }

  @Test
  void loiTuanTuHoaLamGiaoDichThatBaiVaKhongGhi() {
    OutboxWriter w =
        new OutboxWriter(
            jdbc,
            "task",
            (topic, payload) -> {
              throw new OutboxSerializationException("registry không phản hồi", null);
            },
            new ObjectMapper(),
            Clock.fixed(NOW));
    assertThatThrownBy(
            () -> inTransaction(() -> w.append(OutboxFixtures.envelope(UUID.randomUUID(), 1))))
        .isInstanceOf(OutboxSerializationException.class);
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void tenSchemaPhaiDungQuyUoc() {
    for (String bad : new String[] {null, "", "Task", "task;drop", "1task", "task.outbox"}) {
      assertThatThrownBy(
              () -> new OutboxWriter(jdbc, bad, serializer, new ObjectMapper(), Clock.systemUtc()))
          .as(String.valueOf(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(writer.schema()).isEqualTo("task");
  }

  @Test
  void proxyGiaoDichMandatoryChanGoiNgoaiGiaoDich() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TransactionAutoConfiguration.class))
        .withBean(PlatformTransactionManager.class, NoopTransactionManager::new)
        .withBean(
            OutboxWriter.class,
            () -> new OutboxWriter(jdbc, "task", serializer, new ObjectMapper(), Clock.fixed(NOW)))
        .run(
            ctx -> {
              OutboxWriter proxied = ctx.getBean(OutboxWriter.class);
              assertThat(proxied.getClass()).isNotEqualTo(OutboxWriter.class);
              EventEnvelope e = OutboxFixtures.envelope(UUID.randomUUID(), 1);
              assertThatThrownBy(() -> proxied.append(e))
                  .isInstanceOf(IllegalTransactionStateException.class);
              UUID id =
                  new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class))
                      .execute(s -> proxied.append(e));
              assertThat(id).isEqualTo(e.eventId());
              verify(jdbc).update(anyString(), any(Object[].class));
            });
  }

  @Test
  void payloadDaTuanTuHoaLaBanSao() {
    byte[] raw = {9, 8};
    SerializedPayload p = new SerializedPayload(raw, 5, 2);
    raw[0] = 0;
    assertThat(p.bytes()).containsExactly(9, 8);
    assertThat(p).isEqualTo(new SerializedPayload(new byte[] {9, 8}, 5, 2));
    assertThat(p.hashCode()).isEqualTo(new SerializedPayload(new byte[] {9, 8}, 5, 2).hashCode());
    assertThat(p).isNotEqualTo(new SerializedPayload(new byte[] {9, 8}, 6, 2));
    assertThat(p.toString()).contains("2B").contains("globalId=5");
  }

  private static <T> T inTransaction(java.util.function.Supplier<T> work) {
    return new TransactionTemplate(new NoopTransactionManager()).execute(s -> work.get());
  }

  /**
   * Bộ quản lý giao dịch tối thiểu: chỉ đánh dấu giao dịch đang mở cho TransactionSynchronization.
   */
  static final class NoopTransactionManager extends AbstractPlatformTransactionManager {

    private static final long serialVersionUID = 1L;
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    @Override
    protected Object doGetTransaction() {
      return ACTIVE.get();
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
      return (Boolean) transaction;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      ACTIVE.set(true);
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
      ACTIVE.remove();
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
