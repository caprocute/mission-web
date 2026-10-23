package vn.viettel.okrkpi.scoring.domain;

import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Phiên bản Policy Pack ghim cho kỳ ({@code ResolvedPolicyPack} của {@code resolvePolicyPack}): mã
 * gói, phiên bản và băm nội dung SHA-256 dạng hex chữ thường. Băm là khoá của bộ đệm biên dịch và
 * giá trị ghi vào {@code score_result.policy_content_hash} (BR-08-07).
 */
public record PinnedPolicy(
    UUID packId,
    String packCode,
    UUID versionId,
    String version,
    String contentHash,
    boolean frozen) {

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  public PinnedPolicy {
    Objects.requireNonNull(packId, "packId");
    Objects.requireNonNull(versionId, "versionId");
    Objects.requireNonNull(version, "version");
    contentHash = normalize(contentHash);
  }

  /** Băm hex chữ thường 64 ký tự; sai dạng ném {@link IllegalArgumentException}. */
  public static String normalize(String contentHash) {
    String hash = contentHash == null ? "" : contentHash.strip().toLowerCase(java.util.Locale.ROOT);
    if (!SHA256_HEX.matcher(hash).matches()) {
      throw new IllegalArgumentException("Băm nội dung Policy Pack phải là SHA-256 dạng hex");
    }
    return hash;
  }

  /** 32 byte của băm, đúng kiểu cột {@code policy_content_hash bytea}. */
  public byte[] contentHashBytes() {
    return HexFormat.of().parseHex(contentHash);
  }
}
