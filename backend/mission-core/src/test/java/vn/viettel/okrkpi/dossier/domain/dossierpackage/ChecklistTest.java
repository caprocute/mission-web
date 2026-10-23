package vn.viettel.okrkpi.dossier.domain.dossierpackage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.Checklist.Item;

/** BR-11-09: chỉ mục chặn quyết định ký được hay không. */
class ChecklistTest {

  @Test
  void mucKhongChanChiCanhBao() {
    Checklist c =
        new Checklist(
            Instant.EPOCH,
            List.of(
                new Item(Checklist.SIGNED_PROFILES, "ok", true, 0, true),
                new Item(Checklist.UNIT_FORM_SIGNED, "Mẫu 3 chờ ký", false, 1, false)));
    assertThat(c.passed()).isTrue();
    assertThat(c.failedCount()).isZero();
  }

  @Test
  void mucChanChuaDatThiKhongKyDuoc() {
    Checklist c =
        new Checklist(
            Instant.EPOCH,
            List.of(
                new Item(Checklist.SIGNED_PROFILES, "1 hồ sơ chưa ký", false, 1, true),
                new Item(Checklist.HASHES_MATCH, "lệch", false, 2, true),
                new Item(Checklist.UNIT_FORM_SIGNED, "Mẫu 3 chờ ký", false, 1, false)));
    assertThat(c.passed()).isFalse();
    assertThat(c.failedCount()).isEqualTo(2);
  }
}
