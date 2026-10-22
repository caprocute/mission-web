package vn.viettel.okrkpi.task.application.similar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.similar.port.SimilarTaskCandidates;
import vn.viettel.okrkpi.task.application.similar.port.SimilarTaskRows;
import vn.viettel.okrkpi.task.application.similar.port.SimilarTaskRows.Probe;
import vn.viettel.okrkpi.task.application.similar.port.SimilarTaskRows.Row;

class SimilarTaskServiceTest {

  static final TenantId T = TenantId.of(UUID.randomUUID());
  static final UUID PERIOD = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final byte[] FP = {1, 2, 3};

  final SimilarTaskRows rows = mock(SimilarTaskRows.class);
  final SimilarTaskCandidates remote = mock(SimilarTaskCandidates.class);

  static Row row(
      String code, String title, UUID unit, String status, boolean restricted, byte[] fp) {
    return new Row(
        UUID.randomUUID(),
        PERIOD,
        unit,
        code,
        title,
        status,
        restricted,
        null,
        BigDecimal.ZERO,
        fp);
  }

  static Probe probe(UUID self) {
    return new Probe(self, PERIOD, UNIT, "Báo cáo tổng kết công tác quý III", "Báo cáo", FP);
  }

  @Test
  void localSourceFiltersUnitStatusRestrictedAndThreshold() {
    UUID self = UUID.randomUUID();
    Row exact = row("NV-1", "Tên khác hẳn", UNIT, "DA_GIAO", false, FP);
    Row close =
        row("NV-2", "Báo cáo tổng kết công tác quý IV", UNIT, "DANG_THUC_HIEN", false, null);
    Row other =
        row("NV-3", "Báo cáo tổng kết công tác quý III", UUID.randomUUID(), "DA_GIAO", false, null);
    Row hidden = row("NV-4", "Báo cáo tổng kết công tác quý III", UNIT, "DA_GIAO", true, null);
    Row cancelled = row("NV-5", "Báo cáo tổng kết công tác quý III", UNIT, "HUY", false, null);
    Row far = row("NV-6", "Tổ chức hội nghị", UNIT, "DA_GIAO", false, null);
    Row me =
        new Row(
            self,
            PERIOD,
            UNIT,
            "NV-0",
            "Báo cáo tổng kết công tác quý III",
            "NHAP",
            false,
            null,
            null,
            FP);
    when(rows.byFingerprint(T, PERIOD, FP)).thenReturn(List.of(exact, me));
    when(rows.inUnit(T, PERIOD, UNIT, SimilarTaskService.LOCAL_CANDIDATES))
        .thenReturn(List.of(close, other, hidden, cancelled, far));
    SimilarTaskService.Result r = new SimilarTaskService(rows, null).find(T, probe(self), null);
    assertThat(r.checked()).isTrue();
    assertThat(r.matches()).extracting(m -> m.row().code()).containsExactly("NV-1", "NV-2");
    assertThat(r.matches().get(0).similarity()).isEqualByComparingTo("1");
  }

  @Test
  void remoteSourceAndFailureFallsBackToFingerprint() {
    Row close = row("NV-2", "Báo cáo tổng kết công tác quý IV", UNIT, "DA_GIAO", false, null);
    when(remote.candidates(eq(T), eq(PERIOD), eq(UNIT), any(), anyInt(), eq("Bearer t")))
        .thenReturn(List.of(close.id()));
    when(rows.byIds(T, PERIOD, List.of(close.id()))).thenReturn(List.of(close));
    SimilarTaskService svc = new SimilarTaskService(rows, remote);
    assertThat(svc.find(T, probe(null), "Bearer t").matches()).hasSize(1);
    verify(rows, never()).inUnit(any(), any(), any(), anyInt());

    when(remote.candidates(any(), any(), any(), any(), anyInt(), any()))
        .thenThrow(new SimilarTaskCandidates.Unavailable("timeout", null));
    SimilarTaskService.Result down = svc.find(T, probe(null), null);
    assertThat(down.checked()).isFalse();
    assertThat(down.found()).isFalse();

    doReturn(List.of()).when(remote).candidates(any(), any(), any(), any(), anyInt(), any());
    assertThat(svc.find(T, probe(null), null).checked()).isTrue();
  }

  @Test
  void blankProbeAndMissingTask() {
    SimilarTaskService svc = new SimilarTaskService(rows, null);
    assertThat(svc.find(T, new Probe(null, PERIOD, UNIT, " ", null, null), null).found()).isFalse();
    assertThat(svc.find(T, new Probe(null, null, UNIT, "x", null, null), null).found()).isFalse();
    when(rows.probe(T, PERIOD)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> svc.similarTo(T, PERIOD, null))
        .isInstanceOf(NoSuchElementException.class);
    UUID id = UUID.randomUUID();
    when(rows.probe(T, id)).thenReturn(Optional.of(new Probe(id, PERIOD, null, "Tên", null, null)));
    assertThat(svc.similarTo(T, id, null).matches()).isEmpty();
  }
}
