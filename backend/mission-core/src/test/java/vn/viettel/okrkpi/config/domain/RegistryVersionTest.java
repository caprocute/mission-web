package vn.viettel.okrkpi.config.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Phiên bản và dải registry (CORE-08-09). */
class RegistryVersionTest {

  @Test
  void comparesNumericallyAndMatchesRanges() {
    assertThat(RegistryVersion.compare("2026.10", "2026.3")).isPositive();
    assertThat(RegistryVersion.compare("2.4", "2.4.0")).isZero();
    assertThat(RegistryVersion.satisfies("2026.3", "2026.3")).isTrue();
    assertThat(RegistryVersion.satisfies(">=2026.1 <2027", "2026.3")).isTrue();
    assertThat(RegistryVersion.satisfies(">=2026.1 <2027", "2027.0")).isFalse();
    assertThat(RegistryVersion.satisfies("^2.1", "2.4.0")).isTrue();
    assertThat(RegistryVersion.satisfies("^2.1", "3.0.0")).isFalse();
    assertThat(RegistryVersion.satisfies("~2.4", "2.4.7")).isTrue();
    assertThat(RegistryVersion.satisfies("~2.4", "2.5.0")).isFalse();
    assertThat(RegistryVersion.satisfies("<2 || >2026", "2026.3")).isTrue();
    assertThat(RegistryVersion.satisfies(">2026.3", "2026.3")).isFalse();
    assertThat(RegistryVersion.satisfies("<=2026.3 =2026.3", "2026.3")).isTrue();
    assertThat(RegistryVersion.satisfies(">=abc", "2026.3")).isFalse();
    assertThat(RegistryVersion.satisfies(" ", "2026.3")).isFalse();
    assertThat(RegistryVersion.satisfies("2026.3", "x")).isFalse();
  }

  @Test
  void componentKeysAndClassificationsAreChecked() {
    UiComponent c =
        new UiComponent(
            "vt.widget.okrProgress",
            "2026.3",
            "{}",
            "C1",
            UiComponent.Status.HIEU_LUC,
            false,
            null,
            true,
            null);
    assertThat(c.widget()).isTrue();
    assertThatThrownBy(
            () ->
                new UiComponent(
                    "acme.widget.x",
                    "2026.3",
                    "{}",
                    "C1",
                    UiComponent.Status.HIEU_LUC,
                    false,
                    null,
                    false,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new UiComponent(
                    "vt.widget.x",
                    "v2026",
                    "{}",
                    "C1",
                    UiComponent.Status.HIEU_LUC,
                    false,
                    null,
                    false,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new UiComponent(
                    "vt.widget.x",
                    "2026.3",
                    "{}",
                    "C9",
                    UiComponent.Status.HIEU_LUC,
                    false,
                    null,
                    false,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
