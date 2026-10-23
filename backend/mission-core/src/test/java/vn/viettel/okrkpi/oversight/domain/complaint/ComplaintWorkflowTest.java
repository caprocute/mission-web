package vn.viettel.okrkpi.oversight.domain.complaint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.oversight.domain.complaint.ComplaintWorkflow.Step;

/** Kiểm thử đơn vị CORE-07-03: bảng chuyển trạng thái, chọn người xử lý, phần xử lý. */
class ComplaintWorkflowTest {

  private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");

  private static final Map<Step, Map.Entry<Set<ComplaintStatus>, ComplaintStatus>> EXPECTED =
      Map.of(
          Step.ACCEPT, Map.entry(Set.of(ComplaintStatus.MOI), ComplaintStatus.DA_TIEP_NHAN),
          Step.REJECT, Map.entry(Set.of(ComplaintStatus.MOI), ComplaintStatus.KHONG_THU_LY),
          Step.WITHDRAW,
              Map.entry(
                  Set.of(
                      ComplaintStatus.MOI,
                      ComplaintStatus.DA_TIEP_NHAN,
                      ComplaintStatus.DANG_GIAI_TRINH,
                      ComplaintStatus.CHO_KET_LUAN),
                  ComplaintStatus.RUT),
          Step.REQUEST_EXPLANATIONS,
              Map.entry(Set.of(ComplaintStatus.DA_TIEP_NHAN), ComplaintStatus.DANG_GIAI_TRINH),
          Step.EXPLANATIONS_DONE,
              Map.entry(Set.of(ComplaintStatus.DANG_GIAI_TRINH), ComplaintStatus.CHO_KET_LUAN),
          Step.SKIP_EXPLANATIONS,
              Map.entry(Set.of(ComplaintStatus.DA_TIEP_NHAN), ComplaintStatus.CHO_KET_LUAN),
          Step.RESPOND, Map.entry(Set.of(ComplaintStatus.CHO_KET_LUAN), ComplaintStatus.DA_TRA_LOI),
          Step.EXECUTE,
              Map.entry(Set.of(ComplaintStatus.DA_TRA_LOI), ComplaintStatus.DANG_THI_HANH),
          Step.CLOSE,
              Map.entry(
                  Set.of(ComplaintStatus.KHONG_THU_LY, ComplaintStatus.RUT), ComplaintStatus.DONG));

  @Test
  void moiCanhChuyenTrangThaiDungBangVaMoiCanhKhacBiTuChoi() {
    assertThat(EXPECTED.keySet()).containsExactlyInAnyOrder(Step.values());
    for (Step step : Step.values()) {
      var edge = EXPECTED.get(step);
      for (ComplaintStatus from : ComplaintStatus.values()) {
        if (edge.getKey().contains(from)) {
          assertThat(ComplaintWorkflow.next(from, step))
              .as(step + " từ " + from)
              .isEqualTo(edge.getValue());
          assertThat(ComplaintWorkflow.allows(from, step)).isTrue();
        } else {
          assertThatThrownBy(() -> ComplaintWorkflow.next(from, step))
              .as(step + " từ " + from)
              .isInstanceOf(ComplaintWorkflow.InvalidState.class)
              .hasMessageContaining("hiếu nại");
          assertThat(ComplaintWorkflow.allows(from, step)).isFalse();
        }
      }
      assertThatThrownBy(() -> ComplaintWorkflow.next(null, step))
          .isInstanceOf(ComplaintWorkflow.InvalidState.class);
      assertThat(ComplaintWorkflow.allows(null, step)).isFalse();
    }
  }

  @Test
  void rutSauKhiDaTraLoiBiTuChoi() {
    for (ComplaintStatus s :
        EnumSet.of(
            ComplaintStatus.DA_TRA_LOI, ComplaintStatus.DANG_THI_HANH, ComplaintStatus.DONG)) {
      assertThatThrownBy(() -> ComplaintWorkflow.next(s, Step.WITHDRAW))
          .hasMessage("Khiếu nại đã có kết luận hoặc đã đóng, không rút được nữa.");
    }
  }

  @Test
  void ungVienOCapGanNhatConNguoiHopLeVaNangCapKhiHet() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    var same = ComplaintHandlerRules.candidates(List.of(Set.of(a, b), Set.of(c)), p -> p.equals(a));
    assertThat(same.people()).containsExactly(b);
    assertThat(same.level()).isZero();
    assertThat(same.escalated()).isFalse();

    var up =
        ComplaintHandlerRules.candidates(List.of(Set.of(a), Set.of(), Set.of(c)), p -> p.equals(a));
    assertThat(up.people()).containsExactly(c);
    assertThat(up.level()).isEqualTo(2);
    assertThat(up.escalated()).isTrue();

    var none = ComplaintHandlerRules.candidates(List.of(Set.of(a)), p -> true);
    assertThat(none.people()).isEmpty();
    assertThat(none.level()).isEqualTo(-1);
    assertThat(none.escalated()).isFalse();
    assertThat(new ComplaintHandlerRules.Candidates(null, -1).people()).isEmpty();
  }

  @Test
  void phanXuLyGhiTungBuocKhongMatBuocTruoc() {
    UUID handler = UUID.randomUUID();
    UUID sign = UUID.randomUUID();
    UUID reopen = UUID.randomUUID();
    ComplaintConclusion k =
        new ComplaintConclusion(
            ComplaintResolution.CHAP_NHAN_MOT_PHAN,
            null,
            new byte[] {1},
            UUID.randomUUID(),
            "01/TL-KN",
            null,
            handler,
            NOW);
    ComplaintHandling h =
        ComplaintHandling.NONE
            .accepted(handler, NOW)
            .concluded(k, reopen)
            .signing(sign)
            .responded(NOW.plusSeconds(60))
            .closed(CloseReason.RUT, "Rút", NOW.plusSeconds(120));
    assertThat(h.handlerPersonId()).isEqualTo(handler);
    assertThat(h.acceptedAt()).isEqualTo(NOW);
    assertThat(h.conclusion().adjustments()).isEmpty();
    assertThat(h.reopenRequestId()).isEqualTo(reopen);
    assertThat(h.responseSignRequestId()).isEqualTo(sign);
    assertThat(h.respondedAt()).isEqualTo(NOW.plusSeconds(60));
    assertThat(h.closeReason()).isEqualTo(CloseReason.RUT);
    assertThat(h.closeNote()).isEqualTo("Rút");
    assertThat(h.closedAt()).isEqualTo(NOW.plusSeconds(120));
    assertThat(ComplaintHandling.NONE.handlerPersonId()).isNull();
  }

  @Test
  void ketQuaVaDieuChinhChuanHoaGiaTri() {
    assertThat(ComplaintResolution.GIU_NGUYEN.adjusts()).isFalse();
    assertThat(ComplaintResolution.GIU_NGUYEN.outcome()).isEqualTo("GIU_NGUYEN");
    assertThat(ComplaintResolution.CHAP_NHAN.outcome()).isEqualTo("DIEU_CHINH");
    ComplaintAdjustment a = new ComplaintAdjustment("TONG_DIEM", 91, 93.5, "Bỏ trừ hệ số");
    assertThat(a.oldValue()).isEqualTo(new BigDecimal("91"));
    assertThat(a.newValue()).isEqualTo(new BigDecimal("93.5"));
    ComplaintAdjustment b = new ComplaintAdjustment("XEP_LOAI", "HTTNV", null, null);
    assertThat(b.oldValue()).isEqualTo("HTTNV");
    assertThat(b.newValue()).isNull();
    assertThat(new ComplaintAdjustment("X", new BigDecimal("1.0"), null, null).oldValue())
        .isEqualTo(new BigDecimal("1.0"));
    assertThatThrownBy(() -> new ComplaintAdjustment(null, 1, 2, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void banGhiTraLoiBatBuocCacTruongChinh() {
    UUID id = UUID.randomUUID();
    ComplaintResponse r =
        new ComplaintResponse(
            id,
            UUID.randomUUID(),
            UUID.randomUUID(),
            20264,
            ComplaintRecordKind.YEU_CAU,
            UUID.randomUUID(),
            new ExplanationParty(ExplanationParty.Kind.DON_VI, UUID.randomUUID()),
            null,
            LocalDate.of(2026, 10, 28),
            new byte[] {1},
            UUID.randomUUID(),
            null,
            null,
            null,
            null,
            null,
            NOW);
    assertThat(r.attachmentIds()).isEmpty();
    assertThat(r.adjustments()).isEmpty();
    assertThat(r.party().kind()).isEqualTo(ExplanationParty.Kind.DON_VI);
    assertThatThrownBy(() -> new ExplanationParty(ExplanationParty.Kind.NGUOI, null))
        .isInstanceOf(NullPointerException.class);
    assertThat(CloseReason.valueOf("TRUNG_NOI_DUNG")).isNotNull();
    assertThat(ComplaintRecordKind.values()).hasSize(4);
  }
}
