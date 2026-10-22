package vn.viettel.okrkpi.task.application.assign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static vn.viettel.okrkpi.task.application.assign.AssignFixture.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.CatalogDefaults;
import vn.viettel.okrkpi.task.application.assign.port.TaskDraftRepository.DraftRow;
import vn.viettel.okrkpi.task.domain.assign.TaskDraftSpec;

class TaskDraftServiceTest {

  final AssignFixture f = new AssignFixture();

  static String code(Throwable e) {
    return ((BusinessException) e).errorCode().code();
  }

  @Test
  void khungSoanLayKyDangMoVaDonViCuaNguoiGiao() {
    TaskDraftService.Template t = f.drafts.newDraft(TENANT, MANAGER);
    assertThat(t.period().id().value()).isEqualTo(PERIOD);
    assertThat(t.draft().orgUnitId()).isEqualTo(UNIT);
    assertThat(t.draft().spec().acceptorId()).isEqualTo(ASSIGNER);
    when(f.periods.findCurrent(TENANT)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> f.drafts.newDraft(TENANT, MANAGER))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-PERIOD-LOCKED"));
  }

  @Test
  void luuNhapLanDauSinhMaVaGiaTriMacDinh() {
    DraftRow row = f.drafts.createDraft(TENANT, spec("DOT_XUAT", LEAD), MANAGER);
    assertThat(row.code()).isEqualTo("NV-2026-Q4-0001");
    assertThat(row.spec().title()).isEqualTo("Hoàn thành báo cáo quý");
    assertThat(row.spec().priority()).isEqualTo("TRUNG_BINH");
    assertThat(row.orgUnitId()).isEqualTo(UNIT);
  }

  @Test
  void luuNhapDienHanCuoiKyVaSoLuongMot() {
    TaskDraftSpec s = spec("DOT_XUAT", LEAD);
    TaskDraftSpec empty =
        new TaskDraftSpec(
            PERIOD,
            "DOT_XUAT",
            null,
            null,
            "Việc",
            "THUONG_XUYEN",
            null,
            "CAO",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    DraftRow row = f.drafts.createDraft(TENANT, empty, MANAGER);
    assertThat(row.spec().dueDate()).isEqualTo(END);
    assertThat(row.spec().quantity()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(row.spec().unit()).isEmpty();
    assertThat(s.quantity()).isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void kyDaKhoaThiKhongLuuSuaHuy() {
    DraftRow existing = f.put("NHAP", spec("DOT_XUAT", LEAD), false, 2);
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(f.periods)
        .assertWritable(TENANT, PeriodId.of(PERIOD));
    assertThatThrownBy(() -> f.drafts.createDraft(TENANT, spec("DOT_XUAT", LEAD), MANAGER))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-PERIOD-LOCKED"));
    assertThatThrownBy(() -> f.drafts.insert(TENANT, spec("DOT_XUAT", LEAD), MANAGER, UNIT))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-PERIOD-LOCKED"));
    assertThatThrownBy(
            () -> f.drafts.saveDraft(TENANT, existing.id(), 2, spec("DOT_XUAT", LEAD), MANAGER))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-PERIOD-LOCKED"));
    assertThatThrownBy(() -> f.drafts.deleteDraft(TENANT, existing.id(), 2, MANAGER))
        .satisfies(e -> assertThat(code(e)).isEqualTo("TASK-409-PERIOD-LOCKED"));
  }

  @Test
  void kiemTraDuLieuTraTungTruong() {
    TaskDraftSpec bad =
        new TaskDraftSpec(
            null,
            "LA",
            null,
            null,
            " ",
            "KHAC",
            null,
            "RAT_CAO",
            null,
            List.of(new TaskDraftSpec.Assignee("NHOM", UUID.randomUUID())),
            null,
            null,
            List.of(new TaskDraftSpec.Milestone(null, "x")),
            "x".repeat(501),
            BigDecimal.valueOf(-1),
            "x".repeat(51),
            "x".repeat(1001),
            "x".repeat(2001),
            null,
            null,
            "x".repeat(1001));
    assertThatThrownBy(() -> TaskDraftService.validate(bad))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors())
                    .extracting(ProblemFieldError::field)
                    .contains(
                        "periodId",
                        "source",
                        "title",
                        "taskGroup",
                        "priority",
                        "quantity",
                        "unit",
                        "productName",
                        "evidenceRequired",
                        "qualityCriteria",
                        "basis",
                        "milestones",
                        "coAssignees"));
    assertThatThrownBy(() -> TaskDraftService.validate(null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
  }

  @Test
  void mucDanhMucLayDoKhoVaKyKhongTonTai() {
    UUID item = UUID.randomUUID();
    TaskDraftSpec s = spec("DOT_XUAT", LEAD);
    TaskDraftSpec withCatalog =
        new TaskDraftSpec(
            PERIOD,
            s.source(),
            null,
            item,
            s.title(),
            s.taskGroup(),
            null,
            null,
            LEAD,
            null,
            s.dueDate(),
            null,
            null,
            s.productName(),
            s.quantity(),
            s.unit(),
            s.evidenceRequired(),
            s.qualityCriteria(),
            null,
            null,
            s.basis());
    assertThatThrownBy(() -> f.drafts.createDraft(TENANT, withCatalog, MANAGER))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    when(f.repo.catalogDefaults(TENANT, item))
        .thenReturn(Optional.of(new CatalogDefaults(3, BigDecimal.TEN)));
    assertThat(f.drafts.createDraft(TENANT, withCatalog, MANAGER).spec().catalogItemId())
        .isEqualTo(item);
    TaskDraftSpec otherPeriod =
        new TaskDraftSpec(
            UUID.randomUUID(),
            s.source(),
            null,
            null,
            s.title(),
            s.taskGroup(),
            null,
            null,
            LEAD,
            null,
            s.dueDate(),
            null,
            null,
            s.productName(),
            s.quantity(),
            s.unit(),
            s.evidenceRequired(),
            s.qualityCriteria(),
            null,
            null,
            s.basis());
    assertThatThrownBy(() -> f.drafts.insert(TENANT, otherPeriod, MANAGER, null))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
  }

  @Test
  void suaNhapKiemTrangThaiPhienBanVaKy() {
    DraftRow assigned = f.put("DA_GIAO", spec("DOT_XUAT", LEAD), false, 1);
    assertThatThrownBy(
            () -> f.drafts.saveDraft(TENANT, assigned.id(), 1, spec("DOT_XUAT", LEAD), MANAGER))
        .satisfies(e -> assertThat(code(e)).isEqualTo(TaskDraftService.NOT_DRAFT));
    DraftRow d = f.put("NHAP", spec("DOT_XUAT", LEAD), false, 4);
    assertThatThrownBy(() -> f.drafts.saveDraft(TENANT, d.id(), 3, spec("DOT_XUAT", LEAD), MANAGER))
        .isInstanceOf(VersionConflictException.class);
    TaskDraftSpec s = spec("DOT_XUAT", LEAD);
    TaskDraftSpec moved =
        new TaskDraftSpec(
            UUID.randomUUID(),
            s.source(),
            null,
            null,
            s.title(),
            s.taskGroup(),
            null,
            null,
            LEAD,
            null,
            s.dueDate(),
            null,
            null,
            s.productName(),
            s.quantity(),
            s.unit(),
            s.evidenceRequired(),
            s.qualityCriteria(),
            null,
            null,
            s.basis());
    assertThatThrownBy(() -> f.drafts.saveDraft(TENANT, d.id(), 4, moved, MANAGER))
        .satisfies(e -> assertThat(code(e)).isEqualTo("COMMON-422-VALIDATION"));
    when(f.repo.storeDraft(eq(TENANT), any(), eq(4L), any(), any(), any(), any(), any(), any()))
        .thenReturn(-1L);
    assertThatThrownBy(() -> f.drafts.saveDraft(TENANT, d.id(), 4, s, MANAGER))
        .isInstanceOf(VersionConflictException.class);
    when(f.repo.storeDraft(eq(TENANT), any(), eq(4L), any(), any(), any(), any(), any(), any()))
        .thenReturn(5L);
    assertThat(f.drafts.saveDraft(TENANT, d.id(), 4, s, MANAGER).id()).isEqualTo(d.id());
    assertThatThrownBy(() -> f.drafts.saveDraft(TENANT, UUID.randomUUID(), 4, s, MANAGER))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void huyNhapVaXemNhap() {
    DraftRow d = f.put("NHAP", spec("DOT_XUAT", LEAD), false, 2);
    when(f.repo.discardDraft(eq(TENANT), eq(20264), eq(d.id()), anyLong())).thenReturn(true, false);
    f.drafts.deleteDraft(TENANT, d.id(), 2, MANAGER);
    verify(f.repo).discardDraft(TENANT, 20264, d.id(), 2);
    assertThatThrownBy(() -> f.drafts.deleteDraft(TENANT, d.id(), 2, MANAGER))
        .isInstanceOf(VersionConflictException.class);
    assertThat(f.drafts.getDraft(TENANT, d.id()).period().endDate()).isEqualTo(END);
    DraftRow assigned = f.put("DA_GIAO", spec("DOT_XUAT", LEAD), false, 1);
    assertThatThrownBy(() -> f.drafts.getDraft(TENANT, assigned.id()))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void dauVanTayPhanBietChuTriDonVi() {
    TaskDraftSpec s = spec("DOT_XUAT", LEAD).withLead(LEAD);
    assertThat(TaskDraftService.fingerprint(s, null))
        .isNotEqualTo(TaskDraftService.fingerprint(s, UNIT))
        .hasSize(32);
    when(f.org.assignmentsOf(eq(TENANT), eq(ASSIGNER), any())).thenReturn(List.of());
    assertThat(f.drafts.unitOf(TENANT, ASSIGNER)).isNull();
    assertThat(LocalDate.of(2026, 1, 1)).isBefore(END);
  }
}
