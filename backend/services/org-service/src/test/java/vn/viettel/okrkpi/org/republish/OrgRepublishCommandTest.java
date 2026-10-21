package vn.viettel.okrkpi.org.republish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import vn.viettel.okrkpi.org.application.republish.EntityKind;
import vn.viettel.okrkpi.org.application.republish.RepublishRequest;
import vn.viettel.okrkpi.org.infrastructure.republish.OrgRepublishCommandArgs;

class OrgRepublishCommandTest {

  @Test
  void docThamSoDayDu() {
    UUID t = UUID.randomUUID();
    RepublishRequest r = OrgRepublishCommandArgs.parse(new DefaultApplicationArguments("--tenant=" + t,
        "--entity=unit,person", "--bucket=3", "--bucket=200", "--as-of=2026-10-21T00:00:00+07:00", "--dry-run",
        "--operator=ops"));
    assertThat(r.tenant()).isEqualTo(t);
    assertThat(r.entities()).containsExactly(EntityKind.ORG_UNIT, EntityKind.PERSON);
    assertThat(r.buckets()).containsExactly(3, 200);
    assertThat(r.asOf()).isEqualTo(Instant.parse("2026-10-20T17:00:00Z"));
    assertThat(r.dryRun()).isTrue();
    assertThat(r.allBuckets()).isFalse();
  }

  @Test
  void macDinhLaToanBo() {
    RepublishRequest r = OrgRepublishCommandArgs.parse(new DefaultApplicationArguments("--tenant=" + UUID.randomUUID()));
    assertThat(r.entities()).containsExactly(EntityKind.ORG_UNIT, EntityKind.PERSON, EntityKind.ASSIGNMENT);
    assertThat(r.allBuckets()).isTrue();
    assertThat(r.asOf()).isNull();
    assertThat(r.operator()).isEqualTo("system");
  }

  @Test
  void tuChoiThamSoSai() {
    assertThatThrownBy(() -> OrgRepublishCommandArgs.parse(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrgRepublishCommandArgs.parse(new DefaultApplicationArguments("--tenant=x")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrgRepublishCommandArgs.parse(
        new DefaultApplicationArguments("--tenant=" + UUID.randomUUID(), "--bucket=256")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrgRepublishCommandArgs.parse(
        new DefaultApplicationArguments("--tenant=" + UUID.randomUUID(), "--entity=khac")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrgRepublishCommandArgs.parse(
        new DefaultApplicationArguments("--tenant=" + UUID.randomUUID(), "--as-of=hom-qua")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
