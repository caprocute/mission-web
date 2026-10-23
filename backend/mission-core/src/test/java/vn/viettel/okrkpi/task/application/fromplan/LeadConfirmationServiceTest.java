package vn.viettel.okrkpi.task.application.fromplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.fromplan.port.LeadConfirmations;
import vn.viettel.okrkpi.task.application.fromplan.port.LeadConfirmations.LineRow;
import vn.viettel.okrkpi.task.application.plan.PlanChecker;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.Assignee;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;

class LeadConfirmationServiceTest {

  final TenantId tenant = TenantId.of(UUID.randomUUID());
  final UUID owner = UUID.randomUUID();
  final UUID other = UUID.randomUUID();
  final UUID item = UUID.randomUUID();
  final UUID actor = UUID.randomUUID();
  final LeadConfirmations port = mock(LeadConfirmations.class);
  final LeadConfirmationService service =
      new LeadConfirmationService(
          port, Clock.fixed(Instant.parse("2030-05-01T00:00:00Z"), ZoneOffset.UTC));

  LineRow line(UUID lead, String status, String confirmation) {
    return new LineRow(item, 20302, 2, owner, lead, status, confirmation);
  }

  @Test
  void xacNhanVaTuChoi() {
    LineRow l = line(other, "CHO_DUYET", null);
    when(port.line(tenant, item)).thenReturn(Optional.of(l));
    service.decide(tenant, item, true, null, actor, List.of(other));
    verify(port)
        .record(tenant, l, "DA_XAC_NHAN", null, actor, Instant.parse("2030-05-01T00:00:00Z"));
    service.decide(tenant, item, false, " Không đúng chức năng ", actor, List.of(other));
    verify(port)
        .record(
            tenant,
            l,
            "TU_CHOI",
            "Không đúng chức năng",
            actor,
            Instant.parse("2030-05-01T00:00:00Z"));
  }

  @Test
  void chanKhiKhongHopLe() {
    when(port.line(tenant, item)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.decide(tenant, item, true, null, actor, List.of(other)))
        .isInstanceOf(java.util.NoSuchElementException.class);
    when(port.line(tenant, item)).thenReturn(Optional.of(line(owner, "NHAP", null)));
    assertThatThrownBy(() -> service.decide(tenant, item, true, null, actor, List.of(owner)))
        .isInstanceOf(BusinessException.class);
    when(port.line(tenant, item)).thenReturn(Optional.of(line(other, "NHAP", null)));
    assertThatThrownBy(() -> service.decide(tenant, item, true, null, actor, null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.decide(tenant, item, true, null, actor, List.of(owner)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.decide(tenant, item, false, "", actor, List.of(other)))
        .isInstanceOf(BusinessException.class);
    when(port.line(tenant, item)).thenReturn(Optional.of(line(other, "DA_DUYET", null)));
    assertThatThrownBy(() -> service.decide(tenant, item, true, null, actor, List.of(other)))
        .isInstanceOf(BusinessException.class);
    verify(port, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void ketQuaKiemTra() {
    ItemRow row =
        new ItemRow(
            item,
            2,
            null,
            null,
            null,
            "Phối hợp",
            "THEN_CHOT",
            new Assignee("UNIT", other, ""),
            List.of(),
            "Báo cáo",
            BigDecimal.ONE,
            "báo cáo",
            3,
            BigDecimal.TEN,
            null,
            null,
            null,
            null);
    ItemRow draft =
        new ItemRow(
            null,
            3,
            null,
            null,
            null,
            "Mới",
            "THEN_CHOT",
            new Assignee("UNIT", other, ""),
            List.of(),
            "Báo cáo",
            BigDecimal.ONE,
            "báo cáo",
            3,
            BigDecimal.TEN,
            null,
            null,
            null,
            null);
    assertThat(service.checks(tenant, List.of(draft))).isEmpty();
    UUID third = UUID.randomUUID();
    when(port.lines(any(), anyCollection()))
        .thenReturn(
            List.of(
                line(other, "NHAP", null),
                line(third, "NHAP", "TU_CHOI"),
                line(owner, "NHAP", null)));
    List<PlanChecker.Check> checks = service.checks(tenant, List.of(row, draft));
    assertThat(checks)
        .extracting(PlanChecker.Check::level)
        .containsExactly(PlanChecker.Level.CANH_BAO, PlanChecker.Level.CHAN);
    assertThat(checks).allSatisfy(c -> assertThat(c.rule()).isEqualTo("QT-04"));
  }
}
