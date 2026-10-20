package vn.viettel.okrkpi.shared.etag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;

class ETagsTest {

  @Test
  void etagLaRowVersionTrongNhayKep() {
    assertThat(ETags.of(7)).isEqualTo("\"7\"");
    assertThat(ETags.parse("\"7\"")).hasValue(7);
    assertThat(ETags.parse(" \"12\" ")).hasValue(12);
    assertThat(ETags.parse("W/\"7\"")).isEmpty();
    assertThat(ETags.parse("*")).isEmpty();
    assertThat(ETags.parse("\"abc\"")).isEmpty();
    assertThat(ETags.parse("\"\"")).isEmpty();
    assertThat(ETags.parse("7")).isEmpty();
    assertThat(ETags.parse(null)).isEmpty();
    assertThat(ETags.parse("\"1234567890123456789\"")).isEmpty();
    assertThatThrownBy(() -> ETags.of(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void thieuIfMatchLa428() {
    assertThatThrownBy(() -> ETags.requireMatch(null, 3, "TASK"))
        .isInstanceOf(PreconditionRequiredException.class);
    assertThatThrownBy(() -> ETags.requireMatch("  ", 3, "TASK"))
        .isInstanceOf(PreconditionRequiredException.class);
    assertThatThrownBy(() -> ETags.expectedVersion(null, "TASK"))
        .isInstanceOf(PreconditionRequiredException.class);
  }

  @Test
  void lechPhienBanLa409KemPhienBan() {
    assertThatThrownBy(() -> ETags.requireMatch("\"2\"", 3, "OKR"))
        .isInstanceOfSatisfying(
            VersionConflictException.class,
            e -> {
              assertThat(e.module()).isEqualTo("OKR");
              assertThat(e.expectedVersion()).isEqualTo(2);
              assertThat(e.currentVersion()).isEqualTo(3);
            });
    assertThatThrownBy(() -> ETags.requireMatch("*", 3, null))
        .isInstanceOfSatisfying(
            VersionConflictException.class, e -> assertThat(e.expectedVersion()).isNull());
    assertThatThrownBy(() -> ETags.expectedVersion("rác", "TASK"))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void khopMotTrongNhieuEtag() {
    assertThat(ETags.requireMatch("\"1\", \"3\"", 3, "TASK")).isEqualTo(3);
    assertThat(ETags.expectedVersion("W/\"1\", \"4\"", "TASK")).isEqualTo(4);
  }

  @Test
  void ifNoneMatchSoSanhYeu() {
    assertThat(ETags.notModified("\"5\"", 5)).isTrue();
    assertThat(ETags.notModified("W/\"5\"", 5)).isTrue();
    assertThat(ETags.notModified("*", 5)).isTrue();
    assertThat(ETags.notModified("\"4\", \"5\"", 5)).isTrue();
    assertThat(ETags.notModified("\"4\"", 5)).isFalse();
    assertThat(ETags.notModified(null, 5)).isFalse();
    assertThat(ETags.notModified("", 5)).isFalse();
  }

  @Test
  void phanHoiCoEtag() {
    ResponseEntity<String> ok = ETags.ok("x", 2);
    assertThat(ok.getStatusCode().value()).isEqualTo(200);
    assertThat(ok.getHeaders().getETag()).isEqualTo("\"2\"");
    ResponseEntity<String> nm = ETags.conditional("\"2\"", 2, () -> "không gọi");
    assertThat(nm.getStatusCode().value()).isEqualTo(304);
    assertThat(nm.getBody()).isNull();
    assertThat(nm.getHeaders().getETag()).isEqualTo("\"2\"");
    assertThat(ETags.conditional("\"1\"", 2, () -> "mới").getBody()).isEqualTo("mới");
    assertThat(ETags.headers(9).getETag()).isEqualTo("\"9\"");
  }
}
