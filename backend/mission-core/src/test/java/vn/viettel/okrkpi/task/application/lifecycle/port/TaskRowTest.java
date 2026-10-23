package vn.viettel.okrkpi.task.application.lifecycle.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.task.application.lifecycle.port.TaskLifecycleRepository.TaskRow;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskStatus;

class TaskRowTest {

  @Test
  void mucHeSoMacDinhRongVaGiuBanSao() {
    TaskRow old = row(null);
    assertThat(old.nodeKind()).isEqualTo("NHIEM_VU");
    assertThat(old.coefficientLevels()).isEmpty();
    TaskRow withLevels =
        new TaskRow(
            old.id(),
            old.periodId(),
            1,
            "NV",
            "T",
            null,
            "THUONG_XUYEN",
            "DOT_XUAT",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            "bản",
            null,
            null,
            BigDecimal.ZERO,
            TaskStatus.NHAP,
            false,
            null,
            "v",
            0,
            "KPI",
            null,
            null,
            Map.of("he_so_nhom", "NHOM_1"),
            "TR1");
    assertThat(withLevels.coefficientLevels()).containsEntry("he_so_nhom", "NHOM_1");
    TaskRow noLevels =
        new TaskRow(
            old.id(),
            old.periodId(),
            1,
            "NV",
            "T",
            null,
            "THUONG_XUYEN",
            "DOT_XUAT",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            "bản",
            null,
            null,
            BigDecimal.ZERO,
            TaskStatus.NHAP,
            false,
            null,
            "v",
            0,
            "KPI",
            null,
            null,
            null,
            null);
    assertThat(noLevels.coefficientLevels()).isEqualTo(Map.of());
  }

  private static TaskRow row(String unused) {
    return new TaskRow(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        "NV",
        "T",
        null,
        "THUONG_XUYEN",
        "DOT_XUAT",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        "bản",
        null,
        null,
        BigDecimal.ZERO,
        TaskStatus.NHAP,
        false,
        null,
        "v",
        0);
  }
}
