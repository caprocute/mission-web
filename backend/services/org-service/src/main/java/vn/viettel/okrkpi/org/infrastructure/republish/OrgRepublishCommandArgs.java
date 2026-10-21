package vn.viettel.okrkpi.org.infrastructure.republish;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import vn.viettel.okrkpi.org.application.republish.EntityKind;
import vn.viettel.okrkpi.org.application.republish.RepublishRequest;

/** Đọc tham số dòng lệnh của {@code org-republish}; tham số sai ném {@link IllegalArgumentException}. */
public final class OrgRepublishCommandArgs {

  private OrgRepublishCommandArgs() {
  }

  public static RepublishRequest parse(ApplicationArguments args) {
    String tenant = single(args, "tenant");
    if (tenant == null) {
      throw new IllegalArgumentException("thiếu --tenant");
    }
    Set<EntityKind> entities = EnumSet.noneOf(EntityKind.class);
    for (String v : values(args, "entity")) {
      entities.add(EntityKind.fromCli(v));
    }
    Set<Integer> buckets = new TreeSet<>();
    for (String v : values(args, "bucket")) {
      try {
        buckets.add(Integer.parseInt(v));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("--bucket phải là số 0..255");
      }
    }
    String asOf = single(args, "as-of");
    Instant at = null;
    if (asOf != null) {
      try {
        at = OffsetDateTime.parse(asOf).toInstant();
      } catch (java.time.format.DateTimeParseException e) {
        throw new IllegalArgumentException("--as-of phải theo ISO-8601 có múi giờ, ví dụ 2026-10-21T00:00:00+07:00");
      }
    }
    UUID tenantId;
    try {
      tenantId = UUID.fromString(tenant);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("--tenant phải là UUID");
    }
    return new RepublishRequest(tenantId, entities, buckets, at, args.containsOption("dry-run"),
        single(args, "operator"));
  }

  private static String single(ApplicationArguments args, String name) {
    List<String> v = args.getOptionValues(name);
    return v == null || v.isEmpty() ? null : v.getLast();
  }

  private static List<String> values(ApplicationArguments args, String name) {
    List<String> v = args.getOptionValues(name);
    if (v == null) {
      return List.of();
    }
    return v.stream().flatMap(s -> java.util.Arrays.stream(s.split(","))).map(String::trim)
        .filter(s -> !s.isEmpty()).toList();
  }
}
