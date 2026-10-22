package vn.viettel.okrkpi.okr.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.okr.application.OkrReadService.ObjectiveView;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.Finding;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalEvents;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalSignal;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.ObjectiveRow;
import vn.viettel.okrkpi.okr.application.port.OkrReviewRepository;
import vn.viettel.okrkpi.okr.application.port.OkrReviewRepository.ReviewRow;
import vn.viettel.okrkpi.okr.application.port.OkrReviewRepository.ReviewTarget;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Kiểm thử đơn vị quyết định duyệt mục tiêu (CORE-01-06, CORE-01-07). */
class OkrReviewServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID AUTHOR = UUID.randomUUID();
  static final UUID REVIEWER = UUID.randomUUID();
  static final UUID ID = UUID.randomUUID();
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();

  private final OkrReviewRepository reviews = mock(OkrReviewRepository.class);
  private final OkrReadService reads = mock(OkrReadService.class);
  private final OkrCheckService checks = mock(OkrCheckService.class);
  private final PeriodApi periods = mock(PeriodApi.class);
  private final OkrApprovalEvents events = mock(OkrApprovalEvents.class);
  private final OkrApprovalSignal signal = mock(OkrApprovalSignal.class);
  private final List<Object> published = new ArrayList<>();
  private final OkrReviewService service =
      new OkrReviewService(reviews, reads, checks, periods, events, () -> signal, published::add);

  static ReviewTarget target(UUID id, String status, long version) {
    return new ReviewTarget(
        id, PERIOD, 20271, UNIT, null, "O1", "Mục tiêu", 1, status, 1, AUTHOR, "pi-1", version);
  }

  static PeriodSnapshot period(PeriodStatus status) {
    return new PeriodSnapshot(
        PeriodId.of(PERIOD),
        TENANT,
        "Q1",
        "Quý I",
        LocalDate.of(2027, 1, 1),
        LocalDate.of(2027, 3, 31),
        status);
  }

  static String code(Throwable t) {
    return ((BusinessException) t).errorCode().code();
  }

  @BeforeEach
  void setUp() {
    when(reviews.lockTarget(TENANT, ID)).thenReturn(Optional.of(target(ID, "CHO_DUYET", 2)));
    when(periods.findById(TENANT, PeriodId.of(PERIOD)))
        .thenReturn(Optional.of(period(PeriodStatus.MO)));
    when(reviews.markApproved(any(), any(), any())).thenReturn(3L);
    when(reviews.reopenDraft(any(), any(), any(), eq(true))).thenReturn(3L);
    when(reviews.reopenDraft(any(), any(), any(), eq(false))).thenReturn(3L);
    when(reviews.markClosed(any(), any(), any())).thenReturn(3L);
  }

  @AfterEach
  void clearSync() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void pheDuyetGhiSuKienVaBaoSauCommit() {
    UUID previous = UUID.randomUUID();
    when(reviews.previousApproved(eq(TENANT), any()))
        .thenReturn(Optional.of(target(previous, "DA_DUYET", 5)));
    when(reviews.childAuthors(TENANT, previous)).thenReturn(List.of(AUTHOR));
    TransactionSynchronizationManager.initSynchronization();
    assertThat(service.approve(TENANT, REVIEWER, ID, 2, "  Đồng ý ", false)).isEqualTo(3L);
    verify(reviews).appendReview(eq(TENANT), any(), eq(REVIEWER), eq("DUYET"), eq("Đồng ý"));
    verify(events).approved(eq(TENANT), any(), eq(REVIEWER), any(), eq(3L));
    verify(reviews).markClosed(eq(TENANT), any(), eq(REVIEWER));
    verify(events).parentRevised(eq(TENANT), any(), eq(List.of(AUTHOR)), eq(REVIEWER), eq(3L));
    assertThat(published).singleElement().isInstanceOf(OkrObjectiveRevisionApproved.class);
    verify(signal, never()).decided(any(), any(), any(), any(), any(), anyLong());
    for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
      s.afterCommit();
    }
    verify(signal).decided(TENANT, ID, "pi-1", "DUYET", REVIEWER, 2L);
  }

  @Test
  void pheDuyetChanTuDuyetLechPhienBanVaTrangThai() {
    assertThatThrownBy(() -> service.approve(TENANT, AUTHOR, ID, 2, null, false))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-403-SELF-APPROVAL"));
    assertThatThrownBy(() -> service.approve(TENANT, REVIEWER, ID, 1, null, false))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> service.approve(TENANT, REVIEWER, ID, 2, "x".repeat(2001), false))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    when(reviews.lockTarget(TENANT, ID)).thenReturn(Optional.of(target(ID, "NHAP", 2)));
    assertThatThrownBy(() -> service.approve(TENANT, REVIEWER, ID, 2, null, false))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-INVALID-STATE"));
    UUID missing = UUID.randomUUID();
    when(reviews.lockTarget(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.approve(TENANT, REVIEWER, missing, 0, null, false))
        .isInstanceOf(OkrComposeService.NotFound.class);
  }

  @Test
  void duyetTheoLoChanBoConCanhBao() {
    ObjectiveView view = mock(ObjectiveView.class);
    when(reads.objective(TENANT, ID)).thenReturn(Optional.of(view));
    when(checks.check(TENANT, view))
        .thenReturn(List.of(new Finding("R1", "BR-02-01", "CANH_BAO", "m", null, null)));
    assertThatThrownBy(() -> service.approve(TENANT, REVIEWER, ID, 2, null, true))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    when(checks.check(TENANT, view))
        .thenReturn(List.of(new Finding("R1", "BR-02-01", OkrRuleChecker.DAT, "m", null, null)));
    when(reviews.previousApproved(any(), any())).thenReturn(Optional.empty());
    doThrow(new IllegalStateException("mất kết nối"))
        .when(signal)
        .decided(any(), any(), any(), any(), any(), anyLong());
    assertThat(service.approve(TENANT, REVIEWER, ID, 2, null, true)).isEqualTo(3L);
  }

  @Test
  void kyKhoaThiKhongQuyetDinh() {
    doThrow(new PeriodLockedException(PeriodId.of(PERIOD)))
        .when(periods)
        .assertWritable(eq(TENANT), eq(PeriodId.of(PERIOD)), any());
    assertThatThrownBy(() -> service.approve(TENANT, REVIEWER, ID, 2, null, false))
        .isInstanceOf(PeriodLockedException.class);
    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.sendBack(TENANT, REVIEWER, ID, 2, "Sửa lại KR"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void traLaiBatBuocYKien() {
    assertThatThrownBy(() -> service.sendBack(TENANT, REVIEWER, ID, 2, " "))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-COMMENT-REQUIRED"));
    assertThatThrownBy(() -> service.sendBack(TENANT, REVIEWER, ID, 2, null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-COMMENT-REQUIRED"));
    assertThat(service.sendBack(TENANT, REVIEWER, ID, 2, " Viết lại KR 1.2 ")).isEqualTo(3L);
    verify(reviews)
        .appendReview(eq(TENANT), any(), eq(REVIEWER), eq("TRA_LAI"), eq("Viết lại KR 1.2"));
    verify(events).returned(eq(TENANT), any(), eq(REVIEWER), eq(3L));
    verify(signal).decided(TENANT, ID, "pi-1", "TRA_LAI", REVIEWER, 2L);
  }

  @Test
  void rutLaiChiNguoiSoan() {
    assertThatThrownBy(() -> service.withdraw(TENANT, REVIEWER, ID, 2))
        .isInstanceOf(OkrReviewService.Forbidden.class);
    assertThat(service.withdraw(TENANT, AUTHOR, ID, 2)).isEqualTo(3L);
    verify(signal).cancelled(TENANT, ID, "pi-1", AUTHOR, 2L);
  }

  @Test
  void huyCanLyDoVaKhongPhaiNguoiSoan() {
    assertThatThrownBy(() -> service.cancel(TENANT, REVIEWER, ID, 2, "ngắn"))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    assertThatThrownBy(() -> service.cancel(TENANT, REVIEWER, ID, 2, null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    assertThatThrownBy(
            () -> service.cancel(TENANT, AUTHOR, ID, 2, "Đơn vị giải thể theo quyết định"))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-403-SELF-APPROVAL"));
    assertThat(service.cancel(TENANT, REVIEWER, ID, 2, "Đơn vị giải thể theo quyết định"))
        .isEqualTo(3L);
    verify(events).cancelled(eq(TENANT), any(), eq(REVIEWER), eq(3L));
    verify(signal).cancelled(TENANT, ID, "pi-1", REVIEWER, 2L);
    // Mục tiêu đã duyệt: không có tiến trình để huỷ.
    when(reviews.lockTarget(TENANT, ID)).thenReturn(Optional.of(target(ID, "DA_DUYET", 2)));
    service.cancel(TENANT, REVIEWER, ID, 2, "Đơn vị giải thể theo quyết định");
    when(reviews.lockTarget(TENANT, ID)).thenReturn(Optional.of(target(ID, "DONG", 2)));
    assertThatThrownBy(
            () -> service.cancel(TENANT, REVIEWER, ID, 2, "Đơn vị giải thể theo quyết định"))
        .hasMessageContaining("OKR-409-INVALID-STATE");
  }

  @Test
  void taoPhienBanDieuChinh() {
    UUID revision = UUID.randomUUID();
    when(reviews.lockTarget(TENANT, ID)).thenReturn(Optional.of(target(ID, "DA_DUYET", 4)));
    when(reviews.pendingRevision(eq(TENANT), any())).thenReturn(Optional.empty());
    when(reviews.cloneAsRevision(eq(TENANT), any(), eq(AUTHOR))).thenReturn(revision);
    assertThat(service.createRevision(TENANT, AUTHOR, ID, 4, "Điều chỉnh chỉ tiêu giữa kỳ"))
        .isEqualTo(revision);
    assertThatThrownBy(() -> service.createRevision(TENANT, AUTHOR, ID, 4, "ngắn"))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    assertThatThrownBy(() -> service.createRevision(TENANT, AUTHOR, ID, 4, null))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-422-VALIDATION"));
    assertThatThrownBy(() -> service.createRevision(TENANT, AUTHOR, ID, 3, "Điều chỉnh chỉ tiêu"))
        .isInstanceOf(VersionConflictException.class);
    when(reviews.pendingRevision(eq(TENANT), any())).thenReturn(Optional.of(UUID.randomUUID()));
    assertThatThrownBy(() -> service.createRevision(TENANT, AUTHOR, ID, 4, "Điều chỉnh chỉ tiêu"))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-REVISION-PENDING"));
    when(reviews.pendingRevision(eq(TENANT), any())).thenReturn(Optional.empty());
    when(periods.findById(TENANT, PeriodId.of(PERIOD)))
        .thenReturn(Optional.of(period(PeriodStatus.MO_LAI_HAU_KIEM)));
    assertThatThrownBy(() -> service.createRevision(TENANT, AUTHOR, ID, 4, "Điều chỉnh chỉ tiêu"))
        .isInstanceOf(PeriodLockedException.class);
    when(periods.findById(TENANT, PeriodId.of(PERIOD))).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.createRevision(TENANT, AUTHOR, ID, 4, "Điều chỉnh chỉ tiêu"))
        .isInstanceOf(IllegalStateException.class);
    when(reviews.lockTarget(TENANT, ID)).thenReturn(Optional.of(target(ID, "CHO_DUYET", 4)));
    assertThatThrownBy(() -> service.createRevision(TENANT, AUTHOR, ID, 4, "Điều chỉnh chỉ tiêu"))
        .satisfies(t -> assertThat(code(t)).isEqualTo("OKR-409-INVALID-STATE"));
  }

  @Test
  void pheDuyetVaLichSuMangDauVetNoiDungAi() {
    var traces =
        org.mockito.Mockito.mock(vn.viettel.okrkpi.okr.application.port.OkrAiTraceRepository.class);
    var trace =
        new vn.viettel.okrkpi.okr.application.port.OkrAiTraceRepository.AiTrace(
            ID,
            null,
            UUID.randomUUID(),
            "AI_ACCEPTED",
            List.of("title"),
            List.of(),
            Instant.parse("2027-01-05T03:00:00Z"),
            AUTHOR,
            2);
    when(traces.traces(TENANT, ID)).thenReturn(List.of(trace));
    OkrReviewService withAi =
        new OkrReviewService(
            reviews, reads, checks, periods, events, () -> signal, published::add, traces);
    withAi.approve(TENANT, REVIEWER, ID, 2, null, false);
    verify(events).approved(eq(TENANT), any(), eq(REVIEWER), any(), eq(3L), eq(List.of(trace)));

    ObjectiveRow row = mock(ObjectiveRow.class);
    when(row.createdAt()).thenReturn(Instant.parse("2027-01-05T02:00:00Z"));
    when(row.createdBy()).thenReturn(AUTHOR);
    when(reads.objectiveRow(TENANT, ID)).thenReturn(Optional.of(row));
    assertThat(withAi.history(TENANT, ID, null, null, 10))
        .extracting(OkrReviewService.HistoryEntry::action)
        .containsExactly("LUU_NHAP", "TAO");
  }

  @Test
  void lichSuMoiNhatTruocVaPhanTrang() {
    Instant created = Instant.parse("2027-01-05T02:00:00Z");
    ObjectiveRow row = mock(ObjectiveRow.class);
    when(row.createdAt()).thenReturn(created);
    when(row.createdBy()).thenReturn(AUTHOR);
    when(reads.objectiveRow(TENANT, ID)).thenReturn(Optional.of(row));
    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();
    when(reviews.reviews(TENANT, ID))
        .thenReturn(
            List.of(
                new ReviewRow(r1, 1, REVIEWER, "TRA_LAI", "Sửa", created.plusSeconds(60)),
                new ReviewRow(r2, 2, REVIEWER, "DUYET", null, created.plusSeconds(120))));
    List<OkrReviewService.HistoryEntry> all = service.history(TENANT, ID, null, null, 10);
    assertThat(all)
        .extracting(OkrReviewService.HistoryEntry::action)
        .containsExactly("DUYET", "TRA_LAI", "TAO");
    assertThat(all.getFirst().status()).isEqualTo("DA_DUYET");
    assertThat(service.history(TENANT, ID, created.plusSeconds(120), r2, 10))
        .extracting(OkrReviewService.HistoryEntry::action)
        .containsExactly("TRA_LAI", "TAO");
    assertThat(service.history(TENANT, ID, null, null, 1)).hasSize(1);
    UUID missing = UUID.randomUUID();
    when(reads.objectiveRow(TENANT, missing)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.history(TENANT, missing, null, null, 10))
        .isInstanceOf(OkrComposeService.NotFound.class);
  }

  @Test
  void khongCoCongBaoThiBoQua() {
    OkrReviewService quiet =
        new OkrReviewService(reviews, reads, checks, periods, events, () -> null, o -> {});
    assertThat(quiet.withdraw(TENANT, AUTHOR, ID, 2)).isEqualTo(3L);
  }
}
