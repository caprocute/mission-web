package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodCalendarPort.PeriodCalendar;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.WorkingCalendarApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.PolicyContent;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.ResolvedPack;
import vn.viettel.okrkpi.policy.application.port.PolicyPackRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository;
import vn.viettel.okrkpi.policy.application.port.PolicyResolutionRepository.PublishedVersion;
import vn.viettel.okrkpi.policy.domain.DecisionTable;
import vn.viettel.okrkpi.policy.domain.PackOrigin;
import vn.viettel.okrkpi.policy.domain.PolicyPack;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyRule;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phân giải theo gắn và theo khoảng hiệu lực, đệm, tham số, vòng đời nhiệm vụ, cổng lịch kỳ và cổng
 * schema của MOD-08 (CORE-05-03).
 */
class PolicyResolutionServiceTest {

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final PeriodId period = PeriodId.of(UUID.randomUUID());
  private final PolicyResolutionRepository repo = mock(PolicyResolutionRepository.class);
  private final PolicyPackRepository packs = mock(PolicyPackRepository.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final PolicyResolutionService service =
      new PolicyResolutionService(
          repo, packs, () -> periods, new ObjectMapper(), Duration.ofMinutes(5));

  private final UUID versionId = UUID.randomUUID();
  private final ResolvedPack resolved =
      new ResolvedPack(
          UUID.randomUUID(),
          "khoi-dang/quy-dinh-moi",
          versionId,
          "2.0.0",
          "ab",
          "k",
          UUID.randomUUID(),
          false);

  private static final String MANIFEST =
      """
      {"metadata":{"id":"khoi-dang/quy-dinh-moi","hieu_luc":{"tu_ky":"2027-Q1","den_ky":null}},
       "params":{"tru_tien_do":{"type":"double","mac_dinh":0.05},"phang":3,
                 "plan_approval_deadline_rule":{"mac_dinh":"NGAY_LAM_VIEC_CUOI_TUAN_LICH_THU_2"}},
       "inputs":{"ty_le":{"type":"double"},"so_lan":{"type":"int"},"ds":{"type":"list<NhiemVu>"},
                 "ten":{"type":"string"},"co":{"type":"bool"},"la":"x"},
       "forms":{"mau-1b":{"properties":{"diem":{},"nhan_xet":{}}},
                "mau-2":{"fields":[{"name":"a"},"b"]},"rong":{}},
       "task_lifecycle":{"tep":"task_lifecycle.yaml"}}
      """;

  private PublishedVersion published(
      UUID id,
      String code,
      String origin,
      String flow,
      String version,
      PolicyVersionStatus s,
      String manifest) {
    return new PublishedVersion(
        UUID.randomUUID(), code, origin, flow, id, version, s, "ab", "bundle", null, manifest);
  }

  private PeriodSnapshot snapshot(LocalDate start, PeriodStatus status) {
    return new PeriodSnapshot(
        period, tenant, "2027-Q1", "Quý I/2027", start, start.plusMonths(3).minusDays(1), status);
  }

  @Test
  void bindingHitIsCachedPerResolutionKey() {
    when(repo.matchBinding(tenant, period.value(), "DANG", null, null))
        .thenReturn(Optional.of(resolved));
    assertThat(service.resolve(tenant, period, "DANG", null, null)).contains(resolved);
    assertThat(service.resolve(tenant, period, "DANG", null, null)).contains(resolved);
    verify(repo, times(1)).matchBinding(tenant, period.value(), "DANG", null, null);
    service.evict(tenant);
    service.resolve(tenant, period, "DANG", null, null);
    verify(repo, times(2)).matchBinding(tenant, period.value(), "DANG", null, null);
  }

  @Test
  void missingBindingPinsPackChosenByRange() {
    when(repo.matchBinding(tenant, period.value(), "DANG", null, null))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(resolved));
    when(periods.findById(tenant, period))
        .thenReturn(Optional.of(snapshot(LocalDate.of(2027, 1, 1), PeriodStatus.DA_KHOA)));
    when(repo.publishedVersions(tenant, "DANG"))
        .thenReturn(
            List.of(
                published(
                    versionId,
                    "khoi-dang/quy-dinh-moi",
                    "GOC_TOAN_CUC",
                    "DANG",
                    "2.0.0",
                    PolicyVersionStatus.DA_PHAT_HANH,
                    MANIFEST)));
    assertThat(service.resolve(tenant, period, "DANG", null, null)).contains(resolved);
    verify(repo)
        .pinBinding(
            eq(tenant), any(), eq(versionId), eq(period.value()), eq("DANG"), eq(true), any());
  }

  @Test
  void nothingMatchesReturnsEmpty() {
    when(repo.matchBinding(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    assertThat(service.resolve(tenant, period, "DANG", null, null)).isEmpty();
    when(periods.findById(tenant, period))
        .thenReturn(Optional.of(snapshot(LocalDate.of(2026, 10, 1), PeriodStatus.MO)));
    when(repo.publishedVersions(tenant, "DANG"))
        .thenReturn(
            List.of(
                published(
                    versionId,
                    "khoi-dang/quy-dinh-moi",
                    "GOC_TOAN_CUC",
                    "DANG",
                    "2.0.0",
                    PolicyVersionStatus.DA_PHAT_HANH,
                    MANIFEST)));
    assertThat(service.resolve(tenant, period, "DANG", null, null)).isEmpty();
    verify(repo, never()).pinBinding(any(), any(), any(), any(), any(), anyBoolean(), any());
  }

  @Test
  void periodCreatedPinsEachFlowOfBothFlowPeriod() {
    when(repo.publishedVersions(tenant, "DANG"))
        .thenReturn(
            List.of(
                published(
                    versionId,
                    "khoi-dang/quy-dinh-moi",
                    "GOC_TOAN_CUC",
                    "DANG",
                    "2.0.0",
                    PolicyVersionStatus.NGUNG,
                    MANIFEST)));
    UUID nn = UUID.randomUUID();
    when(repo.publishedVersions(tenant, "NHA_NUOC"))
        .thenReturn(
            List.of(
                published(
                    nn,
                    "khoi-chinh-quyen/nd335",
                    "TENANT",
                    "NHA_NUOC",
                    "1.4.0",
                    PolicyVersionStatus.DA_PHAT_HANH,
                    "{}")));
    assertThat(service.pinForPeriod(tenant, period, "CA_HAI", LocalDate.of(2027, 4, 1)))
        .isEqualTo(2);
    verify(repo)
        .pinBinding(
            eq(tenant), any(), eq(versionId), eq(period.value()), eq("DANG"), eq(false), any());
    verify(repo)
        .pinBinding(
            eq(tenant), any(), eq(nn), eq(period.value()), eq("NHA_NUOC"), eq(false), any());
    assertThat(service.pinForPeriod(tenant, period, "DANG", LocalDate.of(2026, 1, 1))).isZero();
  }

  @Test
  void inputsReadAsMapOrArray() {
    when(repo.version(tenant, versionId))
        .thenReturn(
            Optional.of(
                published(
                    versionId,
                    "khoi-dang/quy-dinh-moi",
                    "GOC_TOAN_CUC",
                    "DANG",
                    "2.0.0",
                    PolicyVersionStatus.DA_PHAT_HANH,
                    MANIFEST)));
    assertThat(service.inputs(tenant, versionId))
        .extracting(PolicyQueryApi.InputDeclaration::name)
        .containsExactly("ty_le", "so_lan", "ds", "ten", "co", "la");
    assertThat(service.inputs(tenant, versionId).get(2).type()).isEqualTo("list<NhiemVu>");
    assertThat(service.inputs(tenant, versionId).get(5).type()).isEqualTo("x");

    UUID arrayVersion = UUID.randomUUID();
    when(repo.version(tenant, arrayVersion))
        .thenReturn(
            Optional.of(
                published(
                    arrayVersion,
                    "twd/diem-cong-viec",
                    "TENANT",
                    "DANG",
                    "0.1.0",
                    PolicyVersionStatus.DA_PHAT_HANH,
                    """
                    {"inputs":[{"name":"work_items","type":"list<WorkItem>",
                                "source":"task.aggregate.work_items"},{"type":"int"}]}
                    """)));
    assertThat(service.inputs(tenant, arrayVersion))
        .containsExactly(
            new PolicyQueryApi.InputDeclaration(
                "work_items", "list<WorkItem>", "task.aggregate.work_items"));
    assertThat(service.inputs(tenant, UUID.randomUUID())).isEmpty();
  }

  @Test
  void parametersLifecycleAndContentComeFromVersion() {
    when(repo.version(tenant, versionId))
        .thenReturn(
            Optional.of(
                published(
                    versionId,
                    "khoi-dang/quy-dinh-moi",
                    "GOC_TOAN_CUC",
                    "DANG",
                    "2.0.0",
                    PolicyVersionStatus.DA_PHAT_HANH,
                    MANIFEST)));
    assertThat(service.parameter(tenant, versionId, "tru_tien_do")).contains(0.05);
    assertThat(service.parameter(tenant, versionId, "phang")).contains(3);
    assertThat(service.parameter(tenant, versionId, "khong_co")).isEmpty();
    assertThat(service.manifest(tenant, versionId)).isPresent();
    verify(repo, times(1)).version(tenant, versionId);

    when(packs.rules(tenant, versionId))
        .thenReturn(
            List.of(
                new PolicyRule(
                    UUID.randomUUID(),
                    versionId,
                    "task_lifecycle",
                    "true",
                    "BOOLEAN",
                    "{}",
                    "ADR-04",
                    99,
                    "{\"trang_thai\":[\"NHAP\",\"DA_GIAO\"]}",
                    false)));
    when(packs.decisionTables(tenant, versionId))
        .thenReturn(
            List.of(
                new DecisionTable(
                    UUID.randomUUID(),
                    versionId,
                    "xep_loai",
                    "<dmn/>",
                    "FIRST",
                    new byte[] {1},
                    "PL 5",
                    "[]",
                    "{}")));
    PolicyContent content = service.content(tenant, versionId).orElseThrow();
    assertThat(content.rules())
        .singleElement()
        .extracting(r -> r.ruleKey())
        .isEqualTo("task_lifecycle");
    assertThat(content.decisionTables())
        .singleElement()
        .extracting(t -> t.tableKey())
        .isEqualTo("xep_loai");
    assertThat(service.taskLifecycle(tenant, versionId).orElseThrow().get("trang_thai"))
        .isEqualTo(List.of("NHAP", "DA_GIAO"));
    service.content(tenant, versionId);
    verify(packs, times(1)).rules(tenant, versionId);
    assertThat(service.content(tenant, UUID.randomUUID())).isEmpty();
    assertThat(service.taskLifecycle(tenant, UUID.randomUUID())).isEmpty();
  }

  @Test
  void draftContentIsReadFreshAndInlineLifecycleWins() {
    when(repo.version(tenant, versionId))
        .thenReturn(
            Optional.of(
                published(
                    versionId,
                    "x",
                    "TENANT",
                    "DANG",
                    "0.1.0",
                    PolicyVersionStatus.NHAP,
                    "{\"task_lifecycle\":{\"lenh\":[\"ASSIGN\"]}}")));
    assertThat(service.taskLifecycle(tenant, versionId).orElseThrow()).containsKey("lenh");
    service.content(tenant, versionId);
    service.content(tenant, versionId);
    verify(packs, times(2)).rules(tenant, versionId);
  }

  @Test
  void cacheHitStaysUnderOneMillisecondAtP95() {
    when(repo.matchBinding(tenant, period.value(), "DANG", null, null))
        .thenReturn(Optional.of(resolved));
    service.resolve(tenant, period, "DANG", null, null);
    for (int i = 0; i < 20_000; i++) {
      service.resolve(tenant, period, "DANG", null, null);
    }
    long[] samples = new long[10_000];
    for (int i = 0; i < samples.length; i++) {
      long start = System.nanoTime();
      service.resolve(tenant, period, "DANG", null, null);
      samples[i] = System.nanoTime() - start;
    }
    Arrays.sort(samples);
    assertThat(samples[(int) (samples.length * 0.95)]).isLessThan(1_000_000L);
  }

  @Test
  void periodCalendarComputesSecondWeekDeadline() {
    when(repo.publishedVersions(tenant, "DANG"))
        .thenReturn(
            List.of(
                published(
                    versionId,
                    "khoi-dang/quy-dinh-moi",
                    "GOC_TOAN_CUC",
                    "DANG",
                    "2.0.0",
                    PolicyVersionStatus.DA_PHAT_HANH,
                    MANIFEST)));
    when(repo.version(tenant, versionId))
        .thenReturn(
            Optional.of(
                published(
                    versionId,
                    "khoi-dang/quy-dinh-moi",
                    "GOC_TOAN_CUC",
                    "DANG",
                    "2.0.0",
                    PolicyVersionStatus.DA_PHAT_HANH,
                    MANIFEST)));
    PolicyPeriodCalendar weekdays = new PolicyPeriodCalendar(service, () -> null);
    Optional<PeriodCalendar> q1 =
        weekdays.calendarFor(
            tenant, "QUY", "CA_HAI", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31));
    assertThat(q1.orElseThrow().planApprovalDeadline()).isEqualTo(LocalDate.of(2027, 1, 8));
    assertThat(q1.get().submissionDeadline()).isNull();
    assertThat(weekdays.secondWeekLastWorkingDay(tenant, LocalDate.of(2026, 10, 1)))
        .isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(weekdays.calendarFor(tenant, "NAM", "DANG", LocalDate.of(2027, 1, 1), null))
        .isEmpty();
    assertThat(weekdays.calendarFor(tenant, "QUY", "DANG", LocalDate.of(2026, 10, 1), null))
        .isEmpty();

    WorkingCalendarApi holidays = mock(WorkingCalendarApi.class);
    when(holidays.isWorkingDay(eq(tenant), any()))
        .thenAnswer(
            a -> {
              LocalDate d = a.getArgument(1);
              return d.getDayOfWeek().getValue() <= 5 && !d.equals(LocalDate.of(2027, 1, 8));
            });
    assertThat(
            new PolicyPeriodCalendar(service, () -> holidays)
                .secondWeekLastWorkingDay(tenant, LocalDate.of(2027, 1, 1)))
        .isEqualTo(LocalDate.of(2027, 1, 7));
    WorkingCalendarApi none = mock(WorkingCalendarApi.class);
    assertThat(
            new PolicyPeriodCalendar(service, () -> none)
                .secondWeekLastWorkingDay(tenant, LocalDate.of(2027, 1, 1)))
        .isEqualTo(LocalDate.of(2027, 1, 8));
  }

  @Test
  void schemaPortResolvesFormsRetirementAndScoredFields() {
    PublishedVersion v =
        published(
            versionId,
            "khoi-dang/quy-dinh-moi",
            "GOC_TOAN_CUC",
            "DANG",
            "2.0.0",
            PolicyVersionStatus.DA_PHAT_HANH,
            MANIFEST);
    when(repo.versionByCode(tenant, "khoi-dang/quy-dinh-moi", "2.0.0")).thenReturn(Optional.of(v));
    when(repo.version(tenant, versionId)).thenReturn(Optional.of(v));
    PolicySchemaAdapter port = new PolicySchemaAdapter(service, packs);

    String ref = "policy://khoi-dang/quy-dinh-moi/forms/mau-1b@2.0.0";
    assertThat(port.fields(tenant, ref, null)).contains(Set.of("diem", "nhan_xet"));
    assertThat(port.fields(tenant, ref, "khoi-dang/quy-dinh-moi@>=2.0.0 <3.0.0")).isPresent();
    assertThat(port.fields(tenant, ref, "khoi-dang/quy-dinh-moi@^1.0")).isEmpty();
    assertThat(port.fields(tenant, ref, "khac@^2.0")).isEmpty();
    assertThat(port.fields(tenant, ref, "khoi-dang/quy-dinh-moi@moi-nhat")).isEmpty();
    assertThat(port.fields(tenant, "policy://khoi-dang/quy-dinh-moi/forms/mau-2@2.0.0", ""))
        .contains(Set.of("a", "b"));
    assertThat(port.fields(tenant, "policy://khoi-dang/quy-dinh-moi/forms/rong@2.0.0", null))
        .isEmpty();
    assertThat(port.fields(tenant, "policy://khoi-dang/quy-dinh-moi/forms/khong@2.0.0", null))
        .isEmpty();
    assertThat(port.fields(tenant, "https://x/forms/a@1.0.0", null)).isEmpty();
    assertThat(port.fields(tenant, null, null)).isEmpty();

    assertThat(port.scoredFields(tenant, versionId))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("ty_le", "number", "so_lan", "integer", "ten", "string", "co", "boolean"));
    assertThat(port.scoredFields(tenant, null)).isEmpty();

    PolicyPack pack =
        new PolicyPack(
            UUID.randomUUID(),
            "khoi-dang/quy-dinh-moi",
            "Gói",
            "KHOI_DANG",
            "DANG",
            PackOrigin.GOC_TOAN_CUC,
            null,
            "QĐ",
            null,
            null,
            0);
    when(packs.findPackByCode(tenant, "khoi-dang/quy-dinh-moi")).thenReturn(Optional.of(pack));
    when(packs.listVersions(tenant, pack.id(), null, null, 200))
        .thenReturn(
            List.of(
                version(pack, "1.4.0", PolicyVersionStatus.NGUNG),
                version(pack, "2.0.0", PolicyVersionStatus.DA_PHAT_HANH),
                version(pack, "2.1.0", PolicyVersionStatus.NHAP)));
    assertThat(port.retired(tenant, "khoi-dang/quy-dinh-moi@~1.4.0")).isTrue();
    assertThat(port.retired(tenant, "khoi-dang/quy-dinh-moi@>=1.0.0 <3.0.0")).isFalse();
    assertThat(port.retired(tenant, "khoi-dang/quy-dinh-moi@^2.1")).isFalse();
    assertThat(port.retired(tenant, "khoi-dang/quy-dinh-moi@x")).isFalse();
    assertThat(port.retired(tenant, "khoi-dang/quy-dinh-moi")).isFalse();
    assertThat(port.retired(tenant, null)).isFalse();
  }

  private static PolicyPackVersion version(PolicyPack pack, String v, PolicyVersionStatus s) {
    return new PolicyPackVersion(
        UUID.randomUUID(),
        pack.id(),
        v,
        s,
        null,
        "{}",
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        null,
        null,
        "x",
        null,
        null,
        null,
        null,
        0);
  }
}
