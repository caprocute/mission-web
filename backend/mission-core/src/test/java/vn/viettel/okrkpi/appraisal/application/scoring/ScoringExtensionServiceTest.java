package vn.viettel.okrkpi.appraisal.application.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService.Declared;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService.Extensions;
import vn.viettel.okrkpi.appraisal.application.scoring.ScoringExtensionService.WorkItemPage;
import vn.viettel.okrkpi.appraisal.application.scoring.port.IncidentRepository.IncidentRow;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository.ProfileKey;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository.ScoringInput;
import vn.viettel.okrkpi.okr.api.OkrApi;
import vn.viettel.okrkpi.okr.api.OkrApi.KeyResultSnapshot;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi.InputDeclaration;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskApi.ResponsibleItem;
import vn.viettel.okrkpi.task.api.TaskApi.WorkItemSnapshot;

class ScoringExtensionServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final UUID PERIOD = UUID.randomUUID();
  private static final UUID PLAIN_PACK = UUID.randomUUID();
  private static final UUID WORK_PACK = UUID.randomUUID();

  private final ScoringInputRepository profiles = mock(ScoringInputRepository.class);
  private final PolicyQueryApi policies = mock(PolicyQueryApi.class);
  private final TaskApi tasks = mock(TaskApi.class);
  private final OkrApi okr = mock(OkrApi.class);
  private final IncidentService incidents = mock(IncidentService.class);
  private final ScoringExtensionService service =
      new ScoringExtensionService(profiles, policies, tasks, okr, incidents);

  @Test
  void goiKhongKhaiBaoThiKhongTruyVanThem() {
    when(policies.inputs(TENANT, PLAIN_PACK))
        .thenReturn(List.of(new InputDeclaration("so_luong_duyet", "int", null)));
    List<ScoringInput> page =
        List.of(input(PLAIN_PACK, true, true), input(PLAIN_PACK, false, false));

    assertThat(service.extend(TENANT, PERIOD, page)).isEmpty();

    verify(policies, times(1)).inputs(TENANT, PLAIN_PACK);
    verifyNoInteractions(tasks, okr, incidents);
  }

  @Test
  void viecLaDocMotLanChoCaTrangVaCatO200() {
    when(policies.inputs(TENANT, WORK_PACK))
        .thenReturn(
            List.of(new InputDeclaration("viec", "list<WorkItem>", "task.aggregate.work_items")));
    ScoringInput many = input(WORK_PACK, false, false);
    ScoringInput few = input(WORK_PACK, false, false);
    ScoringInput none = input(WORK_PACK, false, false);
    List<WorkItemSnapshot> big = new ArrayList<>();
    for (int i = 0; i < 201; i++) {
      big.add(item());
    }
    when(tasks.listWorkItems(eq(TENANT), eq(PeriodId.of(PERIOD)), anyCollection(), eq(201)))
        .thenReturn(Map.of(many.personId(), big, few.personId(), List.of(item())));

    Map<UUID, Extensions> out = service.extend(TENANT, PERIOD, List.of(many, few, none));

    assertThat(out.get(many.profileId()).workItems()).hasSize(200);
    assertThat(out.get(many.profileId()).workItemsAfter()).isEqualTo(big.get(199).taskId());
    assertThat(out.get(few.profileId()).workItems()).hasSize(1);
    assertThat(out.get(few.profileId()).workItemsAfter()).isNull();
    assertThat(out.get(none.profileId()).workItems()).isEmpty();
    assertThat(out.get(none.profileId()).responsibility()).isNull();
    assertThat(out.get(none.profileId()).incidents()).isNull();
    verify(tasks, times(1)).listWorkItems(any(), any(), anyCollection(), anyInt());
    verify(tasks, never()).listResponsibleItems(any(), any(), any());
    verifyNoInteractions(okr, incidents);
  }

  @Test
  void phamViPhuTrachChiChoLanhDaoQuanLyKemKrVaDonVi() {
    UUID pack = UUID.randomUUID();
    when(policies.inputs(TENANT, pack))
        .thenReturn(List.of(new InputDeclaration("pham_vi", "list<ResponsibilityItem>", null)));
    ScoringInput head = input(pack, true, true);
    ScoringInput staff = input(pack, false, false);
    UUID taskItem = UUID.randomUUID();
    when(tasks.listResponsibleItems(TENANT, PeriodId.of(PERIOD), head.personId()))
        .thenReturn(
            List.of(
                new ResponsibleItem(
                    "KPI",
                    taskItem,
                    2,
                    new BigDecimal("15"),
                    new BigDecimal("10"),
                    new BigDecimal("0.5"),
                    0)));
    KeyResultSnapshot kr = mock(KeyResultSnapshot.class);
    UUID krId = UUID.randomUUID();
    when(kr.id()).thenReturn(krId);
    when(kr.progress()).thenReturn(new BigDecimal("0.8000"));
    when(okr.listResponsibleKeyResults(TENANT, PeriodId.of(PERIOD), head.personId()))
        .thenReturn(List.of(kr));

    Map<UUID, Extensions> out = service.extend(TENANT, PERIOD, List.of(head, staff));

    assertThat(out.get(head.profileId()).responsibility())
        .extracting(ScoringExtensionService.Responsibility::itemType)
        .containsExactly("KPI", "KR", "DON_VI");
    ScoringExtensionService.Responsibility kpi = out.get(head.profileId()).responsibility().get(0);
    assertThat(kpi.sumS()).isEqualByComparingTo("15");
    assertThat(kpi.sumD()).isEqualByComparingTo("10");
    assertThat(kpi.sumQ()).isNull();
    assertThat(out.get(head.profileId()).responsibility().get(1).targetAchievedRatio())
        .isEqualByComparingTo("0.8");
    assertThat(out.get(head.profileId()).responsibility().get(2).itemId())
        .isEqualTo(head.orgUnitId());
    assertThat(out.get(staff.profileId()).responsibility()).isEmpty();
    assertThat(out.get(head.profileId()).workItems()).isNull();
    verify(tasks, never()).listResponsibleItems(any(), any(), eq(staff.personId()));
  }

  @Test
  void suViecChiKhiGoiKhaiDieuKienLoaiTru() {
    UUID pack = UUID.randomUUID();
    when(policies.inputs(TENANT, pack))
        .thenReturn(List.of(new InputDeclaration("incidents", null, "appraisal.incident")));
    ScoringInput in = input(pack, false, false);
    IncidentRow row =
        new IncidentRow(
            UUID.randomUUID(),
            in.personId(),
            PERIOD,
            in.orgUnitId(),
            "KY_LUAT",
            LocalDate.parse("2027-02-01"),
            "12-QĐ",
            null,
            "LOAI_TRU",
            null,
            UUID.randomUUID(),
            null,
            0);
    when(incidents.forPersons(eq(TENANT), eq(PERIOD), anyCollection()))
        .thenReturn(Map.of(in.personId(), List.of(row)));

    Extensions ext = service.extend(TENANT, PERIOD, List.of(in)).get(in.profileId());

    assertThat(ext.incidents()).containsExactly(row);
    assertThat(ext.workItems()).isNull();
    verifyNoInteractions(tasks, okr);
  }

  @Test
  void docTiepViecLaTheoConTroCon() {
    UUID profile = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    when(profiles.profileKey(TENANT, profile))
        .thenReturn(Optional.of(new ProfileKey(profile, person, PERIOD, WORK_PACK)));
    when(policies.inputs(TENANT, WORK_PACK))
        .thenReturn(List.of(new InputDeclaration("work_items", null, null)));
    UUID after = UUID.randomUUID();
    List<WorkItemSnapshot> rows = List.of(item(), item(), item());
    when(tasks.listWorkItems(TENANT, PeriodId.of(PERIOD), person, after, 3)).thenReturn(rows);

    WorkItemPage page = service.workItems(TENANT, profile, after, 2);
    assertThat(page.items()).hasSize(2);
    assertThat(page.nextAfter()).isEqualTo(rows.get(1).taskId());

    when(tasks.listWorkItems(TENANT, PeriodId.of(PERIOD), person, null, 3))
        .thenReturn(List.of(item()));
    assertThat(service.workItems(TENANT, profile, null, 2).nextAfter()).isNull();

    UUID plain = UUID.randomUUID();
    when(profiles.profileKey(TENANT, plain))
        .thenReturn(Optional.of(new ProfileKey(plain, person, PERIOD, PLAIN_PACK)));
    when(policies.inputs(TENANT, PLAIN_PACK)).thenReturn(List.of());
    assertThat(service.workItems(TENANT, plain, null, 2).items()).isEmpty();

    assertThatThrownBy(() -> service.workItems(TENANT, UUID.randomUUID(), null, 2))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void nhanKhaiBaoTheoNguonKieuHoacTen() {
    assertThat(Declared.of(List.of())).isEqualTo(Declared.NONE);
    assertThat(
            Declared.of(List.of(new InputDeclaration("x", "list< WorkItem >", null))).workItems())
        .isTrue();
    assertThat(
            Declared.of(List.of(new InputDeclaration("responsibility_scope", null, null)))
                .responsibility())
        .isTrue();
    assertThat(
            Declared.of(List.of(new InputDeclaration("y", "list<AppraisalIncidentInput>", null)))
                .incidents())
        .isTrue();
    assertThat(
            new ScoringExtensionService(profiles, null, tasks, okr, incidents)
                .extend(TENANT, PERIOD, List.of(input(WORK_PACK, true, true))))
        .isEmpty();
  }

  private static ScoringInput input(UUID pack, boolean head, boolean leadership) {
    return new ScoringInput(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "DANG",
        UUID.randomUUID(),
        null,
        head,
        leadership,
        pack,
        List.of());
  }

  private static WorkItemSnapshot item() {
    return new WorkItemSnapshot(
        UUID.randomUUID(),
        null,
        BigDecimal.TEN,
        3,
        Map.of("he_so_nhom", "NHOM_2"),
        "THEN_CHOT",
        "TR1",
        null,
        "HOAN_THANH",
        null,
        0,
        0,
        false,
        false,
        false);
  }
}
