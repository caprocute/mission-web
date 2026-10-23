package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.platform.reconcile.JdbcReconcileRunStore;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.shared.kernel.Jcs;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;

/**
 * Khung kiểm thử R2 cho nguồn của một module trên PostgreSQL thật: kết nối {@code svc_<schema>}
 * (RLS), kết nối quản trị để sửa dữ liệu ngoài luồng, sổ cái giả theo mã sự kiện và dòng outbox
 * mang thân Avro thật. Mỗi lần chạy dùng ngày riêng nên mã {@code reconcile_run} không trùng.
 */
public final class LedgerReconcileHarness {

  public static final LocalDate DAY = LocalDate.of(2026, 10, 22);
  public static final Instant FROM = DAY.atStartOfDay(ZoneOffset.UTC).toInstant();
  public static final Instant TO = FROM.plus(Duration.ofDays(1));
  private static final Instant NOW = TO.plus(Duration.ofHours(2));

  public final String schema;
  public final JdbcTemplate jdbc;
  public final PlatformTransactionManager tm;
  public final JdbcTemplate admin;
  private final Map<UUID, String> ledger = new ConcurrentHashMap<>();
  private LedgerSourceReconciler reconciler;
  private int runs;

  public LedgerReconcileHarness(String schema) {
    EventContracts.trustContractPackages();
    this.schema = schema;
    var pg = MissionCoreTestDatabase.container();
    HikariDataSource ds = new HikariDataSource();
    ds.setMaximumPoolSize(3);
    ds.setJdbcUrl(pg.getJdbcUrl() + "?currentSchema=" + schema);
    ds.setUsername("svc_" + schema);
    ds.setPassword(MissionCoreTestDatabase.TEST_DB_PASSWORD);
    this.jdbc = new JdbcTemplate(ds);
    this.tm = new TenantDataSourceTransactionManager(ds, new TenantProperties(null, null));
    this.admin =
        new JdbcTemplate(
            new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
  }

  /** Gắn nguồn cần kiểm (dựng từ {@link #jdbc} và {@link #tm}). */
  public LedgerReconcileHarness source(Function<LedgerReconcileHarness, LedgerSource> make) {
    LedgerEntryLookup lookup =
        (t, agg, from, to) ->
            ledger.entrySet().stream()
                .map(e -> new LedgerEntryLookup.LedgerRef(e.getKey(), "x", e.getValue(), from))
                .toList();
    this.reconciler =
        new LedgerSourceReconciler(
            List.of(make.apply(this)),
            lookup,
            new ReconcileMetrics(new SimpleMeterRegistry(), Clock.systemUTC()),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            1.0,
            1_000,
            Duration.ofHours(1));
    return this;
  }

  /**
   * Sửa dữ liệu thẳng trong CSDL bằng tài khoản quản trị, bỏ qua trigger bất biến (mô phỏng sửa
   * ngoài luồng nghiệp vụ). Câu lệnh không có tham số; giá trị là hằng uuid, số do kiểm thử tạo.
   */
  public void tamper(String sql) {
    admin.execute(
        "SET session_replication_role = replica; " + sql + "; RESET session_replication_role");
  }

  /** Tạo phân vùng kỳ cho các bảng phân vùng LIST theo {@code period_bucket}. */
  public void partitions(int bucket, String... tables) {
    for (String t : tables) {
      jdbc.queryForList(
          "SELECT " + schema + ".fn_create_period_partitions(?::regclass, ?)",
          schema + "." + t,
          bucket);
    }
  }

  /**
   * Ghi dòng outbox có thân Avro thật trong cửa sổ; {@code inLedger} thì sổ cái có bản ghi cùng
   * băm.
   */
  public UUID record(
      TenantId tenant, String topic, UUID aggregate, SpecificRecord payload, boolean inLedger) {
    UUID id = UUID.randomUUID();
    byte[] avro = avro(payload);
    // sys.audit.v1 không có aggregateVersion trong payload.
    var field = payload.getSchema().getField("aggregateVersion");
    long version = field == null ? 1 : ((Number) payload.get(field.pos())).longValue();
    inTenant(
        tenant,
        () ->
            jdbc.update(
                "INSERT INTO "
                    + schema
                    + ".outbox_event (id, tenant_id, aggregate_type, aggregate_id,"
                    + " aggregate_version, event_type, topic, event_key, payload, schema_version,"
                    + " created_at) VALUES (?, ?, 'x', ?, ?, 'x', ?, ?, ?, 1, ?)",
                id,
                tenant.value(),
                aggregate,
                version,
                topic,
                tenant.value() + ":" + aggregate,
                avro,
                Timestamp.from(FROM.plusSeconds(60))));
    if (inLedger) {
      ledger.put(id, Jcs.hex(LedgerPayloadHash.of(payload.getSchema(), avro)));
    }
    return id;
  }

  /** Chạy R2 cho tenant (mỗi lần một ngày mới) và trả dòng {@code reconcile_run}. */
  public ReconcileRun run(TenantId tenant) {
    LocalDate day = DAY.plusDays(runs++);
    reconciler.run(day, FROM, TO, List.of(tenant));
    return inTenant(
        tenant,
        () ->
            new JdbcReconcileRunStore(jdbc, schema)
                .find(LedgerSourceReconciler.runId(schema, tenant.value(), day))
                .orElseThrow());
  }

  public <T> T inTenant(TenantId tenant, Callable<T> work) {
    try {
      return TenantContext.call(
          tenant,
          () ->
              new TransactionTemplate(tm)
                  .execute(
                      st -> {
                        try {
                          return work.call();
                        } catch (Exception e) {
                          throw new IllegalStateException(e);
                        }
                      }));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] avro(SpecificRecord r) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter<SpecificRecord>(r.getSchema()).write(r, enc);
      enc.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
