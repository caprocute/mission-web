package vn.viettel.okrkpi.workflow.application.periodclose;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.workflow.application.WorkflowNotFoundException;
import vn.viettel.okrkpi.workflow.application.saga.SagaJournal.Action;
import vn.viettel.okrkpi.workflow.application.saga.SagaService;
import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;
import vn.viettel.okrkpi.workflow.domain.saga.SagaType;

/** DV-06-07: huỷ hai người duyệt, chạy lại hồ sơ lỗi theo khoá chống lặp, chấp nhận kèm lý do. */
class PeriodCloseControlServiceTest {

  private final UUID sagaId = UUID.randomUUID();
  private SagaService sagas;
  private SagaInstance saga;
  private PeriodCloseControlService control;

  @BeforeEach
  void setUp() {
    sagas = mock(SagaService.class);
    saga = mock(SagaInstance.class);
    when(saga.type()).thenReturn(SagaType.CHOT_KY);
    when(sagas.find(sagaId)).thenReturn(Optional.of(saga));
    control =
        new PeriodCloseControlService(
            sagas, Clock.fixed(Instant.parse("2026-12-31T10:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void huyCanHaiNguoiKhacNhauVaDungLoaiSaga() {
    UUID a = UUID.randomUUID();
    assertThatThrownBy(() -> control.cancel(sagaId, a, a))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(PeriodCloseControlService.SAME_PERSON));
    verify(sagas, never()).control(any(), any());

    control.cancel(sagaId, a, UUID.randomUUID());
    verify(sagas).control(sagaId, SagaService.Control.BU_TRU);

    UUID other = UUID.randomUUID();
    SagaInstance packaging = mock(SagaInstance.class);
    when(packaging.type()).thenReturn(SagaType.DONG_GOI_HO_SO);
    when(sagas.find(other)).thenReturn(Optional.of(packaging));
    assertThatThrownBy(() -> control.cancel(other, a, UUID.randomUUID()))
        .isInstanceOf(WorkflowNotFoundException.class);
    assertThatThrownBy(() -> control.pause(UUID.randomUUID()))
        .isInstanceOf(WorkflowNotFoundException.class);

    control.pause(sagaId);
    control.resume(sagaId);
    verify(sagas).control(sagaId, SagaService.Control.TAM_DUNG);
    verify(sagas).control(sagaId, SagaService.Control.TIEP_TUC);
  }

  @Test
  void chayLaiCungKhoaChoCungMaLoVaThieuKhoaBiTuChoi() {
    UUID first = control.retryFailedProfiles(sagaId, "k-1");
    UUID again = control.retryFailedProfiles(sagaId, " k-1 ");
    UUID other = control.retryFailedProfiles(sagaId, "k-2");
    assertThat(again).isEqualTo(first);
    assertThat(other).isNotEqualTo(first);
    verify(sagas, org.mockito.Mockito.times(2))
        .annotateWaitingStep(
            sagaId,
            PeriodCloseSaga.TINH_DIEM_CHOT_KY,
            Action.CHAY_LAI_LOI,
            Map.of(
                PeriodCloseSteps.Scoring.RETRY_NOTE,
                first.toString(),
                PeriodCloseSteps.Scoring.RETRY_PROFILES_NOTE,
                List.of()));
    UUID profile = UUID.randomUUID();
    control.retryFailedProfiles(sagaId, "k-3", List.of(profile));
    control.retryFailedProfiles(sagaId, "k-4", null);
    verify(sagas)
        .annotateWaitingStep(
            eq(sagaId),
            eq(PeriodCloseSaga.TINH_DIEM_CHOT_KY),
            eq(Action.CHAY_LAI_LOI),
            org.mockito.ArgumentMatchers.argThat(
                m -> List.of(profile.toString()).equals(m.get("hoSoChayLai"))));
    assertThatThrownBy(() -> control.retryFailedProfiles(sagaId, " "))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> control.retryFailedProfiles(sagaId, null))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void chapNhanDeLaiLoiGhiNguoiLyDoVaThoiDiem() {
    UUID by = UUID.randomUUID();
    assertThatThrownBy(() -> control.acceptFailedProfiles(sagaId, by, "  "))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> control.acceptFailedProfiles(sagaId, by, null))
        .isInstanceOf(BusinessException.class);

    control.acceptFailedProfiles(sagaId, by, "  Đã xác minh nguồn dữ liệu  ");
    ArgumentCaptor<Map<String, Object>> notes = ArgumentCaptor.forClass(Map.class);
    verify(sagas)
        .annotateWaitingStep(
            eq(sagaId),
            eq(PeriodCloseSaga.TINH_DIEM_CHOT_KY),
            eq(Action.CHAP_NHAN_LOI),
            notes.capture());
    assertThat((Map<String, Object>) notes.getValue().get(PeriodCloseSteps.Scoring.ACCEPT_NOTE))
        .containsEntry("nguoiChapNhan", by.toString())
        .containsEntry("lyDo", "Đã xác minh nguồn dữ liệu")
        .containsEntry("luc", "2026-12-31T10:00:00Z");
  }
}
