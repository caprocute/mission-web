package vn.viettel.okrkpi.scoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ràng buộc của lô, kết quả, bước vết tính và tài liệu chuẩn hoá (DV-07-01). */
class ScoringDomainTest {

  static final UUID ID = UUID.fromString("0192f1a2-0000-7000-8000-000000000001");
  static final CanonicalDoc EMPTY = CanonicalDoc.of(Map.of());

  @Test
  void taiLieuChuanHoaCungNoiDungCungBam() {
    CanonicalDoc a = CanonicalDoc.parse("{\"b\":1,\"a\":\"Hòa\"}");
    CanonicalDoc b = CanonicalDoc.of(Map.of("a", "Hòa", "b", 1));
    assertThat(a.json()).isEqualTo("{\"a\":\"Hòa\",\"b\":1}");
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    assertThat(a).isNotEqualTo(EMPTY).isNotEqualTo("x");
    assertThat(a.hash()).hasSize(32).isEqualTo(b.hash());
    byte[] copy = a.hash();
    copy[0] ^= 1;
    assertThat(a.hash()).isNotEqualTo(copy);
    assertThat(a.hashHex()).hasSize(64);
    assertThat(a.toString()).contains(a.hashHex());
  }

  @Test
  void buocVetTinhKiemDoDaiVaThuTu() {
    assertThat(new TraceStep(1, "BR-09-01", null, EMPTY, EMPTY, null).stepNo()).isEqualTo(1);
    assertThatThrownBy(() -> new TraceStep(0, "R", null, EMPTY, EMPTY, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TraceStep(1, " ", null, EMPTY, EMPTY, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TraceStep(1, "R".repeat(65), null, EMPTY, EMPTY, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TraceStep(1, "R", "x".repeat(201), EMPTY, EMPTY, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TraceStep(1, "R", null, EMPTY, EMPTY, "x".repeat(1001)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ketQuaKiemLuongDiemVaVetTinh() {
    assertThat(ok("100.000").totalScore()).isEqualByComparingTo("100");
    assertThat(score("NHA_NUOC", null, null, ScoreStatus.LOI, List.of()).status())
        .isEqualTo(ScoreStatus.LOI);
    assertThatThrownBy(() -> score("KHAC", "1", "HTNV", ScoreStatus.OK, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ok("100.001")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ok("-1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ok("1.0001")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> score("DANG", null, "HTNV", ScoreStatus.OK, List.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> score("DANG", "1", null, ScoreStatus.OK, List.of()))
        .isInstanceOf(NullPointerException.class);
    TraceStep two = new TraceStep(2, "R", null, EMPTY, EMPTY, null);
    assertThatThrownBy(() -> score("DANG", "1", "HTNV", ScoreStatus.OK, List.of(two)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void loKiemCoTrungMaVaKyHopLe() {
    ScoreBatch b = batch(20271, "v1", new byte[32], List.of(ok("1"), failed()));
    assertThat(b.count(ScoreStatus.OK)).isEqualTo(1);
    assertThat(b.count(ScoreStatus.LOI)).isEqualTo(1);
    byte[] hash = b.policyContentHash();
    hash[0] = 9;
    assertThat(b.policyContentHash()[0]).isZero();
    assertThat(batch(20270, "v1", new byte[32], List.of(ok("1"))).periodBucket()).isEqualTo(20270);

    assertThatThrownBy(() -> batch(20271, "v1", new byte[31], List.of(ok("1"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> batch(20271, "v1", null, List.of(ok("1"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> batch(20271, " ", new byte[32], List.of(ok("1"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> batch(20271, null, new byte[32], List.of(ok("1"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> batch(20271, "v".repeat(33), new byte[32], List.of(ok("1"))))
        .isInstanceOf(IllegalArgumentException.class);
    for (int bucket : new int[] {19991, 30001, 20275}) {
      assertThatThrownBy(() -> batch(bucket, "v1", new byte[32], List.of(ok("1"))))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> batch(20271, "v1", new byte[32], List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    List<ComputedScore> tooMany =
        new ArrayList<>(Collections.nCopies(ScoreBatch.MAX_SIZE + 1, ok("1")));
    assertThatThrownBy(() -> batch(20271, "v1", new byte[32], tooMany))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> batch(20271, "v1", new byte[32], List.of(ok("1"), ok("2"))))
        .as("trùng mã kết quả")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tacVuKhongNhanTongAm() {
    ScoreJob job = new ScoreJob(ID, ID, ID, 20271, ScoreJobKind.TAM_TINH, EMPTY, ID, null, 0, ID);
    assertThat(job.totalItems()).isZero();
    assertThatThrownBy(
            () -> new ScoreJob(ID, ID, ID, 20271, ScoreJobKind.TAM_TINH, EMPTY, ID, null, -1, ID))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ScoreJobStatus.valueOf("XONG")).isEqualTo(ScoreJobStatus.XONG);
  }

  static ComputedScore ok(String total) {
    return score("DANG", total, "HTNV", ScoreStatus.OK, List.of());
  }

  static ComputedScore failed() {
    return new ComputedScore(
        UUID.randomUUID(), ID, "DANG", ID, EMPTY, EMPTY, null, null, ScoreStatus.LOI, List.of());
  }

  static ComputedScore score(
      String flow, String total, String rating, ScoreStatus status, List<TraceStep> trace) {
    return new ComputedScore(
        ID,
        ID,
        flow,
        ID,
        EMPTY,
        EMPTY,
        total == null ? null : new BigDecimal(total),
        rating,
        status,
        trace);
  }

  static ScoreBatch batch(int bucket, String engine, byte[] hash, List<ComputedScore> items) {
    return new ScoreBatch(
        ID,
        ID,
        ScoreJobKind.CHOT_KY,
        ID,
        bucket,
        ID,
        hash,
        engine,
        ID,
        Instant.parse("2027-04-01T00:00:00Z"),
        items);
  }
}
