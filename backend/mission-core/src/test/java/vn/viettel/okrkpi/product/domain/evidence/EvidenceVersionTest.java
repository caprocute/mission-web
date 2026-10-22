package vn.viettel.okrkpi.product.domain.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.viettel.okrkpi.product.domain.evidence.EvidenceFixtures.T0;
import static vn.viettel.okrkpi.product.domain.evidence.EvidenceFixtures.version;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Bảng chuyển trạng thái phiên bản minh chứng đầy đủ: mọi cặp nguồn × lệnh (CORE-03-02). */
class EvidenceVersionTest {

  private static final UUID SUBMITTER = UUID.randomUUID();

  enum Lenh {
    SAVE_DRAFT(
        v ->
            v.saveDraft(
                new EvidenceVersion.DraftContent(
                    v.objectId(), v.contentSha256(), null, false, "ghi chú"))),
    SUBMIT(v -> v.submit(SUBMITTER, T0)),
    OPEN_REVIEW(EvidenceVersion::openReview),
    ACCEPT(v -> v.accept(T0)),
    REQUEST_REVISION(v -> v.requestRevision("Bổ sung số liệu quý")),
    REJECT(v -> v.reject("Không đúng sản phẩm được giao"));

    final UnaryOperator<EvidenceVersion> op;

    Lenh(UnaryOperator<EvidenceVersion> op) {
      this.op = op;
    }
  }

  /** Cặp hợp lệ và trạng thái đích; mọi cặp khác phải bị từ chối. */
  private static final Map<Lenh, Map<EvidenceStatus, EvidenceStatus>> HOP_LE =
      new EnumMap<>(Lenh.class);

  static {
    HOP_LE.put(Lenh.SAVE_DRAFT, Map.of(EvidenceStatus.NHAP, EvidenceStatus.NHAP));
    HOP_LE.put(Lenh.SUBMIT, Map.of(EvidenceStatus.NHAP, EvidenceStatus.DA_NOP));
    HOP_LE.put(Lenh.OPEN_REVIEW, Map.of(EvidenceStatus.DA_NOP, EvidenceStatus.DANG_NGHIEM_THU));
    HOP_LE.put(Lenh.ACCEPT, Map.of(EvidenceStatus.DANG_NGHIEM_THU, EvidenceStatus.DA_XAC_NHAN));
    HOP_LE.put(
        Lenh.REQUEST_REVISION,
        Map.of(EvidenceStatus.DANG_NGHIEM_THU, EvidenceStatus.YEU_CAU_BO_SUNG));
    HOP_LE.put(Lenh.REJECT, Map.of(EvidenceStatus.DANG_NGHIEM_THU, EvidenceStatus.TU_CHOI));
  }

  static Stream<Arguments> moiCap() {
    return Stream.of(Lenh.values())
        .flatMap(l -> Stream.of(EvidenceStatus.values()).map(s -> Arguments.of(s, l)));
  }

  @ParameterizedTest(name = "{0} + {1}")
  @MethodSource("moiCap")
  void bangChuyenDayDu(EvidenceStatus from, Lenh lenh) {
    EvidenceVersion v = version(from);
    EvidenceStatus to = HOP_LE.get(lenh).get(from);
    if (to != null) {
      EvidenceVersion after = lenh.op.apply(v);
      assertThat(after.status()).isEqualTo(to);
      assertThat(after.id()).isEqualTo(v.id());
      assertThat(v.status()).as("bất biến").isEqualTo(from);
    } else {
      String expected =
          lenh == Lenh.SAVE_DRAFT
              ? EvidenceRuleViolation.VERSION_FROZEN
              : EvidenceRuleViolation.INVALID_STATE;
      assertThatThrownBy(() -> lenh.op.apply(v))
          .isInstanceOfSatisfying(
              EvidenceRuleViolation.class, e -> assertThat(e.code()).isEqualTo(expected));
    }
  }

  @Test
  void trangThaiCuoiKhongRoiDuoc() {
    for (EvidenceStatus s : EvidenceStatus.values()) {
      if (!s.isTerminal()) {
        continue;
      }
      for (Lenh l : Lenh.values()) {
        assertThatThrownBy(() -> l.op.apply(version(s))).isInstanceOf(EvidenceRuleViolation.class);
      }
    }
  }

  @Test
  void nopCanTepBamQuetSachVaXacNhanAi() {
    EvidenceVersion nhap = version(EvidenceStatus.NHAP);
    EvidenceVersion khongTep =
        nhap.saveDraft(new EvidenceVersion.DraftContent(null, null, null, false, null));
    assertThatThrownBy(() -> khongTep.submit(SUBMITTER, T0))
        .hasFieldOrPropertyWithValue("code", EvidenceRuleViolation.NO_OBJECT);

    EvidenceVersion chuaQuet =
        nhap.saveDraft(
            new EvidenceVersion.DraftContent(
                nhap.objectId(), nhap.contentSha256(), "CHO_QUET", false, null));
    assertThatThrownBy(() -> chuaQuet.submit(SUBMITTER, T0))
        .hasFieldOrPropertyWithValue("code", EvidenceRuleViolation.NOT_CLEAN);

    EvidenceVersion ai =
        nhap.saveDraft(
            new EvidenceVersion.DraftContent(
                nhap.objectId(), nhap.contentSha256(), null, true, null));
    assertThatThrownBy(() -> ai.submit(SUBMITTER, T0))
        .hasFieldOrPropertyWithValue("code", EvidenceRuleViolation.AI_NOT_CONFIRMED);
    EvidenceVersion daNop = ai.confirmAiFields(T0).submit(SUBMITTER, T0);
    assertThat(daNop.status()).isEqualTo(EvidenceStatus.DA_NOP);
    assertThat(daNop.submittedBy()).isEqualTo(SUBMITTER);
    assertThat(daNop.submittedAt()).isEqualTo(T0);
  }

  @Test
  void yeuCauBoSungVaTuChoiBatBuocNhanXet() {
    EvidenceVersion v = version(EvidenceStatus.DANG_NGHIEM_THU);
    assertThatThrownBy(() -> v.requestRevision(" "))
        .hasFieldOrPropertyWithValue("code", EvidenceRuleViolation.COMMENT_REQUIRED);
    assertThatThrownBy(() -> v.reject(null))
        .hasFieldOrPropertyWithValue("code", EvidenceRuleViolation.COMMENT_REQUIRED);
  }

  @Test
  void chiPhienBanDaXacNhanVaKhongBiLoaiMoiTinhDiem() {
    assertThat(version(EvidenceStatus.DA_XAC_NHAN).countsForScoring()).isTrue();
    assertThat(version(EvidenceStatus.DA_NOP).countsForScoring()).isFalse();
    EvidenceVersion v = version(EvidenceStatus.DA_XAC_NHAN);
    EvidenceVersion loai =
        new EvidenceVersion(
            v.id(),
            v.evidenceId(),
            v.deliverableId(),
            v.taskId(),
            1,
            v.status(),
            v.objectId(),
            v.contentSha256(),
            v.scanStatus(),
            false,
            null,
            v.submittedBy(),
            v.submittedAt(),
            v.acceptedAt(),
            null,
            UUID.randomUUID(),
            0);
    assertThat(loai.countsForScoring()).isFalse();
  }
}
