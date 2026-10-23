package vn.viettel.okrkpi.scoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicyDomainTest {

  static final UUID ID = UUID.fromString("0192f1a2-0000-7000-8000-0000000000aa");

  @Test
  void khoaPhanGiaiChiNhanHaiLuong() {
    PolicyBindingKey k = PolicyBindingKey.of(ID, ID, "DANG");
    assertThat(k.subjectGroupId()).isNull();
    assertThat(new PolicyBindingKey(ID, ID, "NHA_NUOC", ID, ID).unitId()).isEqualTo(ID);
    assertThatThrownBy(() -> PolicyBindingKey.of(ID, ID, "KHAC"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PolicyBindingKey.of(ID, ID, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PolicyBindingKey.of(null, ID, "DANG"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void bamNoiDungChuanHoaVeHexThuong() {
    PinnedPolicy p = new PinnedPolicy(ID, "goi", ID, "1.0.0", " " + "AB".repeat(32) + " ", false);
    assertThat(p.contentHash()).isEqualTo("ab".repeat(32));
    assertThat(p.contentHashBytes()).hasSize(32);
    assertThatThrownBy(() -> new PinnedPolicy(ID, "goi", ID, "1.0.0", "abc", false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PinnedPolicy.normalize(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void noiDungPhienBanMacDinh() {
    PinnedPolicy p = new PinnedPolicy(ID, null, ID, "1.0.0", "cd".repeat(32), true);
    PolicyRuleSource r = new PolicyRuleSource("a", "1.0", null, 1, null, null, null);
    assertThat(r.resultType()).isEqualTo("DECIMAL");
    assertThat(r.roundingMode()).isEqualTo("NUA_LEN");
    PolicyContent c =
        new PolicyContent(p, null, List.of(r), List.of(new DecisionTableSource("k", "<x/>")));
    assertThat(c.manifest()).isEmpty();
    assertThat(c.tables()).hasSize(1);
    assertThat(new OpenPeriod(ID, 20271, "DANG").periodBucket()).isEqualTo(20271);
    assertThatThrownBy(() -> new OpenPeriod(ID, 1, null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new DecisionTableSource("k", null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PolicyContent(null, null, List.of(), List.of()))
        .isInstanceOf(NullPointerException.class);
  }
}
