package vn.viettel.okrkpi.org.republish;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.viettel.okrkpi.org.application.republish.Buckets;
import vn.viettel.okrkpi.org.application.republish.EntityKind;
import vn.viettel.okrkpi.org.application.republish.OrgRepublishService;
import vn.viettel.okrkpi.org.application.republish.RepublishReport;
import vn.viettel.okrkpi.org.application.republish.RepublishRequest;
import vn.viettel.okrkpi.org.infrastructure.republish.PayloadEncoder;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;
import vn.viettel.okrkpi.org.republish.RepublishTestSupport.Received;
import vn.viettel.okrkpi.org.republish.RepublishTestSupport.Replica;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;
import vn.vtokr.org.v1.DonViDaThayDoi;
import vn.vtokr.org.v1.NhanSuDaThayDoi;
import vn.vtokr.org.v1.PhanCongDaThayDoi;
import vn.vtokr.sys.v1.AuditEvent;

/** DV-04-08 · {@code org-republish} trên PostgreSQL 17 + Kafka Testcontainers, đọc bằng {@code svc_org} (RLS). */
@ActiveProfiles("republish")
@Import(OrgRepublishIT.Encoder.class)
class OrgRepublishIT extends IntegrationTestBase {

  static final Duration WAIT = Duration.ofSeconds(30);
  static final List<Integer> ALL = IntStream.range(0, Buckets.COUNT).boxed().toList();

  @DynamicPropertySource
  static void kafka(DynamicPropertyRegistry registry) {
    RepublishTestSupport.start();
    registry.add("okrkpi.org.republish.bootstrap-servers", RepublishTestSupport.KAFKA::getBootstrapServers);
    registry.add("okrkpi.org.republish.registry-url", () -> "http://registry.invalid/apis/registry/v3");
    registry.add("okrkpi.org.republish.exit-on-finish", () -> "false");
  }

  @TestConfiguration
  static class Encoder {
    @Bean
    @Primary
    PayloadEncoder plainAvroEncoder() {
      return new RepublishTestSupport.PlainAvroEncoder();
    }
  }

  @Autowired
  OrgRepublishService service;

  private final Fixtures fx = new Fixtures();
  private UUID tenant;
  private UUID root;
  private UUID child;
  private final List<UUID> persons = new ArrayList<>();

  @BeforeEach
  void seed() {
    tenant = UUID.randomUUID();
    UUID group = fx.subjectGroup(tenant, "K1");
    root = UUID.randomUUID();
    child = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    fx.unit(tenant, root, "G", "Trung ương", "CO_QUAN", group, LocalDate.of(2020, 1, 1), null);
    // Đơn vị con có hai lát hiệu lực và một bản ghi đã bị đính chính (không được phát).
    fx.unitVersion(tenant, child, "C1", "Sở cũ", "CO_QUAN", group, LocalDate.of(2020, 1, 1), LocalDate.of(2025, 1, 1),
        OffsetDateTime.parse("2020-01-01T00:00:00Z"), OffsetDateTime.parse("2021-01-01T00:00:00Z"), 0);
    fx.unitVersion(tenant, child, "C1", "Sở", "CO_QUAN", group, LocalDate.of(2020, 1, 1), LocalDate.of(2025, 1, 1),
        OffsetDateTime.parse("2021-01-01T00:00:00Z"), null, 1);
    fx.unitVersion(tenant, child, "C1", "Sở mới", "CO_QUAN", group, LocalDate.of(2025, 1, 1), null,
        OffsetDateTime.parse("2021-01-01T00:00:00Z"), null, 2);
    fx.unit(tenant, other, "C2", "Ban", "CO_QUAN", group, LocalDate.of(2020, 1, 1), null);
    fx.relation(tenant, "DANG", root, child, LocalDate.of(2020, 1, 1), null);
    fx.relation(tenant, "CHUYEN_MON", root, child, LocalDate.of(2020, 1, 1), null);
    fx.relation(tenant, "DANG", root, other, LocalDate.of(2020, 1, 1), null);
    UUID title = fx.jobTitle(tenant);
    persons.clear();
    for (int i = 0; i < 40; i++) {
      UUID p = fx.person(tenant, "Cán bộ " + i);
      persons.add(p);
      fx.assignment(tenant, p, i % 2 == 0 ? child : other, title, "CHINH", LocalDate.of(2024, 1, 1));
    }
    // Một tenant khác không được lọt vào lần phát lại.
    UUID foreign = UUID.randomUUID();
    fx.unit(foreign, UUID.randomUUID(), "X", "Ngoài", "CO_QUAN", fx.subjectGroup(foreign, "K1"),
        LocalDate.of(2020, 1, 1), null);
  }

  private RepublishReport run(Set<EntityKind> kinds, Set<Integer> buckets, boolean dryRun) {
    TenantContextHolder.set(tenant);
    try {
      return service.run(new RepublishRequest(tenant, kinds, buckets, null, dryRun, "van-hanh"));
    } finally {
      TenantContextHolder.clear();
    }
  }

  @Test
  void dungBanSaoTuTrongKhopBanGocTheoBamBucket() {
    RepublishReport dry = run(Set.of(), Set.of(), true);
    RepublishReport report = run(Set.of(), Set.of(), false);
    List<Received> batch = RepublishTestSupport.readBatch(report.batchId(), WAIT);

    assertThat(batch).hasSize((int) report.messages());
    assertThat(batch.getFirst().message().getMarker()).isEqualTo("BAT_DAU");
    Received end = batch.getLast();
    assertThat(end.message().getMarker()).isEqualTo("KET_THUC");
    assertThat(end.message().getIsLast()).isTrue();
    assertThat(end.message().getEntityCounts())
        .containsEntry("ORG_UNIT", 4L).containsEntry("PERSON", 40L).containsEntry("ASSIGNMENT", 40L);

    // Thứ tự đơn vị, nhân sự, phân công theo số thứ tự.
    List<Object> entities = batch.stream().map(r -> r.message().getEntity()).filter(e -> e != null).toList();
    assertThat(entities.subList(0, 4)).allMatch(DonViDaThayDoi.class::isInstance);
    assertThat(entities.subList(4, 44)).allMatch(NhanSuDaThayDoi.class::isInstance);
    assertThat(entities.subList(44, 84)).allMatch(PhanCongDaThayDoi.class::isInstance);

    // Khoá và tiêu đề như sự kiện thường.
    Received unit = batch.get(1);
    assertThat(unit.record().key()).startsWith(tenant + ":");
    assertThat(new String(unit.record().headers().lastHeader("tenantid").value(), StandardCharsets.UTF_8))
        .isEqualTo(tenant.toString());
    assertThat(unit.record().headers().lastHeader("aggregateversion")).isNotNull();

    // Lát đang mở của đơn vị con mang cấp trên hai cây và sourceVersion thật.
    DonViDaThayDoi open = entities.stream().filter(DonViDaThayDoi.class::isInstance).map(DonViDaThayDoi.class::cast)
        .filter(u -> u.getUnitId().equals(child) && u.getValidTo() == null).findFirst().orElseThrow();
    assertThat(open.getName()).isEqualTo("Sở mới");
    assertThat(open.getSourceVersion()).isEqualTo(2L);
    assertThat(open.getPartyParentId()).isEqualTo(root);
    assertThat(open.getAdminParentId()).isEqualTo(root);

    Replica replica = new Replica();
    replica.apply(batch);
    Integer[] buckets = ALL.toArray(Integer[]::new);
    assertThat(replica.bucketHashes(ALL)).isEqualTo(end.message().getBucketHashes())
        .isEqualTo(dry.bucketHashes()).containsKeys(java.util.Arrays.stream(buckets).map(String::valueOf)
            .toArray(String[]::new));

    List<AuditEvent> audits = RepublishTestSupport.audits(report.batchId());
    assertThat(audits).singleElement().satisfies(a -> {
      assertThat(a.getAction()).isEqualTo(OrgRepublishService.AUDIT_ACTION);
      assertThat(a.getOutcome()).isEqualTo("THANH_CONG");
      assertThat(a.getPurpose()).contains("count=84").contains("buckets=ALL");
    });
  }

  @Test
  void phatLaiMotBucketChiPhatThucTheThuocBucketDo() {
    int bucket = Buckets.of(persons.getFirst());
    RepublishReport full = run(Set.of(), Set.of(), true);
    RepublishReport report = run(Set.of(), Set.of(bucket), false);
    List<Received> batch = RepublishTestSupport.readBatch(report.batchId(), WAIT);

    List<UUID> ids = batch.stream().map(r -> r.message().getEntity()).filter(e -> e != null).map(e -> switch (e) {
      case DonViDaThayDoi u -> u.getUnitId();
      case NhanSuDaThayDoi p -> p.getPersonId();
      case PhanCongDaThayDoi a -> a.getAssignmentId();
      default -> throw new IllegalStateException();
    }).toList();
    assertThat(ids).isNotEmpty().allMatch(id -> Buckets.of(id) == bucket).contains(persons.getFirst());
    assertThat(batch.getLast().message().getBucketHashes())
        .containsOnlyKeys(String.valueOf(bucket))
        .containsEntry(String.valueOf(bucket), full.bucketHashes().get(String.valueOf(bucket)));
  }

  @Test
  void chayLaiHaiLanKhongDoiBanSao() {
    Replica replica = new Replica();
    replica.apply(RepublishTestSupport.readBatch(run(Set.of(), Set.of(), false).batchId(), WAIT));
    var first = replica.rows();
    var firstHashes = replica.bucketHashes(ALL);
    replica.apply(RepublishTestSupport.readBatch(run(Set.of(), Set.of(), false).batchId(), WAIT));
    assertThat(replica.rows()).isEqualTo(first);
    assertThat(replica.bucketHashes(ALL)).isEqualTo(firstHashes);
  }

  @Test
  void chayThuKhongPhatNhungVanGhiKiemToan() {
    RepublishReport report = run(Set.of(EntityKind.PERSON), Set.of(), true);
    assertThat(report.counts()).containsOnlyKeys("PERSON").containsEntry("PERSON", 40L);
    assertThat(report.messages()).isZero();
    assertThat(RepublishTestSupport.readBatch(report.batchId(), Duration.ofSeconds(2))).isEmpty();
    assertThat(RepublishTestSupport.audits(report.batchId())).singleElement()
        .satisfies(a -> assertThat(a.getPurpose()).contains("dryRun=true"));
  }

  @Test
  void chiPhatBamChoDoiSoatKhongPhatThucThe() {
    RepublishReport dry = run(Set.of(), Set.of(), true);
    RepublishReport report;
    TenantContextHolder.set(tenant);
    try {
      report = service.run(new RepublishRequest(tenant, Set.of(), Set.of(), null, false, "doi-soat", true));
    } finally {
      TenantContextHolder.clear();
    }
    List<Received> batch = RepublishTestSupport.readBatch(report.batchId(), WAIT);
    assertThat(batch).extracting(r -> r.message().getMarker()).containsExactly("BAT_DAU", "KET_THUC");
    assertThat(batch.getLast().message().getSeq()).isEqualTo(1L);
    assertThat(batch.getLast().message().getBucketHashes()).isEqualTo(dry.bucketHashes());
    assertThat(batch.getLast().message().getEntityCounts()).containsEntry("PERSON", 40L);
  }
}
