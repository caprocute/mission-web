package vn.viettel.okrkpi.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import vn.viettel.okrkpi.shared.audit.AuditAutoConfiguration;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;

/** Điều kiện bật OutboxWriter ({@code okrkpi.outbox.*}). */
class OutboxAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(AuditAutoConfiguration.class, OutboxAutoConfiguration.class));

  @Test
  void khongKhaiSchemaThiKhongBat() {
    runner
        .withBean(DataSource.class, () -> mock(DataSource.class))
        .run(
            ctx -> {
              assertThat(ctx).doesNotHaveBean(OutboxWriter.class);
              assertThat(ctx).doesNotHaveBean(OutboxPayloadSerializer.class);
            });
  }

  @Test
  void khaiSchemaVaCoDataSourceThiBatVoiSchemaCuaMoDun() {
    runner
        .withPropertyValues(
            "okrkpi.outbox.schema=task",
            "okrkpi.outbox.registry.url=http://127.0.0.1:1/apis/registry/v3",
            "okrkpi.outbox.registry.properties[apicurio.registry.retry-count]=1")
        .withBean(DataSource.class, () -> mock(DataSource.class))
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(OutboxWriter.class);
              assertThat(ctx.getBean(OutboxWriter.class).schema()).isEqualTo("task");
              assertThat(ctx.getBean(OutboxPayloadSerializer.class))
                  .isInstanceOf(ApicurioPayloadSerializer.class);
              assertThat(ctx.getBean(OutboxProperties.class).registry().properties())
                  .containsEntry("apicurio.registry.retry-count", "1");
            });
  }

  @Test
  void thieuRegistryHoacSchemaSaiThiKhongKhoiDong() {
    runner
        .withPropertyValues("okrkpi.outbox.schema=task")
        .withBean(DataSource.class, () -> mock(DataSource.class))
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("okrkpi.outbox.registry.url"));
    runner
        .withPropertyValues("okrkpi.outbox.schema=Task.X")
        .withBean(DataSource.class, () -> mock(DataSource.class))
        .withBean(
            OutboxPayloadSerializer.class,
            () -> (OutboxPayloadSerializer) (t, p) -> new SerializedPayload(new byte[0], 1, 1))
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("okrkpi.outbox.schema"));
  }

  @Test
  void khongCoDataSourceThiKhongCoWriter() {
    runner
        .withPropertyValues("okrkpi.outbox.schema=task")
        .withBean(
            OutboxPayloadSerializer.class,
            () -> (OutboxPayloadSerializer) (t, p) -> new SerializedPayload(new byte[0], 1, 1))
        .run(ctx -> assertThat(ctx).doesNotHaveBean(OutboxWriter.class));
  }
}
