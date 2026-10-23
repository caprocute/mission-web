package vn.viettel.okrkpi.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Vòng đời phiên bản hồ sơ tập khách hàng dùng máy trạng thái của Policy Pack (CORE-05-12). */
class CustomerProfileVersionTest {

  private final UUID author = UUID.randomUUID();
  private final UUID approver = UUID.randomUUID();
  private final Instant at = Instant.parse("2026-10-22T02:00:00Z");

  private CustomerProfileVersion draft() {
    return CustomerProfileVersion.draft(
        UUID.randomUUID(), UUID.randomUUID(), "0.1.0", "{}", "Phiên bản đầu", author, at);
  }

  @Test
  void kiemTraGuiDuyetKyDuyetTheoThuTu() {
    CustomerProfileVersion v = draft();
    assertThatThrownBy(() -> v.submit(author, at))
        .isInstanceOf(PolicyTransitionException.class)
        .hasFieldOrPropertyWithValue("code", "POLICY-409-NOT-TESTED");
    CustomerProfileVersion tested = v.validated("ab".repeat(32), author, at);
    assertThat(tested.status()).isEqualTo(PolicyVersionStatus.DAT_KIEM_THU);
    assertThat(tested.rowVersion()).isEqualTo(1);

    CustomerProfileVersion edited = tested.edit("{\"a\":1}", null, author, at);
    assertThat(edited.status()).isEqualTo(PolicyVersionStatus.NHAP);
    assertThat(edited.contentHash()).isNull();
    assertThat(edited.changeNote()).isEqualTo("Phiên bản đầu");

    CustomerProfileVersion pending = tested.submit(author, at);
    assertThat(pending.status()).isEqualTo(PolicyVersionStatus.CHO_PHE_DUYET);
    assertThatThrownBy(() -> pending.edit("{}", null, author, at))
        .hasFieldOrPropertyWithValue("code", "POLICY-409-VERSION-FROZEN");
    assertThatThrownBy(() -> pending.approve(author, at, at))
        .hasFieldOrPropertyWithValue("code", "POLICY-403-SAME-AUTHOR");

    CustomerProfileVersion published = pending.approve(approver, at, at);
    assertThat(published.status()).isEqualTo(PolicyVersionStatus.DA_PHAT_HANH);
    assertThat(published.signed()).isTrue();
    assertThat(published.publishedAt()).isEqualTo(at);
    assertThatThrownBy(() -> published.reject(approver, at))
        .hasFieldOrPropertyWithValue("code", "POLICY-409-NOT-PENDING");
  }

  @Test
  void tuChoiVeNhapGiuBamDoTriggerDongBang() {
    CustomerProfileVersion pending =
        draft().validated("cd".repeat(32), author, at).submit(author, at);
    CustomerProfileVersion rejected = pending.reject(approver, at);
    assertThat(rejected.status()).isEqualTo(PolicyVersionStatus.NHAP);
    assertThat(rejected.contentHash()).isEqualTo(pending.contentHash());
    assertThat(rejected.validated("ef".repeat(32), author, at).contentHash())
        .isEqualTo("ef".repeat(32));
  }
}
