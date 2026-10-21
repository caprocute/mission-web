package vn.viettel.okrkpi.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.OutputStreamAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.crypto.CryptoProvider;
import vn.viettel.okrkpi.shared.crypto.DekCache.CachedDek;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.DekService;
import vn.viettel.okrkpi.shared.crypto.DekStatus;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * Kiểm thử tích hợp nhật ký truy cập C2 (CORE-00-05) trên PostgreSQL thật, schema {@code appraisal}
 * giả lập mô-đun có trường C2: một lần giải mã tạo đúng một sự kiện {@code sys.audit.v1}; đọc 50
 * dòng tạo một sự kiện gom; outbox lỗi thì không trả dữ liệu; nhật ký vận hành không chứa bản rõ;
 * đo chi phí thêm của điểm móc.
 *
 * <p>Thân sự kiện mã hoá Avro nhị phân bằng lớp sinh từ hợp đồng rồi giải ngược để kiểm hợp lệ lược
 * đồ; Kafka, Apicurio và consumer sổ cái DV-09-02 không chạy trong kiểm thử này (DV-09-02 chưa gộp
 * main).
 */
class C2AccessAuditIT {

  private static final String SECRET = "Nội dung khiếu nại tuyệt mật số 42";
  private static final String COLUMN = "appraisal.complaint.content_enc";
  private static final UUID ACTOR = UUID.fromString("01950000-0000-7000-8000-0000000000a1");
  private static final UUID ASSIGNMENT = UUID.fromString("01950000-0000-7000-8000-0000000000b1");
  private static final byte[] KEY = new byte[32];

  private static JdbcTemplate jdbc;
  private static TenantDataSourceTransactionManager tm;
  private static FieldEncryptor encryptor;
  private static C2AccessAuditor auditor;
  private static final ByteArrayOutputStream LOGS = new ByteArrayOutputStream();
  private static OutputStreamAppender appender;

  @BeforeAll
  static void setUp() {
    EventContracts.trustContractPackages();
    appender = attachLogCapture();
    var pg = MissionCoreTestDatabase.container();
    HikariDataSource ds = new HikariDataSource();
    ds.setMaximumPoolSize(4);
    ds.setJdbcUrl(pg.getJdbcUrl() + "?currentSchema=appraisal");
    ds.setUsername("svc_appraisal");
    ds.setPassword(MissionCoreTestDatabase.TEST_DB_PASSWORD);
    jdbc = new JdbcTemplate(ds);
    tm = new TenantDataSourceTransactionManager(ds, new TenantProperties(null, null));

    CryptoProvider provider = new JcaAesGcmCryptoProvider();
    DekService deks = mock(DekService.class);
    UUID dekId = UUID.randomUUID();
    when(deks.getOrCreateActiveDek(any(), any()))
        .thenAnswer(inv -> dek(dekId, inv.getArgument(0), inv.getArgument(1)));
    when(deks.getDekForDecryption(any(), any()))
        .thenAnswer(inv -> dek(dekId, inv.getArgument(0), DekPurpose.KHIEU_NAI));
    encryptor = new FieldEncryptor(deks, provider);
    auditor = newAuditor(avroSerializer(), 20);
    encryptor.setDecryptListener(auditor);
  }

  @AfterAll
  static void tearDown() {
    encryptor.setDecryptListener(null);
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    ctx.getConfiguration().getRootLogger().removeAppender(appender.getName());
    appender.stop();
    ctx.updateLoggers();
    // Nhật ký vận hành không bao giờ chứa bản rõ C2, kể cả khi ghi vết thất bại.
    assertThat(LOGS.toString(StandardCharsets.UTF_8)).doesNotContain(SECRET);
  }

  @Test
  void motLanGiaiMaTaoDungMotSuKienDuTruong() {
    TenantId tenant = TenantId.of(UUID.randomUUID());
    UUID row = UUID.randomUUID();
    byte[] envelope = encrypt(tenant, row);

    String plain = inTx(tenant, false, () -> decrypt(tenant, row, envelope));

    assertThat(plain).isEqualTo(SECRET);
    List<Map<String, Object>> rows = outboxRows(tenant);
    assertThat(rows).hasSize(1);
    Map<String, Object> r = rows.getFirst();
    assertThat(r.get("topic")).isEqualTo(EventTopics.SYS_AUDIT_V1);
    assertThat(r.get("headers").toString()).contains(ACTOR.toString()).doesNotContain(SECRET);
    AuditEvent e = decode((byte[]) r.get("payload"));
    assertThat(e.getAction()).isEqualTo("XEM_C2");
    assertThat(e.getObjectType()).isEqualTo("COMPLAINT");
    assertThat(e.getObjectId()).isEqualTo(row.toString());
    assertThat(e.getActorAssignmentId()).isEqualTo(ASSIGNMENT);
    assertThat(e.getActorRole()).isEqualTo("AC-05");
    assertThat(e.getSystem()).isFalse();
    assertThat(e.getTargets())
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.getColumn()).isEqualTo(COLUMN);
              assertThat(t.getDekPurpose()).isEqualTo("KHIEU_NAI");
            });
    assertThat(new String((byte[]) r.get("payload"), StandardCharsets.UTF_8))
        .doesNotContain(SECRET);
  }

  @Test
  void docDanhSachNamMuoiDongTrongGiaoDichChiDocTaoSuKienGomChiaLo() {
    TenantId tenant = TenantId.of(UUID.randomUUID());
    List<UUID> ids = new ArrayList<>();
    List<byte[]> envelopes = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      UUID row = UUID.randomUUID();
      ids.add(row);
      envelopes.add(encrypt(tenant, row));
    }

    inTx(
        tenant,
        true,
        () -> {
          for (int i = 0; i < ids.size(); i++) {
            decrypt(tenant, ids.get(i), envelopes.get(i));
          }
          return null;
        });

    // 50 đối tượng, tối đa 20 mỗi sự kiện: 3 sự kiện gom (20 + 20 + 10), không phải 50 sự kiện.
    List<AuditEvent> events =
        outboxRows(tenant).stream().map(r -> decode((byte[]) r.get("payload"))).toList();
    assertThat(events).hasSize(3);
    assertThat(events.stream().mapToInt(e -> e.getTargets().size()).sum()).isEqualTo(50);
    assertThat(events.stream().flatMap(e -> e.getTargets().stream()).map(t -> t.getObjectId()))
        .containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
    assertThat(events.getFirst().getObjectId()).isNull();
  }

  @Test
  void outboxLoiThiKhongTraDuLieuC2() {
    TenantId tenant = TenantId.of(UUID.randomUUID());
    UUID row = UUID.randomUUID();
    byte[] envelope = encrypt(tenant, row);
    C2AccessAuditor failing =
        newAuditor(
            (topic, payload) -> {
              throw new IllegalStateException("registry không phản hồi");
            },
            20);
    encryptor.setDecryptListener(failing);
    try {
      // Ngoài giao dịch: ghi vết ngay, lỗi thì decrypt ném và không trả bản rõ.
      assertThatThrownBy(() -> TenantContext.run(tenant, () -> decrypt(tenant, row, envelope)))
          .isInstanceOf(C2AccessAuditException.class);
      // Trong giao dịch: lỗi lúc commit làm cả yêu cầu thất bại.
      assertThatThrownBy(() -> inTx(tenant, false, () -> decrypt(tenant, row, envelope)))
          .isInstanceOf(C2AccessAuditException.class);
    } finally {
      encryptor.setDecryptListener(auditor);
    }
    assertThat(outboxRows(tenant)).isEmpty();
  }

  @Test
  void annotationC2AccessGhiVetKhiDocTep() {
    TenantId tenant = TenantId.of(UUID.randomUUID());
    UUID evidence = UUID.randomUUID();
    ProxyFactory pf = new ProxyFactory(new EvidenceReader());
    pf.addAdvisor(
        new DefaultPointcutAdvisor(
            new AnnotationMatchingPointcut(null, C2Access.class, true),
            new C2AccessInterceptor(auditor)));
    pf.setProxyTargetClass(true);
    EvidenceReader proxy = (EvidenceReader) pf.getProxy();

    byte[] file = inTx(tenant, false, () -> proxy.open(evidence));

    assertThat(file).isNotEmpty();
    AuditEvent e = decode((byte[]) outboxRows(tenant).getFirst().get("payload"));
    assertThat(e.getObjectType()).isEqualTo("MINH_CHUNG");
    assertThat(e.getObjectId()).isEqualTo(evidence.toString());
    assertThat(e.getTargets().getFirst().getColumn()).isEqualTo(C2AccessAuditor.FILE_COLUMN);
  }

  @Test
  void chiPhiThemMoiLanGiaiMaDuoiMotMiliGiayP95() {
    TenantId tenant = TenantId.of(UUID.randomUUID());
    UUID row = UUID.randomUUID();
    byte[] envelope = encrypt(tenant, row);
    int n = 2_000;
    List<Long> with = new ArrayList<>(n);
    List<Long> without = new ArrayList<>(n);
    // Chạy ấm JIT trước khi đo.
    inTx(tenant, false, () -> measure(tenant, row, envelope, 500, new ArrayList<>()));
    inTx(tenant, false, () -> measure(tenant, row, envelope, n, with));
    encryptor.setDecryptListener(null);
    try {
      inTx(tenant, false, () -> measure(tenant, row, envelope, n, without));
    } finally {
      encryptor.setDecryptListener(auditor);
    }
    long p95With = p95(with);
    long p95Without = p95(without);
    long overheadMicros = Math.max(0, p95With - p95Without) / 1_000;
    System.out.printf(
        "CORE-00-05 đo chi phí: giải mã p95 có ghi vết=%d µs, không ghi vết=%d µs, chênh=%d µs%n",
        p95With / 1_000, p95Without / 1_000, overheadMicros);
    assertThat(overheadMicros).isLessThan(1_000L);
  }

  // ==================== Hỗ trợ ====================

  static class EvidenceReader {
    @C2Access(objectType = "MINH_CHUNG")
    public byte[] open(UUID evidenceId) {
      return new byte[] {1, 2, 3};
    }
  }

  private static Void measure(TenantId tenant, UUID row, byte[] envelope, int n, List<Long> sink) {
    for (int i = 0; i < n; i++) {
      long t0 = System.nanoTime();
      decrypt(tenant, row, envelope);
      sink.add(System.nanoTime() - t0);
    }
    return null;
  }

  private static long p95(List<Long> values) {
    List<Long> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
  }

  private static C2AccessAuditor newAuditor(OutboxPayloadSerializer serializer, int maxTargets) {
    OutboxWriter writer =
        new OutboxWriter(jdbc, "appraisal", serializer, new ObjectMapper(), Clock.systemUtc());
    return new C2AccessAuditor(
        writer, tm, () -> new C2Actor(new ActorId(ACTOR), ASSIGNMENT, "AC-05", false), maxTargets);
  }

  private static OutboxPayloadSerializer avroSerializer() {
    return (topic, payload) -> new SerializedPayload(encode(payload), 1L, 1);
  }

  private static byte[] encrypt(TenantId tenant, UUID row) {
    return encryptor.encrypt(
        tenant.value(), DekPurpose.KHIEU_NAI, List.of(COLUMN, row.toString()), SECRET);
  }

  private static String decrypt(TenantId tenant, UUID row, byte[] envelope) {
    return encryptor.decryptToString(
        tenant.value(), DekPurpose.KHIEU_NAI, List.of(COLUMN, row.toString()), envelope);
  }

  private static <T> T inTx(
      TenantId tenant, boolean readOnly, java.util.function.Supplier<T> work) {
    TransactionTemplate tx = new TransactionTemplate(tm);
    tx.setReadOnly(readOnly);
    try {
      return TenantContext.call(tenant, () -> tx.execute(s -> work.get()));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<Map<String, Object>> outboxRows(TenantId tenant) {
    return inTx(
        tenant,
        true,
        () ->
            jdbc.queryForList(
                "select topic, payload, headers::text as headers from appraisal.outbox_event"
                    + " where tenant_id = ? order by id",
                tenant.value()));
  }

  private static CachedDek dek(UUID dekId, UUID tenant, DekPurpose purpose) {
    return new CachedDek(
        dekId, tenant, purpose, LocalDate.of(2026, 10, 1), DekStatus.ACTIVE, KEY.clone());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static byte[] encode(SpecificRecord payload) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var encoder = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter(payload.getClass()).write(payload, encoder);
      encoder.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static AuditEvent decode(byte[] data) {
    try {
      return new SpecificDatumReader<>(AuditEvent.class)
          .read(null, DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(data), null));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static OutputStreamAppender attachLogCapture() {
    LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
    Configuration config = ctx.getConfiguration();
    OutputStreamAppender out =
        OutputStreamAppender.createAppender(
            PatternLayout.createDefaultLayout(config), null, LOGS, "core0005-capture", false, true);
    out.start();
    config.addAppender(out);
    config.getRootLogger().addAppender(out, null, null);
    ctx.updateLoggers();
    return out;
  }
}
