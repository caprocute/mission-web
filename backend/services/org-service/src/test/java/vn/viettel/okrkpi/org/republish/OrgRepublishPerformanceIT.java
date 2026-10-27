package vn.viettel.okrkpi.org.republish;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.viettel.okrkpi.org.application.republish.OrgRepublishService;
import vn.viettel.okrkpi.org.application.republish.RepublishReport;
import vn.viettel.okrkpi.org.application.republish.RepublishRequest;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;

/**
 * DV-04-08 · Đo phát lại tenant 100.000 nhân sự (mỗi người một phân công chính) và 1.111 đơn vị lên Kafka
 * Testcontainers. Mục tiêu đề xuất dưới 30 phút (suy luận); kết quả ghi vào báo cáo task.
 */
@ActiveProfiles("republish")
class OrgRepublishPerformanceIT extends IntegrationTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(OrgRepublishPerformanceIT.class);
  static final int PERSONS = 100_000;

  @DynamicPropertySource
  static void kafka(DynamicPropertyRegistry registry) {
    OrgRepublishIT.kafka(registry);
  }

  @Autowired
  OrgRepublishService service;

  @Test
  void phatLai100000NhanSu() {
    UUID tenant = UUID.randomUUID();
    Fixtures fx = new Fixtures();
    UUID group = fx.subjectGroup(tenant, "K1");
    UUID title = fx.jobTitle(tenant);
    long seedStart = System.nanoTime();
    fx.admin().update("""
        INSERT INTO org.org_unit (version_id, id, tenant_id, code, name, unit_type, subject_group_id, flows, level,
          valid_from, created_by, updated_by)
        SELECT gen_random_uuid(), gen_random_uuid(), ?, 'U' || g, 'Đơn vị ' || g, 'CO_QUAN', ?, '{NHA_NUOC}', 'TINH',
          '2020-01-01', ?, ?
        FROM generate_series(1, 1111) g""", tenant, group, Fixtures.SYSTEM_ACTOR, Fixtures.SYSTEM_ACTOR);
    fx.admin().update("""
        INSERT INTO org.person (version_id, id, tenant_id, full_name, employment_status, valid_from, created_by,
          updated_by)
        SELECT gen_random_uuid(), gen_random_uuid(), ?, 'Cán bộ ' || g, 'DANG_CONG_TAC', '2000-01-01', ?, ?
        FROM generate_series(1, ?) g""", tenant, Fixtures.SYSTEM_ACTOR, Fixtures.SYSTEM_ACTOR, PERSONS);
    fx.admin().update("""
        WITH u AS (SELECT array_agg(id) AS ids FROM org.org_unit WHERE tenant_id = ?)
        INSERT INTO org.assignment (version_id, id, tenant_id, person_id, org_unit_id, job_title_id, kind, branch,
          valid_from, created_by, updated_by)
        SELECT gen_random_uuid(), gen_random_uuid(), p.tenant_id, p.id,
          u.ids[1 + (abs(hashtext(p.id::text)) % array_length(u.ids, 1))], ?, 'CHINH', 'CHINH_QUYEN', '2024-01-01', ?, ?
        FROM org.person p, u WHERE p.tenant_id = ?""", tenant, title, Fixtures.SYSTEM_ACTOR, Fixtures.SYSTEM_ACTOR,
        tenant);
    fx.admin().execute("ANALYZE org.org_unit, org.person, org.assignment");
    LOG.info("Sinh dữ liệu: {} ms", (System.nanoTime() - seedStart) / 1_000_000);

    TenantContextHolder.set(tenant);
    RepublishReport report;
    try {
      report = service.run(new RepublishRequest(tenant, Set.of(), Set.of(), null, false, "do-hieu-nang"));
    } finally {
      TenantContextHolder.clear();
    }
    long entities = report.entities();
    LOG.info("org-republish {} thực thể ({} thông điệp) trong {} ms, {} thực thể/giây", entities, report.messages(),
        report.duration().toMillis(), entities * 1000 / Math.max(1, report.duration().toMillis()));
    assertThat(report.counts()).containsEntry("PERSON", (long) PERSONS).containsEntry("ASSIGNMENT", (long) PERSONS)
        .containsEntry("ORG_UNIT", 1111L);
    assertThat(report.duration()).isLessThan(Duration.ofMinutes(30));

    long readStart = System.nanoTime();
    var batch = RepublishTestSupport.readBatch(report.batchId(), Duration.ofMinutes(5));
    LOG.info("Bên nhận thử đọc {} thông điệp trong {} ms", batch.size(), (System.nanoTime() - readStart) / 1_000_000);
    assertThat(batch).hasSize((int) report.messages());
    RepublishTestSupport.Replica replica = new RepublishTestSupport.Replica();
    replica.apply(batch);
    assertThat(replica.bucketHashes(OrgRepublishIT.ALL)).isEqualTo(batch.getLast().message().getBucketHashes());
  }
}
