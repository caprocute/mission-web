package vn.viettel.okrkpi.policy.domain;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Số phiên bản ba phần của Policy Pack ({@code ck_policy_pack_version__semver}, PC-03). */
public record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {

  public static final Pattern PATTERN = Pattern.compile("^([0-9]+)\\.([0-9]+)\\.([0-9]+)$");

  private static final Comparator<SemVer> ORDER =
      Comparator.comparingInt(SemVer::major)
          .thenComparingInt(SemVer::minor)
          .thenComparingInt(SemVer::patch);

  public SemVer {
    if (major < 0 || minor < 0 || patch < 0) {
      throw new IllegalArgumentException("Phiên bản âm");
    }
  }

  /** Đọc chuỗi dạng {@code 2.1.0}; sai dạng (hoặc tràn số) trả rỗng. */
  public static Optional<SemVer> parse(String text) {
    if (text == null) {
      return Optional.empty();
    }
    Matcher m = PATTERN.matcher(text);
    if (!m.matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new SemVer(
              Integer.parseInt(m.group(1)),
              Integer.parseInt(m.group(2)),
              Integer.parseInt(m.group(3))));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  public SemVer nextMinor() {
    return new SemVer(major, minor + 1, 0);
  }

  @Override
  public int compareTo(SemVer other) {
    return ORDER.compare(this, Objects.requireNonNull(other, "other"));
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}
