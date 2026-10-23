package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.UiPackCompatibilityApi;
import vn.viettel.okrkpi.policy.application.port.PolicyAudit;
import vn.viettel.okrkpi.policy.application.port.PolicyPackDistribution;
import vn.viettel.okrkpi.policy.application.port.PolicyPackDistribution.DistributionFailedException;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishSagaRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyPublishedEvents;
import vn.viettel.okrkpi.policy.application.port.PolicyWorkflowActivation;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyBinding;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest;
import vn.viettel.okrkpi.policy.domain.PolicyPublishRequest.Scope;
import vn.viettel.okrkpi.policy.domain.PolicyPublishSaga;
import vn.viettel.okrkpi.policy.domain.PolicyPublishSaga.Status;
import vn.viettel.okrkpi.policy.domain.PolicyPublishSaga.Step;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-05-10: các bước saga phát hành, bù trừ, phát hành lại, thu hồi. */
class PolicyPublishSagaServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID author = UUID.randomUUID();
  private final UUID approver = UUID.randomUUID();
  private final UUID periodId = UUID.randomUUID();
  private final UUID oldVersionId = UUID.randomUUID();
  private final PolicyPack pack =
      new PolicyPack(
          UUID.randomUUID(),
          "khoi-dang",
          "Bộ quy định khối Đảng",
          "DANG",
          "DANG",
          PackOrigin.GOC_TOAN_CUC,
          null,
          "QĐ 366",
          NOW,
          author,
          0);
  private final Scope dang = new Scope("DANG", null, null);
  private final Scope nhaNuoc = new Scope("NHA_NUOC", null, null);

  private PolicyPackRepository packs;
  private PolicyPublishRepository publish;
  private FakeSagas sagas;
  private UiPackCompatibilityApi uiPacks;
  private PeriodApi periods;
  private PolicyPackDistribution distribution;
  private PolicyWorkflowActivation workflow;
  private PolicyPublishedEvents events;
  private PolicyAudit audit;
  private PolicyResolutionService resolution;
  private PolicyPublishSagaService service;
  private final AtomicReference<PolicyPackVersion> stored = new AtomicReference<>();
  private final ObjectMapper json = new ObjectMapper();
  private PolicyPublishRequest request;

  @BeforeEach
  void setUp() {
    packs = mock(PolicyPackRepository.class);
    publish = mock(PolicyPublishRepository.class);
    sagas = new FakeSagas();
    uiPacks = mock(UiPackCompatibilityApi.class);
    periods = mock(PeriodApi.class);
    distribution = mock(PolicyPackDistribution.class);
    workflow = mock(PolicyWorkflowActivation.class);
    events = mock(PolicyPublishedEvents.class);
    audit = mock(PolicyAudit.class);
    resolution = mock(PolicyResolutionService.class);
    Clock clock = () -> NOW;
    service =
        new PolicyPublishSagaService(
            packs,
            publish,
            sagas,
            uiPacks,
            periods,
            distribution,
            workflow,
            events,
            audit,
            resolution,
            json,
            clock,
            Runnable::run,
            TransactionOperations.withoutTransaction(),
            Duration.ZERO);
    when(packs.findPack(tenant, pack.id())).thenReturn(Optional.of(pack));
    when(packs.rules(any(), any())).thenReturn(List.of());
    when(packs.decisionTables(any(), any())).thenReturn(List.of());
    when(packs.storeStatus(any(), any(), anyLong()))
        .thenAnswer(
            inv -> {
              PolicyPackVersion v = inv.getArgument(1);
              if (stored.get() != null && v.id().equals(stored.get().id())) {
                stored.set(v);
              }
              return true;
            });
    when(packs.findVersionById(eq(tenant), any()))
        .thenAnswer(
            inv ->
                Optional.ofNullable(stored.get()).filter(v -> v.id().equals(inv.getArgument(1))));
    when(uiPacks.isCompatible(tenant, "khoi-dang@2.2.0")).thenReturn(true);
    period(LocalDate.of(2027, 1, 1), PeriodStatus.MO);
  }

  private String hash() {
    return PolicyContentHash.of(json, "{}", List.of(), List.of());
  }

  private PolicyPackVersion version(
      UUID id, String number, PolicyVersionStatus status, LocalDate effective, long rowVersion) {
    return new PolicyPackVersion(
        id,
        pack.id(),
        number,
        status,
        effective,
        "{}",
        hash(),
        BigDecimal.ONE,
        UUID.randomUUID(),
        true,
        approver,
        NOW,
        NOW,
        status == PolicyVersionStatus.DA_PHAT_HANH ? NOW : null,
        null,
        "Ghi chú",
        NOW,
        author,
        NOW,
        approver,
        rowVersion);
  }

  private PolicyPackVersion signed(LocalDate effective) {
    PolicyPackVersion v =
        version(UUID.randomUUID(), "2.2.0", PolicyVersionStatus.CHO_PHE_DUYET, effective, 6);
    stored.set(v);
    when(packs.findVersion(tenant, pack.id(), "2.2.0"))
        .thenAnswer(inv -> Optional.of(stored.get()));
    request =
        new PolicyPublishRequest(
            UUID.randomUUID(),
            v.id(),
            periodId,
            List.of(dang, nhaNuoc),
            null,
            PolicyPublishRequest.Status.DA_DUYET,
            author,
            NOW,
            approver,
            NOW,
            null,
            1);
    when(publish.findById(tenant, request.id())).thenReturn(Optional.of(request));
    when(publish.findApproved(tenant, v.id())).thenReturn(Optional.of(request));
    return v;
  }

  private void period(LocalDate start, PeriodStatus status) {
    when(periods.findById(tenant, PeriodId.of(periodId)))
        .thenReturn(
            Optional.of(
                new PeriodSnapshot(
                    PeriodId.of(periodId),
                    tenant,
                    "Q1-2027",
                    "Quý I/2027",
                    start,
                    start.plusMonths(3).minusDays(1),
                    status)));
  }

  @Test
  void publishesThroughAllStepsAndEmitsOneEvent() {
    PolicyPackVersion v = signed(LocalDate.of(2027, 1, 1));
    UUID existing = sagas.bind(periodId, dang, oldVersionId, false);

    String jobId = service.launch(tenant, approver, v, request);

    PolicyPublishSaga saga = sagas.find(tenant, UUID.fromString(jobId)).orElseThrow();
    assertThat(saga.status()).isEqualTo(Status.HOAN_TAT);
    assertThat(saga.step()).isEqualTo(Step.PHAT_HANH);
    assertThat(saga.finishedAt()).isEqualTo(NOW);
    assertThat(saga.context().workflowActivated()).isTrue();
    assertThat(saga.context().bindings()).hasSize(2);
    assertThat(sagas.versionOf(existing)).isEqualTo(v.id());
    assertThat(sagas.rows).hasSize(2);
    assertThat(stored.get().status()).isEqualTo(PolicyVersionStatus.DA_PHAT_HANH);
    assertThat(stored.get().publishedAt()).isEqualTo(NOW);
    verify(distribution).distribute(tenant, pack, v);
    verify(workflow).activate(eq(tenant), any());
    verify(events, times(1)).published(eq(tenant), any());
    verify(audit)
        .changed(
            eq(tenant), eq(approver), eq("PHAT_HANH_POLICY_PACK"), any(), any(), any(), anyLong());
    // Phiên bản mới chưa tới ngày hiệu lực: phiên bản cũ vẫn áp dụng.
    verify(packs, never()).listVersions(any(), any(), any(), any(), anyInt());
  }

  @Test
  void retiresPreviousVersionWhenNewOneIsAlreadyEffective() {
    PolicyPackVersion v = signed(TODAY);
    PolicyPackVersion old =
        version(oldVersionId, "2.1.0", PolicyVersionStatus.DA_PHAT_HANH, null, 9);
    when(packs.listVersions(tenant, pack.id(), PolicyVersionStatus.DA_PHAT_HANH, null, 200))
        .thenAnswer(inv -> List.of(stored.get(), old));

    service.launch(tenant, approver, v, request);

    verify(packs)
        .storeStatus(
            eq(tenant),
            org.mockito.ArgumentMatchers.argThat(
                x -> x.id().equals(oldVersionId) && x.status() == PolicyVersionStatus.NGUNG),
            eq(9L));
  }

  @Test
  void incompatibleUiPackStopsBeforeBinding() {
    PolicyPackVersion v = signed(LocalDate.of(2027, 1, 1));
    when(uiPacks.isCompatible(tenant, "khoi-dang@2.2.0")).thenReturn(false);

    String jobId = service.launch(tenant, approver, v, request);

    PolicyPublishSaga saga = sagas.find(tenant, UUID.fromString(jobId)).orElseThrow();
    assertThat(saga.status()).isEqualTo(Status.DA_BU_TRU);
    assertThat(saga.step()).isEqualTo(Step.KIEM_TUONG_THICH_UI);
    assertThat(saga.lastError()).contains("chưa tương thích");
    assertThat(sagas.rows).isEmpty();
    assertThat(stored.get().status()).isEqualTo(PolicyVersionStatus.CHO_PHE_DUYET);
    verify(events, never()).published(any(), any());
  }

  @Test
  void distributionFailureRetriesThenCompensatesBindings() {
    PolicyPackVersion v = signed(LocalDate.of(2027, 1, 1));
    UUID existing = sagas.bind(periodId, dang, oldVersionId, false);
    doThrow(new DistributionFailedException("Cell 2 không nạp được gói"))
        .when(distribution)
        .distribute(any(), any(), any());

    String jobId = service.launch(tenant, approver, v, request);

    PolicyPublishSaga saga = sagas.find(tenant, UUID.fromString(jobId)).orElseThrow();
    assertThat(saga.status()).isEqualTo(Status.DA_BU_TRU);
    assertThat(saga.step()).isEqualTo(Step.DONG_BO_CELL);
    assertThat(saga.attempts()).isEqualTo(PolicyPublishSagaService.MAX_ATTEMPTS - 1);
    assertThat(saga.lastError()).contains("Cell 2");
    assertThat(saga.context().bindings()).isEmpty();
    verify(distribution, times(PolicyPublishSagaService.MAX_ATTEMPTS))
        .distribute(any(), any(), any());
    assertThat(sagas.rows).containsOnlyKeys(existing);
    assertThat(sagas.versionOf(existing)).isEqualTo(oldVersionId);
    assertThat(stored.get().status()).isEqualTo(PolicyVersionStatus.CHO_PHE_DUYET);
    assertThat(stored.get().signed()).isTrue();
    verify(workflow, never()).activate(any(), any());
    verify(events, never()).published(any(), any());
  }

  @Test
  void failureAfterWorkflowActivationDeactivatesIt() {
    PolicyPackVersion v = signed(LocalDate.of(2027, 1, 1));
    org.mockito.Mockito.doReturn(false).when(packs).storeStatus(any(), any(), anyLong());

    String jobId = service.launch(tenant, approver, v, request);

    PolicyPublishSaga saga = sagas.find(tenant, UUID.fromString(jobId)).orElseThrow();
    assertThat(saga.status()).isEqualTo(Status.DA_BU_TRU);
    assertThat(saga.step()).isEqualTo(Step.PHAT_HANH);
    verify(workflow).deactivate(eq(tenant), any());
    assertThat(sagas.rows).isEmpty();
  }

  @Test
  void frozenBindingAbortsAndLockedPeriodAborts() {
    PolicyPackVersion v = signed(LocalDate.of(2027, 1, 1));
    sagas.bind(periodId, nhaNuoc, oldVersionId, true);
    String first = service.launch(tenant, approver, v, request);
    assertThat(sagas.find(tenant, UUID.fromString(first)).orElseThrow().status())
        .isEqualTo(Status.DA_BU_TRU);

    period(LocalDate.of(2027, 1, 1), PeriodStatus.DA_KHOA);
    String second = service.launch(tenant, approver, v, request);
    assertThat(sagas.find(tenant, UUID.fromString(second)).orElseThrow().lastError())
        .contains("đã khoá");
  }

  @Test
  void compensationThatCannotUndoNeedsAttention() {
    PolicyPackVersion v = signed(LocalDate.of(2027, 1, 1));
    sagas.freezeOnWrite = true;
    doThrow(new IllegalStateException("lỗi")).when(workflow).activate(any(), any());

    String jobId = service.launch(tenant, approver, v, request);

    PolicyPublishSaga saga = sagas.find(tenant, UUID.fromString(jobId)).orElseThrow();
    assertThat(saga.status()).isEqualTo(Status.LOI_CAN_XU_LY);
    assertThat(saga.lastError()).contains("bù trừ lỗi");
  }

  @Test
  void republishRunsAgainWithoutSigning() {
    PolicyPackVersion v = signed(LocalDate.of(2027, 1, 1));
    when(uiPacks.isCompatible(tenant, "khoi-dang@2.2.0")).thenReturn(false, true);
    String first = service.launch(tenant, approver, v, request);
    assertThat(sagas.find(tenant, UUID.fromString(first)).orElseThrow().status())
        .isEqualTo(Status.DA_BU_TRU);

    String second = service.republish(tenant, author, pack.id(), "2.2.0", 6);

    assertThat(second).isNotEqualTo(first);
    assertThat(sagas.find(tenant, UUID.fromString(second)).orElseThrow().status())
        .isEqualTo(Status.HOAN_TAT);
    assertThat(stored.get().status()).isEqualTo(PolicyVersionStatus.DA_PHAT_HANH);
    verify(audit)
        .changed(
            eq(tenant),
            eq(author),
            eq("PHAT_HANH_LAI_POLICY_PACK"),
            any(),
            eq("khoi-dang@2.2.0"),
            any(),
            anyLong());
  }

  @Test
  void republishGuards() {
    PolicyPackVersion v = signed(LocalDate.of(2027, 1, 1));
    assertThatThrownBy(() -> service.republish(tenant, author, pack.id(), "2.2.0", 5))
        .isInstanceOf(VersionConflictException.class);
    when(packs.findVersion(tenant, pack.id(), "9.9.9")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.republish(tenant, author, pack.id(), "9.9.9", 6))
        .isInstanceOf(NoSuchElementException.class);

    // Saga đang chạy, chưa quá hạn.
    sagas.insert(
        tenant, PolicyPublishSaga.start(UUID.randomUUID(), v.id(), request.id(), approver, NOW));
    assertThat(code(() -> service.republish(tenant, author, pack.id(), "2.2.0", 6)))
        .isEqualTo("POLICY-409-VERSION-CONFLICT");

    // Băm nội dung lệch: phải ký lại.
    stored.set(
        new PolicyPackVersion(
            v.id(),
            v.packId(),
            v.version(),
            v.status(),
            v.effectiveFrom(),
            "{\"a\":1}",
            v.contentHash(),
            v.goldenPassRate(),
            v.lastTestRunId(),
            true,
            approver,
            NOW,
            NOW,
            null,
            null,
            "Ghi chú",
            NOW,
            author,
            NOW,
            approver,
            6));
    assertThat(code(() -> service.republish(tenant, author, pack.id(), "2.2.0", 6)))
        .isEqualTo("POLICY-409-NOT-SIGNED");

    stored.set(version(v.id(), "2.2.0", PolicyVersionStatus.DA_PHAT_HANH, null, 6));
    assertThat(code(() -> service.republish(tenant, author, pack.id(), "2.2.0", 6)))
        .isEqualTo("POLICY-409-VERSION-CONFLICT");

    stored.set(v);
    when(publish.findApproved(tenant, v.id())).thenReturn(Optional.empty());
    sagas.saga.clear();
    assertThat(code(() -> service.republish(tenant, author, pack.id(), "2.2.0", 6)))
        .isEqualTo("POLICY-409-NOT-SIGNED");
  }

  @Test
  void republishCompensatesStaleRun() {
    PolicyPackVersion v = signed(LocalDate.of(2027, 1, 1));
    UUID existing = sagas.bind(periodId, dang, oldVersionId, false);
    PolicyPublishSaga stale =
        PolicyPublishSaga.start(
            UUID.randomUUID(), v.id(), request.id(), approver, NOW.minus(Duration.ofHours(1)));
    stale =
        stale.advance(
            stale.context().withBinding(new PolicyPublishSaga.Bound(existing, oldVersionId)),
            NOW.minus(Duration.ofHours(1)));
    sagas.insert(tenant, stale);
    sagas.rebind(tenant, existing, v.id(), approver);

    service.republish(tenant, author, pack.id(), "2.2.0", 6);

    assertThat(sagas.find(tenant, stale.id()).orElseThrow().status()).isEqualTo(Status.DA_BU_TRU);
    assertThat(stored.get().status()).isEqualTo(PolicyVersionStatus.DA_PHAT_HANH);
  }

  @Test
  void revokeRestoresPreviousBindingsAndReplaysEvent() {
    PolicyPackVersion v = signed(LocalDate.of(2027, 1, 1));
    UUID existing = sagas.bind(periodId, dang, oldVersionId, false);
    service.launch(tenant, approver, v, request);
    PolicyPackVersion published = stored.get();
    PolicyPackVersion old =
        version(oldVersionId, "2.1.0", PolicyVersionStatus.DA_PHAT_HANH, null, 9);
    when(packs.listVersions(tenant, pack.id(), PolicyVersionStatus.DA_PHAT_HANH, null, 200))
        .thenReturn(List.of(published, old));
    when(packs.bindings(any(), any())).thenReturn(List.of());

    PolicyPackVersion revoked =
        service.revoke(
            tenant,
            approver,
            pack.id(),
            "2.2.0",
            published.rowVersion(),
            "Sai ngưỡng điểm xuất sắc");

    assertThat(revoked.status()).isEqualTo(PolicyVersionStatus.NGUNG);
    assertThat(sagas.versionOf(existing)).isEqualTo(oldVersionId);
    assertThat(sagas.rows).containsOnlyKeys(existing);
    verify(workflow).deactivate(tenant, published);
    verify(events).published(tenant, old);
    verify(audit)
        .changed(
            eq(tenant), eq(approver), eq("THU_HOI_POLICY_PACK"), any(), any(), any(), anyLong());
  }

  @Test
  void revokeGuards() {
    PolicyPackVersion v = signed(LocalDate.of(2027, 1, 1));
    assertThat(
            code(() -> service.revoke(tenant, approver, pack.id(), "2.2.0", 6, "Sai ngưỡng điểm")))
        .isEqualTo("POLICY-409-VERSION-CONFLICT");
    assertThat(code(() -> service.revoke(tenant, approver, pack.id(), "2.2.0", 6, "ngắn")))
        .isEqualTo("COMMON-422-VALIDATION");
    assertThatThrownBy(() -> service.revoke(tenant, approver, pack.id(), "2.2.0", 1, "Sai ngưỡng"))
        .isInstanceOf(VersionConflictException.class);

    stored.set(version(v.id(), "2.2.0", PolicyVersionStatus.DA_PHAT_HANH, null, 6));
    UUID bindingId = UUID.randomUUID();
    when(packs.bindings(tenant, v.id()))
        .thenReturn(List.of(new PolicyBinding(bindingId, periodId, "DANG", null, null, true)));
    assertThat(
            code(() -> service.revoke(tenant, approver, pack.id(), "2.2.0", 6, "Sai ngưỡng điểm")))
        .isEqualTo("POLICY-409-PERIOD-LOCKED");

    when(packs.bindings(tenant, v.id())).thenReturn(List.of());
    period(LocalDate.of(2026, 10, 1), PeriodStatus.MO);
    assertThat(
            code(() -> service.revoke(tenant, approver, pack.id(), "2.2.0", 6, "Sai ngưỡng điểm")))
        .isEqualTo("POLICY-409-PERIOD-STARTED");

    period(LocalDate.of(2027, 1, 1), PeriodStatus.DA_KHOA);
    assertThat(
            code(() -> service.revoke(tenant, approver, pack.id(), "2.2.0", 6, "Sai ngưỡng điểm")))
        .isEqualTo("POLICY-409-PERIOD-LOCKED");

    // Gắn còn lại của phiên bản (không do saga ghi) được gỡ.
    period(LocalDate.of(2027, 1, 1), PeriodStatus.MO);
    UUID loose = sagas.bind(periodId, dang, v.id(), false);
    when(packs.bindings(tenant, v.id()))
        .thenReturn(List.of(new PolicyBinding(loose, periodId, "DANG", null, null, false)));
    when(packs.listVersions(any(), any(), any(), any(), anyInt())).thenReturn(List.of());
    service.revoke(tenant, approver, pack.id(), "2.2.0", 6, "Sai ngưỡng điểm");
    assertThat(sagas.rows).isEmpty();
    verify(events, never()).published(any(), any());
  }

  private static String code(Runnable call) {
    try {
      call.run();
    } catch (BusinessException e) {
      return e.errorCode().code();
    }
    return null;
  }

  /** Lưu saga và gắn kỳ trong bộ nhớ. */
  private final class FakeSagas implements PolicyPublishSagaRepository {
    final Map<UUID, PolicyPublishSaga> saga = new HashMap<>();
    final Map<UUID, Object[]> rows = new HashMap<>();
    final List<UUID> order = new ArrayList<>();
    boolean freezeOnWrite;

    UUID bind(UUID period, Scope scope, UUID versionId, boolean frozen) {
      UUID id = UUID.randomUUID();
      rows.put(id, new Object[] {period, scope, versionId, frozen});
      return id;
    }

    UUID versionOf(UUID bindingId) {
      return (UUID) rows.get(bindingId)[2];
    }

    @Override
    public void insert(TenantId t, PolicyPublishSaga s) {
      saga.put(s.id(), s);
      order.add(s.id());
    }

    @Override
    public boolean store(TenantId t, PolicyPublishSaga s, long expected) {
      PolicyPublishSaga cur = saga.get(s.id());
      if (cur == null || cur.rowVersion() != expected) {
        return false;
      }
      saga.put(s.id(), s);
      return true;
    }

    @Override
    public Optional<PolicyPublishSaga> find(TenantId t, UUID id) {
      return Optional.ofNullable(saga.get(id));
    }

    @Override
    public Optional<PolicyPublishSaga> findLatest(TenantId t, UUID versionId) {
      for (int i = order.size() - 1; i >= 0; i--) {
        PolicyPublishSaga s = saga.get(order.get(i));
        if (s != null && s.versionId().equals(versionId)) {
          return Optional.of(s);
        }
      }
      return Optional.empty();
    }

    @Override
    public Optional<BindingRow> findBinding(TenantId t, UUID period, Scope scope) {
      return rows.entrySet().stream()
          .filter(e -> e.getValue()[0].equals(period) && e.getValue()[1].equals(scope))
          .map(e -> new BindingRow(e.getKey(), (UUID) e.getValue()[2], (Boolean) e.getValue()[3]))
          .findFirst();
    }

    @Override
    public void insertBinding(
        TenantId t, UUID id, UUID versionId, UUID period, Scope scope, UUID actor) {
      rows.put(id, new Object[] {period, scope, versionId, freezeOnWrite});
    }

    @Override
    public boolean rebind(TenantId t, UUID id, UUID versionId, UUID actor) {
      Object[] row = rows.get(id);
      if (row == null || (Boolean) row[3]) {
        return false;
      }
      row[2] = versionId;
      return true;
    }

    @Override
    public boolean unbind(TenantId t, UUID id) {
      Object[] row = rows.get(id);
      if (row == null || (Boolean) row[3]) {
        return false;
      }
      rows.remove(id);
      return true;
    }

    @Override
    public List<PolicyBinding> bindings(TenantId t, UUID versionId) {
      return packs.bindings(t, versionId);
    }
  }
}
