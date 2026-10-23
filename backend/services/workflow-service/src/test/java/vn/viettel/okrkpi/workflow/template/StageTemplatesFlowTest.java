package vn.viettel.okrkpi.workflow.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;
import vn.viettel.okrkpi.workflow.domain.stage.StageTemplate;

/**
 * Kiểm thử chung của bốn mẫu phần 2: hạn và mốc nhắc theo lịch làm việc của tenant (ngày nghỉ, ngày
 * làm bù), biến khởi tạo dưới ngân sách, định nghĩa dùng chung không gắn tenant.
 */
class StageTemplatesFlowTest {

  @Test
  void hanTinhTheoNgayLamViecCoNgayNghiVaNgayLamBu() {
    try (StageEngineHarness h = new StageEngineHarness()) {
      // Thứ Năm 22, thứ Sáu 23 nghỉ; thứ Bảy 24 làm bù.
      h.calendar.days.put(LocalDate.of(2026, 10, 22), false);
      h.calendar.days.put(LocalDate.of(2026, 10, 23), false);
      h.calendar.days.put(LocalDate.of(2026, 10, 24), true);
      UUID profile = UUID.randomUUID();
      UUID member = UUID.randomUUID();
      h.service.start(h.command(StageTemplate.DUYET_HO_SO_DANH_GIA, profile, member));
      String pid = h.pid(StageTemplate.DUYET_HO_SO_DANH_GIA, profile);
      Task task = h.task(pid);
      // 3 ngày làm việc: thứ Bảy 24, thứ Hai 26, thứ Ba 27; nhắc trước 1 ngày: 08:00 thứ Hai 26.
      assertThat(task.getDueDate().toInstant())
          .isEqualTo(StageEngineHarness.at17(LocalDate.of(2026, 10, 27)));
      assertThat(h.timerDue(pid, "nhac_tuDanhGia"))
          .isEqualTo(StageEngineHarness.at8(LocalDate.of(2026, 10, 26)));
      assertThat(h.timerDue(pid, "quaHan_tuDanhGia"))
          .isEqualTo(StageEngineHarness.at17(LocalDate.of(2026, 10, 27)));
    }
  }

  @Test
  void bonMauDungChungDinhNghiaVaBienDuoiNganSach() {
    try (StageEngineHarness h = new StageEngineHarness()) {
      for (StageTemplate t : StageTemplate.values()) {
        assertThat(
                h.engine
                    .getRepositoryService()
                    .createProcessDefinitionQuery()
                    .processDefinitionKey(t.code())
                    .processDefinitionWithoutTenantId()
                    .count())
            .as(t.code())
            .isEqualTo(1);
        UUID id = UUID.randomUUID();
        h.service.start(h.command(t, id, UUID.randomUUID()));
        String pid = h.pid(t, id);
        assertThat(ApprovalVariables.sizeOf(h.engine.getRuntimeService().getVariables(pid)))
            .as(t.code())
            .isLessThan(ApprovalVariables.MAX_TOTAL_BYTES);
        assertThat(
                h.engine
                    .getRuntimeService()
                    .createProcessInstanceQuery()
                    .processInstanceId(pid)
                    .singleResult()
                    .getTenantId())
            .isEqualTo(h.tenant.toString());
      }
    }
  }
}
