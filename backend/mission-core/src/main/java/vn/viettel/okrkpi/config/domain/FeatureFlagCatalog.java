package vn.viettel.okrkpi.config.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Danh mục cờ tính năng nền tảng (CORE-08-07): bản sao trên classpath của {@code
 * data/seed/feature-flags/feature-flags.csv}, cột {@code flag_key,description,enabled,percent}.
 */
public final class FeatureFlagCatalog {

  static final String FILE = "feature_flags.csv";

  private static final Map<String, FeatureFlag> DEFAULTS = load();

  private FeatureFlagCatalog() {}

  /** Mọi cờ nền tảng với giá trị mặc định, theo thứ tự mã cờ. */
  public static List<FeatureFlag> all() {
    return DEFAULTS.values().stream().sorted(Comparator.comparing(FeatureFlag::key)).toList();
  }

  public static Optional<FeatureFlag> defaultOf(String key) {
    return Optional.ofNullable(DEFAULTS.get(key));
  }

  private static Map<String, FeatureFlag> load() {
    Map<String, FeatureFlag> flags = new LinkedHashMap<>();
    try (InputStream in = FeatureFlagCatalog.class.getResourceAsStream(FILE)) {
      if (in == null) {
        throw new IllegalStateException("Thiếu tệp danh mục cờ tính năng " + FILE);
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      reader.readLine();
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        if (line.isBlank()) {
          continue;
        }
        List<String> cols = NationalHolidays.columns(line);
        String key = cols.get(0);
        // Mã cờ theo ví dụ của hợp đồng (ai.weeklySummary) có chữ hoa, dù mẫu FlagKeyPath chỉ cho
        // chữ thường (wiki trang 14).
        if (key.length() > FeatureFlag.KEY_MAX || !key.matches("^[a-z0-9][a-zA-Z0-9._-]*$")) {
          throw new IllegalStateException("Mã cờ sai dạng: " + key);
        }
        flags.put(
            key,
            new FeatureFlag(
                null,
                key,
                cols.get(1),
                Boolean.parseBoolean(cols.get(2)),
                new FeatureFlagRollout(null, Integer.parseInt(cols.get(3))),
                null,
                null,
                0));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Map.copyOf(flags);
  }
}
