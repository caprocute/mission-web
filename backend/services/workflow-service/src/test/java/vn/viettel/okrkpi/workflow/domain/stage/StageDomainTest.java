package vn.viettel.okrkpi.workflow.domain.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalScope;

/** Quy tắc miền của bốn mẫu phần 2 (DV-06-04). */
class StageDomainTest {

  @Test
  void nguoiThamGiaMaHoaVaGiaiMaBoNhomLa() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    String encoded =
        Participants.encode(
            Map.of("appraiser", List.of(a, a), "decider", List.of(b), "khac", List.of(a)));
    assertThat(encoded).isEqualTo("appraiser=" + a + ";decider=" + b);
    assertThat(Participants.decode(encoded + ";khac=" + a + ";hong"))
        .containsOnlyKeys("appraiser", "decider");
    assertThat(Participants.all(Participants.decode(encoded))).containsExactly(a, b);
    assertThat(Participants.decode(null)).isEmpty();
    assertThat(Participants.decode(" ")).isEmpty();
  }

  @Test
  void chonNguoiLayDonViDauTienDuNguoiItViecTruoc() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    UUID x = UUID.randomUUID();
    UUID y = UUID.randomUUID();
    UUID z = UUID.randomUUID();
    Map<UUID, List<UUID>> holders = Map.of(u1, List.of(x, y), u2, List.of(x, y, z));
    // Ở u1 chỉ còn y sau khi loại x: không đủ hai người, lên u2.
    CandidatePicker.Pick pick =
        CandidatePicker.pick(List.of(u1, u2), holders, Set.of(x), Map.of(y, 3L, z, 0L), 2)
            .orElseThrow();
    assertThat(pick.unitId()).isEqualTo(u2);
    assertThat(pick.assignees()).containsExactly(z, y);
    assertThat(pick.escalated()).isTrue();
    assertThat(CandidatePicker.pick(List.of(u1), holders, Set.of(x, y), Map.of(), 1)).isEmpty();
    assertThatThrownBy(() -> CandidatePicker.pick(List.of(u1), holders, Set.of(), Map.of(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keHoachBuocMaHoaKhuHoi() {
    UUID deputy = UUID.randomUUID();
    StagePlan plan =
        new StagePlan(
            Map.of(
                "thamDinh",
                new StagePlan.Planned(
                    "thamDinh",
                    "appraiser",
                    ApprovalScope.CAP_TREN_TRUC_TIEP,
                    3,
                    1,
                    OverdueAction.CHUYEN_CAP_PHO_UY_QUYEN,
                    deputy),
                "deXuat",
                new StagePlan.Planned(
                    "deXuat",
                    "manager",
                    ApprovalScope.DON_VI_TRUC_TIEP,
                    2,
                    1,
                    OverdueAction.NHAC_LAI,
                    null)));
    StagePlan back = StagePlan.decode(plan.encode());
    assertThat(back).isEqualTo(plan);
    assertThat(back.step("thamDinh").orElseThrow().substituteId()).isEqualTo(deputy);
    assertThat(back.step("khong")).isEmpty();
  }

  @Test
  void buocKiemHanVaNhac() {
    assertThatThrownBy(
            () ->
                new StageStep(
                    "a",
                    "A",
                    "member",
                    StageStep.Target.NGUOI,
                    ApprovalScope.DON_VI_TRUC_TIEP,
                    16,
                    1,
                    OverdueAction.NHAC_LAI,
                    null,
                    false,
                    false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StageStep(
                    "a",
                    "A",
                    "member",
                    StageStep.Target.NGUOI,
                    ApprovalScope.DON_VI_TRUC_TIEP,
                    2,
                    2,
                    OverdueAction.NHAC_LAI,
                    null,
                    false,
                    false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mauTraCuuTheoMaLoaiVaMocKy() {
    assertThat(StageTemplate.byCode("xu-ly-khieu-nai")).contains(StageTemplate.XU_LY_KHIEU_NAI);
    assertThat(StageTemplate.byCode("khong")).isEmpty();
    assertThat(StageTemplate.byBusinessType("REOPEN_REQUEST"))
        .contains(StageTemplate.MO_LAI_KY_HAI_NGUOI);
    assertThat(StageTemplate.byBusinessType("OKR_OBJECTIVE")).isEmpty();
    assertThat(StageTemplate.bySignature("oversight", "TRA_LOI_KHIEU_NAI"))
        .contains(StageTemplate.XU_LY_KHIEU_NAI);
    assertThat(StageTemplate.bySignature("oversight", "PHE_DUYET_MO_LAI"))
        .contains(StageTemplate.MO_LAI_KY_HAI_NGUOI);
    assertThat(StageTemplate.bySignature("policy", "PHAT_HANH_POLICY"))
        .contains(StageTemplate.DUYET_PHAT_HANH_POLICY_PACK);
    assertThat(StageTemplate.bySignature("oversight", "KHIEU_NAI")).isEmpty();
    assertThat(StageTemplate.DUYET_HO_SO_DANH_GIA.step("QUYET_DINH").orElseThrow().elementId())
        .isEqualTo("quyetDinh");
    assertThat(StageTemplate.DUYET_HO_SO_DANH_GIA.indexOf("khong")).isEqualTo(-1);
    assertThat(StageTemplate.signedOutcome("kyTraLoi")).isEqualTo("KY");
    assertThat(StageTemplate.signedOutcome("tuDanhGia")).isEqualTo("NOP");
    assertThat(StageTemplate.signedOutcome("pheDuyet")).isEqualTo("DUYET");
    assertThat(StageTemplate.assigneeVariable("thamDinh")).isEmpty();
    assertThat(StageTemplate.outcomes("khong")).isEmpty();
    assertThat(OverdueAction.parse("CHUYEN_CAP_TREN")).contains(OverdueAction.CHUYEN_CAP_TREN);
    assertThat(OverdueAction.parse("SAI")).isEmpty();
  }

  @Test
  void lichBuocKepMocNhacTrongKhoangLucVaoVaHan() {
    Instant now = Instant.parse("2026-10-21T02:00:00Z");
    LocalDate due = LocalDate.of(2026, 10, 22);
    StageDeadlines.Schedule s = StageDeadlines.schedule(now, due, 3, Map.of());
    assertThat(s.remind()).isEqualTo(now);
    assertThat(StageDeadlines.minus(LocalDate.of(2026, 10, 26), 1, Map.of()))
        .isEqualTo(LocalDate.of(2026, 10, 23));
    StageDeadlines.Schedule late =
        StageDeadlines.schedule(Instant.parse("2026-10-22T11:00:00Z"), due, 0, Map.of());
    assertThat(late.remind()).isEqualTo(late.due());
  }
}
