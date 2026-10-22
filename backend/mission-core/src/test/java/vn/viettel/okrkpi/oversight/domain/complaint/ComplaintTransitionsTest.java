package vn.viettel.okrkpi.oversight.domain.complaint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.CHO_KET_LUAN;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.DANG_GIAI_TRINH;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.DANG_THI_HANH;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.DA_TIEP_NHAN;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.DA_TRA_LOI;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.DONG;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.KHONG_THU_LY;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.MOI;
import static vn.viettel.okrkpi.oversight.domain.complaint.ComplaintStatus.RUT;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Bảng chuyển trạng thái QT-12 cộng {@code RUT} (CORE-07-03): mỗi dòng một ca, còn lại bị chặn. */
class ComplaintTransitionsTest {

  private static final Instant AT = Instant.parse("2026-10-21T03:00:00Z");

  /** Các dòng của bảng "Trạng thái đối tượng" TL-02b QT-12 và ngoại lệ rút. */
  static Stream<Arguments> rows() {
    return Stream.of(
        Arguments.of(null, MOI),
        Arguments.of(MOI, DA_TIEP_NHAN),
        Arguments.of(MOI, KHONG_THU_LY),
        Arguments.of(DA_TIEP_NHAN, DANG_GIAI_TRINH),
        Arguments.of(DANG_GIAI_TRINH, CHO_KET_LUAN),
        Arguments.of(DA_TIEP_NHAN, CHO_KET_LUAN),
        Arguments.of(CHO_KET_LUAN, DA_TRA_LOI),
        Arguments.of(DA_TRA_LOI, DANG_THI_HANH),
        Arguments.of(DANG_THI_HANH, DONG),
        Arguments.of(DA_TRA_LOI, DONG),
        Arguments.of(KHONG_THU_LY, DONG),
        Arguments.of(MOI, RUT),
        Arguments.of(DA_TIEP_NHAN, RUT),
        Arguments.of(DANG_GIAI_TRINH, RUT),
        Arguments.of(CHO_KET_LUAN, RUT),
        Arguments.of(RUT, DONG));
  }

  @ParameterizedTest
  @MethodSource("rows")
  void dongCuaBangDuocPhep(ComplaintStatus from, ComplaintStatus to) {
    assertThat(ComplaintTransitions.allowed(from, to)).isTrue();
  }

  @Test
  void ngoaiBangBiChan() {
    Set<String> allowed = new HashSet<>();
    rows().forEach(a -> allowed.add(a.get()[0] + ">" + a.get()[1]));
    List<ComplaintStatus> from = new ArrayList<>(List.of(ComplaintStatus.values()));
    from.add(null);
    int blocked = 0;
    for (ComplaintStatus f : from) {
      for (ComplaintStatus t : ComplaintStatus.values()) {
        if (!allowed.contains(f + ">" + t)) {
          assertThat(ComplaintTransitions.allowed(f, t)).as(f + " → " + t).isFalse();
          blocked++;
        }
      }
    }
    assertThat(blocked).isEqualTo(10 * 9 - 16);
  }

  @Test
  void rutChiTruocKetLuan() {
    assertThat(ComplaintTransitions.beforeConclusion(CHO_KET_LUAN)).isTrue();
    assertThat(ComplaintTransitions.beforeConclusion(DA_TRA_LOI)).isFalse();
    assertThat(ComplaintTransitions.beforeConclusion(DONG)).isFalse();
    assertThat(ComplaintTransitions.beforeConclusion(null)).isFalse();
  }

  @Test
  void tiepNhanGhiNguoiXuLyVaTangPhienBan() {
    Complaint c = filed();
    UUID handler = UUID.randomUUID();
    Complaint a = c.accepted(handler, AT);
    assertThat(a.status()).isEqualTo(DA_TIEP_NHAN);
    assertThat(a.handling().handlerId()).isEqualTo(handler);
    assertThat(a.handling().acceptDeadline()).isEqualTo(LocalDate.of(2026, 10, 23));
    assertThat(a.rowVersion()).isEqualTo(c.rowVersion() + 1);
    assertThatThrownBy(() -> a.accepted(handler, AT)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void khongThuLyRoiDong() {
    Complaint c = filed();
    UUID handler = UUID.randomUUID();
    Complaint r = c.notAccepted(handler, ComplaintCloseReason.QUA_HAN, "Quá thời hạn.", AT);
    assertThat(r.status()).isEqualTo(KHONG_THU_LY);
    Complaint d = r.closed(AT.plusSeconds(1));
    assertThat(d.status()).isEqualTo(DONG);
    assertThat(d.handling().closedAt()).isEqualTo(AT);
    assertThat(d.handling().closeReasonCode()).isEqualTo(ComplaintCloseReason.QUA_HAN);
    assertThatThrownBy(() -> c.notAccepted(handler, ComplaintCloseReason.RUT, "x", AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> d.closed(AT)).isInstanceOf(IllegalStateException.class);
    assertThat(ComplaintCloseReason.RUT.rejection()).isFalse();
  }

  @Test
  void rutRoiDongGiuNoiDung() {
    Complaint c = filed();
    Complaint w = c.withdrawn("Đã thống nhất.", AT);
    assertThat(w.status()).isEqualTo(RUT);
    assertThat(w.contentEnc()).isEqualTo(c.contentEnc());
    Complaint d = w.closed(AT);
    assertThat(d.status()).isEqualTo(DONG);
    assertThat(d.handling().closeReason()).isEqualTo("Đã thống nhất.");
    assertThat(d.rowVersion()).isEqualTo(c.rowVersion() + 2);
  }

  @Test
  void nhapChiSangMoi() {
    Complaint draft = draft();
    assertThatThrownBy(() -> draft.accepted(UUID.randomUUID(), AT))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> filed().filed(AT, LocalDate.of(2026, 11, 11), null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThat(draft.handling()).isEqualTo(ComplaintHandling.NONE);
  }

  @Test
  void chinhSachChonNguoiXuLyNhuQuyTacRego() {
    UUID u1 = UUID.randomUUID();
    UUID u0 = UUID.randomUUID();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    Map<String, Set<UUID>> parts = Map.of("appraiser", Set.of(a), "decider", Set.of(c));
    ComplaintHandlerPolicy.Choice same =
        ComplaintHandlerPolicy.choose(
            List.of(u1, u0), Map.of(u1, List.of(a, b), u0, List.of(c)), parts);
    assertThat(same.unit()).isEqualTo(u1);
    assertThat(same.candidates()).containsExactly(b);
    assertThat(same.escalated()).isFalse();
    assertThat(same.reason()).isNull();
    ComplaintHandlerPolicy.Choice up =
        ComplaintHandlerPolicy.choose(
            List.of(u1, u0), Map.of(u1, List.of(a), u0, List.of(b, c)), parts);
    assertThat(up.unit()).isEqualTo(u0);
    assertThat(up.escalated()).isTrue();
    assertThat(up.reason()).isEqualTo(ComplaintHandlerPolicy.ESCALATED);
    ComplaintHandlerPolicy.Choice none =
        ComplaintHandlerPolicy.choose(List.of(u1), Map.of(u1, List.of(a)), parts);
    assertThat(none.unit()).isNull();
    assertThat(none.candidates()).isEmpty();
    assertThat(none.reason()).isEqualTo(ComplaintHandlerPolicy.NO_CANDIDATE);
    assertThat(ComplaintHandlerPolicy.conflicts(c, parts)).containsExactly("decider");
    assertThat(ComplaintHandlerPolicy.conflicts(b, parts)).isEmpty();
  }

  private static Complaint draft() {
    return new Complaint(
        UUID.randomUUID(),
        "KN-2026-0001",
        UUID.randomUUID(),
        20264,
        UUID.randomUUID(),
        UUID.randomUUID(),
        ComplaintKind.KHIEU_NAI,
        ComplaintStage.THAM_DINH,
        "Khiếu nại",
        List.of(),
        new byte[] {1, 2},
        null,
        UUID.randomUUID(),
        List.of(),
        false,
        null,
        null,
        null,
        null,
        null,
        AT,
        0,
        null);
  }

  private static Complaint filed() {
    return draft()
        .filed(AT, LocalDate.of(2026, 11, 11), UUID.randomUUID(), LocalDate.of(2026, 10, 23));
  }
}
