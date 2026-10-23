package vn.viettel.okrkpi.oversight.application.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.IntNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.oversight.application.alert.AnomalyAlertIntake.Outcome;
import vn.viettel.okrkpi.oversight.application.port.AlertHandlers;
import vn.viettel.okrkpi.oversight.application.port.AlertNotices;
import vn.viettel.okrkpi.oversight.domain.alert.AlertRules;
import vn.viettel.okrkpi.oversight.domain.alert.AlertSignal;
import vn.viettel.okrkpi.oversight.domain.alert.AnomalyAlert;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-07-10: chống trùng, cờ chặn, tự đóng, gộp cấp đơn vị, thông báo tổng hợp. */
class AnomalyAlertIntakeTest {

  static final TenantId TENANT =
      TenantId.of(UUID.fromString("0192f000-0000-7000-8000-00000000a001"));
  static final UUID KY = UUID.fromString("0192f000-0000-7000-8000-0000000000a4");
  static final UUID HIEN_HANH = UUID.fromString("0192f000-0000-7000-8000-0000000000a5");
  static final UUID PHONG_CHA = UUID.randomUUID();

  final Clock clock = Clock.fixed(Instant.parse("2026-11-03T19:05:00Z"), ZoneOffset.UTC);
  final FakeAlertRepository repo = new FakeAlertRepository();
  final Map<UUID, UUID> parents = new HashMap<>();
  final Map<UUID, Set<UUID>> reviewers = new HashMap<>();
  Integer threshold = 3;

  final PeriodApi periods =
      new PeriodApi() {
        @Override
        public Optional<PeriodSnapshot> findCurrent(TenantId tenantId) {
          return Optional.of(snapshot(HIEN_HANH));
        }

        @Override
        public Optional<PeriodSnapshot> findById(TenantId tenantId, PeriodId id) {
          return id.value().equals(KY) || id.value().equals(HIEN_HANH)
              ? Optional.of(snapshot(id.value()))
              : Optional.empty();
        }

        @Override
        public void assertOpen(TenantId tenantId, PeriodId periodId) throws PeriodLockedException {}
      };

  final AlertHandlers handlers =
      new AlertHandlers() {
        @Override
        public Set<UUID> ofProfiles(TenantId t, PeriodId p, Collection<UUID> ids) {
          return reviewers.getOrDefault(ids.iterator().next(), Set.of());
        }

        @Override
        public Set<UUID> ofUnits(TenantId t, PeriodId p, Collection<UUID> ids) {
          return reviewers.getOrDefault(ids.iterator().next(), Set.of());
        }

        @Override
        public UUID parentOf(TenantId t, UUID unit) {
          return parents.get(unit);
        }

        @Override
        public Collection<UUID> subunitsOf(TenantId t, UUID unit) {
          return parents.entrySet().stream()
              .filter(e -> e.getValue().equals(unit))
              .map(Map.Entry::getKey)
              .toList();
        }
      };

  final AnomalyAlertIntake intake =
      new AnomalyAlertIntake(
          repo,
          periods,
          handlers,
          (t, key, unit) ->
              threshold == null ? Optional.empty() : Optional.of(IntNode.valueOf(threshold)),
          clock);

  static PeriodSnapshot snapshot(UUID id) {
    return new PeriodSnapshot(
        PeriodId.of(id),
        TENANT,
        "2026-Q4",
        "Quý IV/2026",
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 12, 31),
        PeriodStatus.MO);
  }

  static AlertSignal signal(
      String code, String severity, String type, UUID subject, List<String> evidence) {
    return new AlertSignal(
        UUID.randomUUID(),
        Instant.parse("2026-11-03T19:04:00Z"),
        "k",
        code,
        severity,
        type,
        subject,
        evidence,
        "2026.10.3");
  }

  @Test
  void sameKeyCreatesOneAlertAndEvidenceKeepsOnlyCodes() {
    UUID hoSo = UUID.randomUUID();
    List<String> chain =
        new ArrayList<>(
            List.of(
                "ky:" + KY,
                "appraisal_profile:" + hoSo,
                "so_lieu:CHENH_DIEM=6.5",
                "Ý kiến nghiệm thu: nội dung tự do",
                "email:nguoi@example.vn"));
    chain.add(null);
    assertThat(intake.receive(TENANT, signal("TU_CHAM_CAO", "VUA", "CA_NHAN", hoSo, chain)))
        .isEqualTo(Outcome.CREATED);
    assertThat(intake.receive(TENANT, signal("TU_CHAM_CAO", "CAO", "CA_NHAN", hoSo, chain)))
        .isEqualTo(Outcome.DUPLICATE);
    assertThat(repo.all()).hasSize(1);
    AnomalyAlert a = repo.all().get(0);
    assertThat(a.status()).isEqualTo("MOI");
    assertThat(a.periodId()).isEqualTo(KY);
    assertThat(a.periodBucket()).isEqualTo(20264);
    assertThat(a.evidence())
        .containsExactly("ky:" + KY, "appraisal_profile:" + hoSo, "so_lieu:CHENH_DIEM=6.5");
    assertThat(repo.locks).allMatch(k -> k.contains("TU_CHAM_CAO|CA_NHAN|" + hoSo + "|" + KY));
    assertThat(repo.actions).isEmpty();
  }

  @Test
  void duplicateEvidenceIsBlockedUntilResolutionClosesTheAlert() {
    UUID mc1 = UUID.randomUUID();
    UUID mc2 = UUID.randomUUID();
    UUID unit = UUID.randomUUID();
    List<String> chain =
        List.of("ky:" + KY, "evidence:" + mc1, "evidence:" + mc2, "sha256:5e88a1f4");
    intake.receive(TENANT, signal("MINH_CHUNG_TRUNG", "CAO", "DON_VI", unit, chain));
    assertThat(repo.blockedEvidence(TENANT, List.of(mc1, mc2, UUID.randomUUID())))
        .containsExactlyInAnyOrder(mc1, mc2);
    assertThat(repo.actions)
        .singleElement()
        .satisfies(x -> assertThat(x.action()).isEqualTo("TU_DONG"));

    assertThat(
            intake.receive(
                TENANT, signal("MINH_CHUNG_TRUNG", "HET_DAU_HIEU", "DON_VI", unit, chain)))
        .isEqualTo(Outcome.RESOLVED);
    assertThat(repo.all().get(0).status()).isEqualTo("DONG");
    assertThat(repo.blockedEvidence(TENANT, List.of(mc1, mc2))).isEmpty();
    assertThat(repo.actions).hasSize(2);
    // Lần nữa: không còn cảnh báo MOI để đóng; cảnh báo cũ không mở lại.
    assertThat(
            intake.receive(
                TENANT, signal("MINH_CHUNG_TRUNG", "HET_DAU_HIEU", "DON_VI", unit, chain)))
        .isEqualTo(Outcome.IGNORED);
    assertThat(intake.receive(TENANT, signal("MINH_CHUNG_TRUNG", "CAO", "DON_VI", unit, chain)))
        .isEqualTo(Outcome.DUPLICATE);
  }

  @Test
  void ledgerBreakIsAlwaysHighAndMissingPeriodUsesCurrent() {
    UUID hoSo = UUID.randomUUID();
    intake.receive(
        TENANT, signal("SUA_SAU_KHOA", "THAP", "CA_NHAN", hoSo, List.of("ledger_chain:12")));
    AnomalyAlert a = repo.all().get(0);
    assertThat(a.severity()).isEqualTo("CAO");
    assertThat(a.periodId()).isEqualTo(HIEN_HANH);
  }

  @Test
  void invalidSignalsAreRejected() {
    UUID s = UUID.randomUUID();
    assertThatThrownBy(
            () -> intake.receive(TENANT, signal("CB_01", "CAO", "CA_NHAN", s, List.of())))
        .isInstanceOf(AlertRules.InvalidSignal.class);
    assertThatThrownBy(
            () -> intake.receive(TENANT, signal("CHE_NHO", "HIGH", "CA_NHAN", s, List.of())))
        .isInstanceOf(AlertRules.InvalidSignal.class);
    assertThatThrownBy(() -> intake.receive(TENANT, signal("CHE_NHO", "CAO", "TASK", s, List.of())))
        .isInstanceOf(AlertRules.InvalidSignal.class);
    assertThatThrownBy(
            () ->
                intake.receive(
                    TENANT,
                    new AlertSignal(
                        UUID.randomUUID(),
                        Instant.now(),
                        "k",
                        "CHE_NHO",
                        "CAO",
                        "CA_NHAN",
                        s,
                        List.of(),
                        " ")))
        .isInstanceOf(AlertRules.InvalidSignal.class);
    assertThatThrownBy(
            () ->
                intake.receive(
                    TENANT,
                    signal("CHE_NHO", "CAO", "CA_NHAN", s, List.of("ky:" + UUID.randomUUID()))))
        .isInstanceOf(AlertRules.InvalidSignal.class)
        .hasMessageContaining("kỳ");
  }

  @Test
  void manyUnitAlertsOfSameSignalAreGroupedUnderParentUnit() {
    List<UUID> rooms = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      UUID r = UUID.randomUUID();
      parents.put(r, PHONG_CHA);
      rooms.add(r);
    }
    String[] sev = {"THAP", "VUA", "THAP", "CAO"};
    for (int i = 0; i < 2; i++) {
      intake.receive(
          TENANT, signal("CAP_NHAT_DON", sev[i], "DON_VI", rooms.get(i), List.of("ky:" + KY)));
    }
    assertThat(repo.all()).hasSize(2);
    intake.receive(
        TENANT, signal("CAP_NHAT_DON", sev[2], "DON_VI", rooms.get(2), List.of("ky:" + KY)));
    AnomalyAlert group =
        repo.all().stream().filter(a -> a.subjectId().equals(PHONG_CHA)).findFirst().orElseThrow();
    assertThat(group.severity()).isEqualTo("VUA");
    assertThat(group.evidence()).filteredOn(x -> x.startsWith("anomaly_alert:")).hasSize(3);
    assertThat(group.sourceEventId())
        .isEqualTo(AlertRules.groupSourceId(TENANT.value(), "CAP_NHAT_DON", PHONG_CHA, KY));

    intake.receive(
        TENANT, signal("CAP_NHAT_DON", sev[3], "DON_VI", rooms.get(3), List.of("ky:" + KY)));
    AnomalyAlert after = repo.rows.get(group.id());
    assertThat(after.evidence()).filteredOn(x -> x.startsWith("anomaly_alert:")).hasSize(4);
    assertThat(after.severity()).isEqualTo("CAO");
    assertThat(repo.all()).hasSize(5);
  }

  @Test
  void groupingIsOffBelowTwoOrWithoutParent() {
    threshold = 1;
    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();
    parents.put(r1, PHONG_CHA);
    parents.put(r2, PHONG_CHA);
    intake.receive(TENANT, signal("CAO_BANG", "VUA", "DON_VI", r1, List.of("ky:" + KY)));
    intake.receive(TENANT, signal("CAO_BANG", "VUA", "DON_VI", r2, List.of("ky:" + KY)));
    threshold = null;
    intake.receive(
        TENANT, signal("CAO_BANG", "VUA", "DON_VI", UUID.randomUUID(), List.of("ky:" + KY)));
    assertThat(repo.all()).hasSize(3).noneMatch(a -> a.subjectId().equals(PHONG_CHA));
  }

  @Test
  void digestSendsOneNoticePerHandlerWithCountOfNewHighAlerts() {
    UUID hoSo1 = UUID.randomUUID();
    UUID hoSo2 = UUID.randomUUID();
    UUID unit = UUID.randomUUID();
    UUID thamDinh = UUID.randomUUID();
    UUID truongPhong = UUID.randomUUID();
    reviewers.put(hoSo1, Set.of(thamDinh));
    reviewers.put(hoSo2, Set.of(thamDinh, truongPhong));
    reviewers.put(unit, Set.of(truongPhong));
    intake.receive(TENANT, signal("SUA_SAU_KHOA", "CAO", "CA_NHAN", hoSo1, List.of("ky:" + KY)));
    intake.receive(TENANT, signal("SUA_SAU_KHOA", "CAO", "CA_NHAN", hoSo2, List.of("ky:" + KY)));
    intake.receive(TENANT, signal("CAO_BANG", "CAO", "DON_VI", unit, List.of("ky:" + KY)));
    intake.receive(TENANT, signal("CHE_NHO", "VUA", "CA_NHAN", hoSo1, List.of("ky:" + KY)));
    intake.receive(
        TENANT,
        signal("MIEN_TRU_LAP", "CAO", "NGUOI_DUYET", UUID.randomUUID(), List.of("ky:" + KY)));
    Map<UUID, Integer> sent = new HashMap<>();
    AlertNotices notices = (t, r, n, b) -> sent.put(r, n);
    AnomalyDigest digest = new AnomalyDigest(repo, handlers, notices, clock);
    assertThat(digest.run(TENANT, UUID.randomUUID())).isEqualTo(2);
    assertThat(sent).containsEntry(thamDinh, 2).containsEntry(truongPhong, 2);
    sent.clear();
    assertThat(digest.run(TENANT, UUID.randomUUID())).isZero();
    assertThat(sent).isEmpty();
    assertThat(new AnomalyDigest(repo, null, notices, clock).run(TENANT, UUID.randomUUID()))
        .isZero();
  }

  @Test
  void rulesHelpers() {
    assertThat(AlertRules.bucket(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
        .isEqualTo(20260);
    assertThat(AlertRules.rank("THAP")).isLessThan(AlertRules.rank("VUA"));
    assertThat(AlertRules.evidenceIds(List.of("evidence:khong-phai-uuid", "evidence:" + KY)))
        .containsExactly(KY);
    assertThat(AlertRules.period(List.of("ky:x", "so_lieu:A=1"))).isEmpty();
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 600; i++) {
      many.add("task:" + i);
    }
    assertThat(AlertRules.sanitize(many)).hasSize(AlertRules.MAX_EVIDENCE);
  }
}
