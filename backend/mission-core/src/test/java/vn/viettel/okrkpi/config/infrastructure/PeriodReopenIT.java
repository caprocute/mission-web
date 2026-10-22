package vn.viettel.okrkpi.config.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodWriteTarget;
import vn.viettel.okrkpi.config.application.HolidayCalendarService;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.application.PeriodReopenService;
import vn.viettel.okrkpi.config.application.PeriodStateCache;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.config.infrastructure.listener.PeriodReopenedListener;
import vn.viettel.okrkpi.config.infrastructure.persistence.JdbcPeriodReopenRepository;
import vn.viettel.okrkpi.config.infrastructure.persistence.JdbcPeriodRepository;
import vn.viettel.okrkpi.config.infrastructure.persistence.OutboxPeriodEvents;
import vn.viettel.okrkpi.config.infrastructure.persistence.PeriodConfiguration;
import vn.viettel.okrkpi.config.infrastructure.scheduling.PeriodReopenExpiryJob;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxOutcome;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.vtokr.oversight.v1.KyDaMoLaiHauKiem;

/**
 * Kiểm thử tích hợp CORE-08-06 trên PostgreSQL thật, ma trận bốn kịch bản: ghi trong phạm vi khi kỳ
 * đang mở lại thành công; ghi ngoài phạm vi 409; nhận EVT-22 hai lần chỉ xử lý một lần (inbox
 * {@code config.inbox_processed}); hết hạn thì timer khoá lại, ghi {@code KHOA_LAI}, phát EVT-09,
 * và ghi sau khi khoá lại 409. EVT-22 đưa thẳng vào bộ xử lý inbox của schema config (môi trường
 * kiểm thử chưa có Kafka), thân Avro được thay bằng bộ giải mã kiểm thử.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.config.period-reopen.expiry.enabled=false"
    })
@AutoConfigureMockMvc(addFilters = false)
class PeriodReopenIT {

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PeriodReopenService periodReopenService;
  @Autowired private JdbcPeriodRepository periodRepository;
  @Autowired private JdbcPeriodReopenRepository reopenRepository;
  @Autowired private HolidayCalendarService holidayCalendarService;
  @Autowired private InboxProperties inboxProperties;
  @Autowired private InboxRetryDlqPublisher dlq;
  @Autowired private InboxMetrics metrics;
  @Autowired private ObjectProvider<TenantDirectory> directory;

  @Autowired
  @Qualifier("configDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager tm;

  private PeriodConfigService periodConfigService;
  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID actor = PeriodApiIT.CallerConfig.ACTOR;

  @Test
  void reopenInScopeOutOfScopeDuplicateAndExpiryRelock() throws Exception {
    // Dịch vụ dựng tay với bộ tuần tự outbox giả (môi trường kiểm thử không có registry Avro).
    OutboxWriter writer =
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "config",
            (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
            json,
            Clock.systemUtc());
    periodConfigService =
        new PeriodConfigService(
            periodRepository,
            new OutboxPeriodEvents(writer),
            e -> {},
            null,
            Clock.systemUtc(),
            new PeriodStateCache(PeriodStateCache.DEFAULT_TTL),
            new TransactionTemplate(tm));
    periodConfigService.setReopens(reopenRepository);
    PeriodReopenService reopens =
        new PeriodReopenService(
            reopenRepository,
            periodRepository,
            periodConfigService,
            holidayCalendarService,
            null,
            e -> {},
            Clock.systemUtc(),
            10);
    PeriodReopenedListener listener = new PeriodReopenedListener(reopens);
    PeriodApi periodApi = periodConfigService;
    PeriodId period = lockedPeriod();
    UUID unit = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    KyDaMoLaiHauKiem event =
        KyDaMoLaiHauKiem.newBuilder()
            .setPeriodId(period.value())
            .setReopenRequestId(request)
            .setScope("DON_VI")
            .setScopeIds(List.of(unit))
            .setApproverIds(List.of(UUID.randomUUID(), UUID.randomUUID()))
            .setExecutedAt(Instant.now())
            .setAggregateVersion(3)
            .build();
    InboxMessageProcessor processor =
        PeriodConfiguration.configInboxProcessor(
            dataSource, tm, inboxProperties, (topic, headers, data) -> event, dlq, metrics);
    InboxEndpoint endpoint = PeriodConfiguration.reopenEndpoint(listener);
    UUID eventId = UUID.randomUUID();

    assertThat(processor.process(endpoint, record(eventId, request), () -> {}))
        .isEqualTo(InboxOutcome.PROCESSED);
    // Nhận trùng: inbox bỏ qua, kỳ chỉ mở lại một lần.
    assertThat(processor.process(endpoint, record(eventId, request), () -> {}))
        .isEqualTo(InboxOutcome.DUPLICATE_SKIPPED);
    assertThat(state(period)).isEqualTo("MO_LAI_HAU_KIEM:1");

    // Trong phạm vi: ghi được; ngoài phạm vi: 409 period-locked theo MSG-13-04.
    TenantContext.run(
        tenant, () -> periodApi.assertWritable(tenant, period, PeriodWriteTarget.unit(unit)));
    assertThatThrownBy(
            () ->
                TenantContext.run(
                    tenant,
                    () ->
                        periodApi.assertWritable(
                            tenant, period, PeriodWriteTarget.unit(UUID.randomUUID()))))
        .isInstanceOf(PeriodLockedException.class)
        .satisfies(
            e ->
                assertThat(((PeriodLockedException) e).detail())
                    .contains("ngoài phạm vi được mở lại"));

    // Hết hạn: đưa hạn về quá khứ rồi chạy timer.
    inTenant(
        jdbc ->
            jdbc.sql(
                    "UPDATE config.period_reopen SET opened_at = now() - interval '2 days',"
                        + " open_until = now() - interval '1 minute' WHERE id = ?")
                .param(request)
                .update());
    assertThat(expire(reopens)).isEqualTo(1);
    assertThat(expire(reopens)).isZero();
    // Timer của ngữ cảnh: tenant không còn lần mở lại quá hạn thì không làm gì.
    PeriodReopenExpiryJob job = new PeriodReopenExpiryJob(periodReopenService, directory, "");
    assertThat(job.runFor(List.of(tenant))).isZero();
    job.run();
    assertThat(state(period)).isEqualTo("DA_KHOA:1");
    List<String> log =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT from_status || '>' || to_status || ':' || cause_type"
                            + " FROM config.period_status_log WHERE period_id = ?"
                            + " ORDER BY changed_at, id")
                    .param(period.value())
                    .query(String.class)
                    .list());
    assertThat(log)
        .endsWith("DA_KHOA>MO_LAI_HAU_KIEM:MO_LAI_HAU_KIEM", "MO_LAI_HAU_KIEM>DA_KHOA:KHOA_LAI");
    Integer locked =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT count(*) FROM config.outbox_event WHERE aggregate_id = ?"
                            + " AND topic = ?")
                    .param(period.value())
                    .param(EventTopics.EVT_09_KY_DA_KHOA)
                    .query(Integer.class)
                    .single());
    assertThat(locked).isEqualTo(2);
    Integer processed =
        inTenant(
            jdbc ->
                jdbc.sql("SELECT count(*) FROM config.inbox_processed WHERE consumer = ?")
                    .param(PeriodReopenedListener.CONSUMER)
                    .query(Integer.class)
                    .single());
    assertThat(processed).isEqualTo(1);
    // Sau khi khoá lại: ghi trong phạm vi cũ cũng bị chặn.
    assertThatThrownBy(
            () ->
                TenantContext.run(
                    tenant,
                    () -> periodApi.assertWritable(tenant, period, PeriodWriteTarget.unit(unit))))
        .isInstanceOf(PeriodLockedException.class);
  }

  private int expire(PeriodReopenService reopens) throws Exception {
    return TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(tm)
                .execute(s -> reopens.expireDue(tenant, PeriodReopenExpiryJob.SYSTEM_ACTOR)));
  }

  private ConsumerRecord<String, byte[]> record(UUID eventId, UUID request) {
    ConsumerRecord<String, byte[]> r =
        new ConsumerRecord<>(
            EventTopics.EVT_22_KY_DA_MO_LAI_HAU_KIEM,
            0,
            0L,
            tenant.value() + ":" + request,
            new byte[] {0});
    r.headers().add(new RecordHeader(OutboxHeaders.EVENT_ID, bytes(eventId.toString())));
    r.headers().add(new RecordHeader(OutboxHeaders.TENANT_ID, bytes(tenant.value().toString())));
    r.headers().add(new RecordHeader(OutboxHeaders.AGGREGATE_VERSION, bytes("3")));
    return r;
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private PeriodId lockedPeriod() throws Exception {
    String body =
        """
        {"code":"2025-Q4","name":"Quý IV/2025","kind":"QUY","flow":"CA_HAI",\
        "startDate":"2025-10-01","endDate":"2025-12-31"}
        """;
    String id =
        json.readTree(
                TenantContext.call(
                        tenant,
                        () ->
                            mvc.perform(
                                    post("/api/v1/periods")
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                                .andReturn())
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8))
            .get("id")
            .asText();
    PeriodId period = PeriodId.parse(id);
    UUID saga = UUID.randomUUID();
    for (PeriodState s : List.of(PeriodState.DANG_KHOA, PeriodState.DA_KHOA)) {
      TenantContext.run(
          tenant,
          () ->
              new TransactionTemplate(tm)
                  .executeWithoutResult(
                      x ->
                          periodConfigService.transition(
                              tenant, period, s, saga, actor, null, null)));
    }
    return period;
  }

  private String state(PeriodId period) throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql("SELECT status || ':' || reopen_count FROM config.period WHERE id = ?")
                .param(period.value())
                .query(String.class)
                .single());
  }

  private <T> T inTenant(Function<JdbcClient, T> query) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> query.apply(jdbc)));
  }
}
