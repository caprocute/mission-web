package vn.viettel.okrkpi.replica.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.platform.reconcile.ReconcileMetrics;
import vn.viettel.okrkpi.platform.reconcile.ReconcileRun;
import vn.viettel.okrkpi.platform.reconcile.RunStatus;
import vn.viettel.okrkpi.replica.domain.AppliedEvent;
import vn.viettel.okrkpi.replica.domain.AssignmentSnapshot;
import vn.viettel.okrkpi.replica.domain.PersonSnapshot;
import vn.viettel.okrkpi.replica.domain.UnitSnapshot;
import vn.viettel.okrkpi.replica.infrastructure.persistence.JdbcOrgReplicaStore;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;
import vn.vtokr.org.v1.OrgBootstrap;

/**
 * R1 (DL-12) trên PostgreSQL thật với migration và vai trò {@code svc_org_replica}: băm bản sao
 * khớp {@code KET_THUC} thì {@code KHOP}; cố ý sửa một dòng thì {@code LOI} đúng một bucket kèm
 * lệnh phát lại; phát lại bucket đó xong thì {@code DA_SUA}. Băm phía nguồn tính độc lập theo đúng
 * công thức {@code Buckets} của org-service.
 */
class OrgReplicaReconcileIT {

  private static final LocalDate FROM = LocalDate.of(2026, 1, 1);

  private static JdbcTemplate jdbc;
  private static TransactionTemplate tx;
  private static TenantDataSourceTransactionManager tm;
  private static JdbcOrgReplicaStore store;
  private static SimpleMeterRegistry meters;
  private static OrgReplicaReconciler reconciler;
  private static OrgReplicaReconcileJob job;

  private TenantId tenant;
  private final List<Row> source = new ArrayList<>();
  private Instant now = Instant.parse("2026-10-22T18:00:00Z");

  record Row(String kind, UUID id, LocalDate validFrom, long sv) {}

  @BeforeAll
  static void setUp() {
    var pg = MissionCoreTestDatabase.container();
    HikariDataSource ds = new HikariDataSource();
    ds.setMaximumPoolSize(4);
    ds.setJdbcUrl(pg.getJdbcUrl() + "?currentSchema=org_replica");
    ds.setUsername("svc_org_replica");
    ds.setPassword(MissionCoreTestDatabase.TEST_DB_PASSWORD);
    jdbc = new JdbcTemplate(ds);
    tm = new TenantDataSourceTransactionManager(ds, new TenantProperties(null, null));
    tx = new TransactionTemplate(tm);
    store = new JdbcOrgReplicaStore(jdbc);
    meters = new SimpleMeterRegistry();
    reconciler =
        new OrgReplicaReconciler(
            jdbc,
            new ReconcileMetrics(meters, Clock.systemUTC()),
            new ObjectMapper(),
            Clock.systemUTC());
    job =
        new OrgReplicaReconcileJob(
            reconciler,
            tm,
            new StaticListableBeanFactory().getBeanProvider(TenantDirectory.class),
            "",
            Duration.ZERO,
            Clock.offset(Clock.systemUTC(), Duration.ofDays(3650)));
  }

  @BeforeEach
  void seed() {
    tenant = TenantId.of(UUID.randomUUID());
    source.clear();
    inTenant(
        () -> {
          for (int i = 0; i < 3; i++) {
            UUID u = UUID.randomUUID();
            store.upsertUnit(
                new UnitSnapshot(
                    u, FROM, null, "DV" + i, "Đơn vị " + i, "CO_QUAN", null, null, null, 2),
                event());
            source.add(new Row("ORG_UNIT", u, FROM, 2));
            for (int j = 0; j < 8; j++) {
              UUID p = UUID.randomUUID();
              store.upsertPerson(
                  new PersonSnapshot(p, "Cán bộ " + j, true, "DANG_CONG_TAC", 1), event());
              source.add(new Row("PERSON", p, null, 1));
              UUID a = UUID.randomUUID();
              store.upsertAssignment(
                  new AssignmentSnapshot(
                      a, p, u, null, UUID.randomUUID(), "CHINH_THUC", true, false, FROM, null, 3),
                  event());
              source.add(new Row("ASSIGNMENT", a, FROM, 3));
            }
          }
          return null;
        });
  }

  @Test
  void banSaoDungThiKhop() {
    UUID batch = expect(0, all(), null);
    assertThat(job.runFor(List.of(tenant))).isEqualTo(1);
    ReconcileRun run = run(batch);
    assertThat(run.status()).isEqualTo(RunStatus.KHOP);
    assertThat(run.bucketsTotal()).isEqualTo(256);
    assertThat(run.bucketsMismatch()).isZero();
    assertThat(run.entityType()).isEqualTo(OrgReplicaReconciler.ALL_KINDS);
    assertThat(run.detail()).contains("\"actualCounts\"").contains("\"PERSON\": 24");
  }

  @Test
  void suaMotDongThiLechDungMotBucketRoiPhatLaiThiDaSua() {
    Row p = source.stream().filter(r -> r.kind().equals("PERSON")).findFirst().orElseThrow();
    int bucket = OrgReplicaReconciler.bucketsOf(List.of(p.id())).first();
    // Sửa trái phép trực tiếp trong CSDL: bản sao không còn khớp nguồn.
    inTenant(
        () ->
            jdbc.update(
                "UPDATE org_replica.person_v SET source_version = 99 WHERE id = ?", p.id()));
    double before = mismatches();

    UUID nightly = expect(0, all(), null);
    job.runFor(List.of(tenant));
    ReconcileRun lech = run(nightly);
    assertThat(lech.status()).isEqualTo(RunStatus.LOI);
    assertThat(lech.bucketsMismatch()).isEqualTo(1);
    assertThat(lech.detail())
        .contains("\"mismatched\": [" + bucket + "]")
        .contains("org-republish --tenant=" + tenant.value() + " --bucket=" + bucket);
    assertThat(mismatches() - before).isEqualTo(1);

    // Nguồn phát lại riêng bucket lệch (sourceVersion lớn hơn bản đã bị sửa), bên nhận áp và khớp.
    source.replaceAll(r -> r.id().equals(p.id()) ? new Row("PERSON", p.id(), null, 100) : r);
    inTenant(
        () -> {
          store.upsertPerson(
              new PersonSnapshot(p.id(), "Cán bộ", true, "DANG_CONG_TAC", 100), event());
          return null;
        });
    long inBucket = source.stream().filter(r -> bucket(r.id()) == bucket).count();
    UUID replay = expect(inBucket, List.of(bucket), null);
    job.runFor(List.of(tenant));
    ReconcileRun sua = run(replay);
    assertThat(sua.status()).isEqualTo(RunStatus.DA_SUA);
    assertThat(sua.rowsRepaired()).isEqualTo((int) inBucket);
    assertThat(sua.bucketsTotal()).isEqualTo(1);
  }

  @Test
  void thieuBanGhiOBanSaoCungLaLech() {
    UUID extra = UUID.randomUUID();
    source.add(new Row("ORG_UNIT", extra, FROM, 1));
    UUID batch = expect(0, all(), List.of("ORG_UNIT"));
    job.runFor(List.of(tenant));
    ReconcileRun run = run(batch);
    assertThat(run.status()).isEqualTo(RunStatus.LOI);
    assertThat(run.entityType()).isEqualTo("ORG_UNIT");
    assertThat(run.detail()).contains("--entity=unit --bucket=" + bucket(extra));
  }

  @Test
  void consumerChiGhiKetThucVaBoQuaThongDiepKhac() {
    UUID batch = UUID.randomUUID();
    var listener = new OrgReplicaReconcileListener(reconciler);
    Map<String, String> hashes = sourceHashes(all(), null);
    inTenant(
        () -> {
          listener.onBootstrap(envelope(marker(batch, "BAT_DAU", null)));
          listener.onBootstrap(envelope(marker(batch, "KET_THUC", hashes)));
          listener.onBootstrap(envelope(marker(batch, "KET_THUC", hashes)));
          return null;
        });
    assertThat(run(OrgReplicaReconciler.runId(batch)).status()).isEqualTo(RunStatus.DANG_CHAY);
    assertThatThrownBy(
            () -> listener.onBootstrap(envelope(marker(UUID.randomUUID(), "KET_THUC", Map.of()))))
        .isInstanceOf(PermanentInboxException.class);
    assertThatThrownBy(
            () ->
                listener.onBootstrap(
                    envelope(marker(UUID.randomUUID(), "KET_THUC", Map.of("300", "00")))))
        .isInstanceOf(PermanentInboxException.class);
    job.runFor(List.of(tenant));
    assertThat(run(OrgReplicaReconciler.runId(batch)).status()).isEqualTo(RunStatus.KHOP);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private UUID expect(long entities, List<Integer> buckets, List<String> kinds) {
    UUID batch = UUID.randomUUID();
    Map<String, Long> counts = new LinkedHashMap<>();
    for (String k : kinds == null ? List.of("ORG_UNIT", "PERSON", "ASSIGNMENT") : kinds) {
      counts.put(k, source.stream().filter(r -> r.kind().equals(k)).count());
    }
    Map<String, String> hashes = sourceHashes(buckets, kinds);
    now = now.plusSeconds(60);
    Instant at = now;
    inTenant(() -> reconciler.expect(tenant.value(), batch, entities, counts, hashes, at));
    return OrgReplicaReconciler.runId(batch);
  }

  /** Băm nguồn theo đúng {@code Buckets.Accumulator} của org-service. */
  private Map<String, String> sourceHashes(List<Integer> buckets, List<String> kinds) {
    long[] sums = new long[256];
    for (Row r : source) {
      if (kinds == null || kinds.contains(r.kind())) {
        byte[] h =
            sha(
                r.kind()
                    + "|"
                    + r.id()
                    + "|"
                    + (r.validFrom() == null ? "-" : r.validFrom())
                    + "|"
                    + r.sv());
        long v = 0;
        for (int i = 0; i < 8; i++) {
          v = (v << 8) | (h[i] & 0xffL);
        }
        sums[bucket(r.id())] += v;
      }
    }
    Map<String, String> out = new LinkedHashMap<>();
    buckets.forEach(b -> out.put(Integer.toString(b), HexFormat.of().toHexDigits(sums[b])));
    return out;
  }

  private static int bucket(UUID id) {
    return sha(id.toString())[0] & 0xff;
  }

  private static byte[] sha(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<Integer> all() {
    return java.util.stream.IntStream.range(0, 256).boxed().toList();
  }

  private ReconcileRun run(UUID id) {
    return inTenant(() -> reconciler.runs().find(id).orElseThrow());
  }

  private double mismatches() {
    var c =
        meters
            .find(ReconcileMetrics.MISMATCHES)
            .tag("reconcile", OrgReplicaReconciler.JOB)
            .counter();
    return c == null ? 0 : c.count();
  }

  private AppliedEvent event() {
    return new AppliedEvent(tenant, UUID.randomUUID(), now.minusSeconds(5));
  }

  private static OrgBootstrap marker(UUID batch, String marker, Map<String, String> hashes) {
    return OrgBootstrap.newBuilder()
        .setBatchId(batch)
        .setSeq("BAT_DAU".equals(marker) ? 0 : 1)
        .setIsLast(!"BAT_DAU".equals(marker))
        .setEntity(null)
        .setMarker(marker)
        .setEntityCounts(
            hashes == null ? null : Map.of("ORG_UNIT", 3L, "PERSON", 24L, "ASSIGNMENT", 24L))
        .setBucketHashes(hashes == null ? null : new LinkedHashMap<>(hashes))
        .build();
  }

  private InboxEnvelope<OrgBootstrap> envelope(OrgBootstrap payload) {
    return new InboxEnvelope<>(
        UUID.randomUUID(),
        "org.bootstrap.v1",
        "k",
        tenant,
        null,
        0L,
        null,
        Classification.C1,
        null,
        null,
        null,
        now.atOffset(ZoneOffset.UTC).toInstant(),
        0,
        0L,
        payload);
  }

  private <T> T inTenant(Callable<T> work) {
    try {
      return TenantContext.call(tenant, () -> tx.execute(s -> call(work)));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static <T> T call(Callable<T> work) {
    try {
      return work.call();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
