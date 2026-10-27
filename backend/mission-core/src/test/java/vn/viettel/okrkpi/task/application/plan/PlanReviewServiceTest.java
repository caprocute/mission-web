package vn.viettel.okrkpi.task.application.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.context.ApplicationEventPublisher;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.PlanVersionReturnedEvent;
import vn.viettel.okrkpi.task.api.PlanVersionSubmittedEvent;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.Check;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.Level;
import vn.viettel.okrkpi.task.application.plan.PlanService.VersionView;
import vn.viettel.okrkpi.task.application.plan.port.PlanNotices;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ReviewRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.VersionRow;
import vn.viettel.okrkpi.task.domain.plan.PlanVersionStatus;

/** CORE-02-08: gửi duyệt, trả lại, chờ tôi duyệt ở tầng ứng dụng. */
class PlanReviewServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID HEAD = UUID.randomUUID();
  private static final UUID AUTHOR = UUID.randomUUID();
  private static final UUID PERIOD = UUID.randomUUID();
  private static final PlanRow PLAN =
      new PlanRow(UUID.randomUUID(), PERIOD, 20271, "TAP_THE", UNIT, null, null, 0);
  private static final PeriodSnapshot SNAPSHOT =
      new PeriodSnapshot(
          PeriodId.of(PERIOD),
          TENANT,
          "2027-Q1",
          "Quý I năm 2027",
          LocalDate.parse("2027-01-01"),
          LocalDate.parse("2027-03-31"),
          PeriodStatus.MO);

  private final PlanRepository repo = mock(PlanRepository.class);
  private final PlanService planService = mock(PlanService.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final OrgDirectoryApi org = mock(OrgDirectoryApi.class);
  private final PlanNotices notices = mock(PlanNotices.class);
  private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
  private final PlanReviewService service =
      new PlanReviewService(
          repo,
          planService,
          periods,
          org,
          notices,
          publisher,
          Clock.fixed(Instant.parse("2027-01-05T02:00:00Z"), ZoneOffset.UTC));

  private static VersionRow version(PlanVersionStatus status, long rowVersion) {
    return new VersionRow(
        UUID.randomUUID(),
        PLAN.id(),
        1,
        status,
        false,
        null,
        null,
        null,
        null,
        null,
        0,
        rowVersion);
  }

  private void given(VersionRow v, List<Check> checks) {
    when(repo.findPlan(TENANT, PLAN.id())).thenReturn(Optional.of(PLAN));
    when(repo.lockVersion(TENANT, 20271, PLAN.id(), 1)).thenReturn(Optional.of(v));
    when(planService.version(TENANT, PLAN.id(), 1))
        .thenReturn(
            new VersionView(
                PLAN, SNAPSHOT, v, List.of(offCatalog(UUID.randomUUID(), null)), checks, null));
    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.of(SNAPSHOT));
    when(org.headsOf(eq(TENANT), eq(UNIT), any())).thenReturn(List.of(HEAD));
  }

  @Test
  void guiDuyetConLoiChanTra422KemDanhSach() {
    given(
        version(PlanVersionStatus.NHAP, 2),
        List.of(
            new Check("BR-04-04", Level.CHAN, 2, "Dòng 2 cần đủ", null),
            new Check("BR-04-06", Level.CHAN, null, "Vượt", "MSG-04-06"),
            new Check("BR-04-07", Level.CANH_BAO, null, "KR", null)));
    assertThatThrownBy(() -> service.submitVersion(TENANT, PLAN.id(), 1, 2, AUTHOR))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e ->
                assertThat(e.errors())
                    .extracting(f -> f.field())
                    .containsExactly("items[1]", "items"));
    verify(repo, never()).markSubmitted(any(), any(), any(), any(), any());
  }

  @Test
  void guiDuyetHopLePhatYeuCauTienTrinh() {
    VersionRow v = version(PlanVersionStatus.TRA_LAI, 4);
    given(v, List.of(new Check("BR-04-07", Level.CANH_BAO, null, "KR", null)));
    when(repo.markSubmitted(eq(TENANT), eq(PLAN), eq(v), any(), eq(AUTHOR))).thenReturn(5L);
    service.submitVersion(TENANT, PLAN.id(), 1, 4, AUTHOR);
    verify(publisher)
        .publishEvent(
            ArgumentMatchers.<Object>argThat(
                e ->
                    e instanceof PlanVersionSubmittedEvent s
                        && s.aggregateVersion() == 5
                        && s.contentHash().length() == 64
                        && s.submittedBy().equals(AUTHOR)));
  }

  @Test
  void guiDuyetKeHoachChuaCoCongViecTra422() {
    VersionRow v = version(PlanVersionStatus.NHAP, 0);
    given(v, List.of());
    when(planService.version(TENANT, PLAN.id(), 1))
        .thenReturn(new VersionView(PLAN, SNAPSHOT, v, List.of(), List.of(), null));
    assertThatThrownBy(() -> service.submitVersion(TENANT, PLAN.id(), 1, 0, AUTHOR))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errors()).extracting(f -> f.field()).containsExactly("items"));
    verify(repo, never()).markSubmitted(any(), any(), any(), any(), any());
  }

  @Test
  void guiDuyetSaiPhienBanHoacTrangThai() {
    given(version(PlanVersionStatus.NHAP, 2), List.of());
    assertThatThrownBy(() -> service.submitVersion(TENANT, PLAN.id(), 1, 1, AUTHOR))
        .isInstanceOf(VersionConflictException.class);
    given(version(PlanVersionStatus.CHO_DUYET, 2), List.of());
    assertThatThrownBy(() -> service.submitVersion(TENANT, PLAN.id(), 1, 2, AUTHOR))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(PlanService.INVALID_TRANSITION));
  }

  @Test
  void traLaiCanYKienVaDungNguoiDuyet() {
    VersionRow v = version(PlanVersionStatus.CHO_DUYET, 3);
    given(v, List.of());
    assertThatThrownBy(() -> service.returnVersion(TENANT, PLAN.id(), 1, 3, "  ", HEAD))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(PlanService.VALIDATION));
    assertThatThrownBy(() -> service.returnVersion(TENANT, PLAN.id(), 1, 3, "Sửa", AUTHOR))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(PlanReviewService.OUT_OF_SCOPE));
    when(repo.markReturned(TENANT, PLAN, v, "Sửa dòng 2", HEAD)).thenReturn(AUTHOR);
    service.returnVersion(TENANT, PLAN.id(), 1, 3, " Sửa dòng 2 ", HEAD);
    verify(notices).returned(eq(TENANT), any(), eq("Quý I năm 2027"), eq(AUTHOR));
    verify(publisher)
        .publishEvent(
            ArgumentMatchers.<Object>argThat(
                e -> e instanceof PlanVersionReturnedEvent r && r.aggregateVersion() == 4));
  }

  @Test
  void choToiDuyetTheoDonViDungDau() {
    when(org.assignmentsOf(eq(TENANT), eq(HEAD), any()))
        .thenReturn(
            List.of(
                new AssignmentView(
                    UUID.randomUUID(),
                    HEAD,
                    UNIT,
                    null,
                    UUID.randomUUID(),
                    "BIEN_CHE",
                    "CHINH",
                    true,
                    LocalDate.parse("2020-01-01"),
                    null),
                new AssignmentView(
                    UUID.randomUUID(),
                    HEAD,
                    UUID.randomUUID(),
                    null,
                    UUID.randomUUID(),
                    "KIEM_NHIEM",
                    "PHU",
                    false,
                    LocalDate.parse("2020-01-01"),
                    null)));
    VersionRow v = version(PlanVersionStatus.CHO_DUYET, 3);
    when(repo.awaitingReview(eq(TENANT), eq(java.util.Set.of(UNIT)), eq(null), anyInt()))
        .thenReturn(List.of(new ReviewRow(PLAN, v), new ReviewRow(PLAN, v)));
    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.of(SNAPSHOT));
    assertThat(service.approvals(TENANT, HEAD, null, 51))
        .hasSize(2)
        .allSatisfy(r -> assertThat(r.period()).isEqualTo(SNAPSHOT));
    verify(periods, times(1)).findById(TENANT, PeriodId.of(PERIOD));
    assertThat(service.approvals(TENANT, AUTHOR, null, 51)).isEmpty();
    assertThat(
            new PlanReviewService(
                    repo, planService, periods, null, notices, publisher, Clock.systemUTC())
                .approvals(TENANT, HEAD, null, 51))
        .isEmpty();
  }

  @Test
  void choToiDuyetTheoHopViecWorkflow() {
    VersionRow v = version(PlanVersionStatus.CHO_DUYET, 3);
    UUID other = UUID.randomUUID();
    when(repo.awaitingReviewOfPlans(TENANT, List.of(PLAN.id(), other), null, 51))
        .thenReturn(List.of(new ReviewRow(PLAN, v)));
    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.of(SNAPSHOT));
    PlanReviewService withInbox =
        new PlanReviewService(
            repo,
            planService,
            periods,
            org,
            notices,
            publisher,
            Clock.systemUTC(),
            () -> Optional.of(List.of(PLAN.id(), other)));
    assertThat(withInbox.approvals(TENANT, AUTHOR, null, 51)).hasSize(1);

    PlanReviewService emptyInbox =
        new PlanReviewService(
            repo,
            planService,
            periods,
            org,
            notices,
            publisher,
            Clock.systemUTC(),
            () -> Optional.of(List.of()));
    assertThat(emptyInbox.approvals(TENANT, AUTHOR, null, 51)).isEmpty();
    verify(repo, never()).awaitingReview(any(), any(), any(), anyInt());

    PlanReviewService noInbox =
        new PlanReviewService(
            repo,
            planService,
            periods,
            org,
            notices,
            publisher,
            Clock.systemUTC(),
            Optional::empty);
    assertThat(noInbox.approvals(TENANT, AUTHOR, null, 51)).isEmpty();
  }

  private static PlanRepository.ItemRow offCatalog(UUID id, UUID catalog) {
    return new PlanRepository.ItemRow(
        id,
        1,
        catalog,
        null,
        null,
        "Xuất bản bản tin",
        "THUONG_XUYEN",
        new PlanRepository.Assignee("UNIT", UNIT, "Phòng"),
        List.of(),
        "Bản tin",
        java.math.BigDecimal.ONE,
        "bản tin",
        null,
        null,
        LocalDate.parse("2027-03-01"),
        null,
        null,
        null);
  }

  @Test
  void dinhDiemViecNgoaiDanhMucGhiDiemVaMaBamMoi() {
    VersionRow v = version(PlanVersionStatus.CHO_DUYET, 3);
    given(v, List.of());
    UUID item = UUID.randomUUID();
    when(repo.items(TENANT, 20271, v.id())).thenReturn(List.of(offCatalog(item, null)));
    service.scoreItem(TENANT, PLAN.id(), 1, item, 3, 3, new java.math.BigDecimal("7.5"), HEAD);
    verify(repo)
        .scoreItem(
            eq(TENANT),
            eq(PLAN),
            eq(v),
            eq(item),
            eq(3),
            ArgumentMatchers.argThat(x -> x.compareTo(new java.math.BigDecimal("7.5")) == 0),
            ArgumentMatchers.argThat(h -> h.length == 32),
            eq(HEAD));
  }

  @Test
  void dinhDiemBiChanKhiSaiTrangThaiNguoiHoacDong() {
    UUID item = UUID.randomUUID();
    java.math.BigDecimal score = java.math.BigDecimal.TEN;
    assertThatThrownBy(() -> service.scoreItem(TENANT, PLAN.id(), 1, item, 1, 9, score, HEAD))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                service.scoreItem(
                    TENANT, PLAN.id(), 1, item, 1, 2, new java.math.BigDecimal("0.1"), HEAD))
        .isInstanceOf(BusinessException.class);
    VersionRow nhap = version(PlanVersionStatus.NHAP, 1);
    given(nhap, List.of());
    assertThatThrownBy(() -> service.scoreItem(TENANT, PLAN.id(), 1, item, 2, 2, score, HEAD))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> service.scoreItem(TENANT, PLAN.id(), 1, item, 1, 2, score, HEAD))
        .isInstanceOf(BusinessException.class);
    VersionRow cho = version(PlanVersionStatus.CHO_DUYET, 1);
    given(cho, List.of());
    assertThatThrownBy(() -> service.scoreItem(TENANT, PLAN.id(), 1, item, 1, 2, score, AUTHOR))
        .isInstanceOf(BusinessException.class);
    when(repo.items(TENANT, 20271, cho.id()))
        .thenReturn(List.of(offCatalog(item, UUID.randomUUID())));
    assertThatThrownBy(() -> service.scoreItem(TENANT, PLAN.id(), 1, item, 1, 2, score, HEAD))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> service.scoreItem(TENANT, PLAN.id(), 1, UUID.randomUUID(), 1, 2, score, HEAD))
        .isInstanceOf(java.util.NoSuchElementException.class);
    verify(repo, never()).scoreItem(any(), any(), any(), any(), anyInt(), any(), any(), any());
  }
}
