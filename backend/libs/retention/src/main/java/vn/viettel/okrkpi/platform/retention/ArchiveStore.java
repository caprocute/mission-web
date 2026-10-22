package vn.viettel.okrkpi.platform.retention;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRetentionResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Bucket lưu trữ có Object Lock chế độ COMPLIANCE (TL-03 "Cơ chế lưu trữ", Ceph RGW): ghi tệp kèm
 * hạn giữ, đọc lại kiểm băm, gia hạn khi có giữ pháp lý. Hạn COMPLIANCE chỉ kéo dài được, không rút
 * ngắn được, kể cả bởi quản trị.
 */
public final class ArchiveStore {

  private final S3Client s3;
  private final String bucket;

  public ArchiveStore(S3Client s3, String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  public String bucket() {
    return bucket;
  }

  /** Ghi tệp với hạn giữ, đọc lại và so băm; trả băm SHA-256 dạng hex. */
  String putLocked(String key, Path file, LocalDate retainUntil, boolean legalHold)
      throws IOException {
    String sha = sha256(file);
    s3.putObject(
        b ->
            b.bucket(bucket)
                .key(key)
                .objectLockMode(ObjectLockMode.COMPLIANCE)
                .objectLockRetainUntilDate(instant(retainUntil))
                .objectLockLegalHoldStatus(
                    legalHold ? ObjectLockLegalHoldStatus.ON : ObjectLockLegalHoldStatus.OFF),
        RequestBody.fromFile(file));
    // Đặt lại tường minh hạn giữ và giữ pháp lý: kho tương thích S3 không đọc tiêu đề Object Lock
    // của PutObject vẫn nhận đúng cấu hình (đặt cùng hạn COMPLIANCE là hợp lệ).
    s3.putObjectRetention(
        b ->
            b.bucket(bucket)
                .key(key)
                .retention(
                    r ->
                        r.mode(ObjectLockRetentionMode.COMPLIANCE)
                            .retainUntilDate(instant(retainUntil))));
    s3.putObjectLegalHold(
        b ->
            b.bucket(bucket)
                .key(key)
                .legalHold(
                    h ->
                        h.status(
                            legalHold
                                ? ObjectLockLegalHoldStatus.ON
                                : ObjectLockLegalHoldStatus.OFF)));
    Path back = Files.createTempFile(file.getParent(), "doc-lai-", ".bin");
    try {
      download(key, back);
      String got = sha256(back);
      if (!got.equals(sha)) {
        throw new IOException("Băm tệp đọc lại khác tệp đã ghi: " + key);
      }
    } finally {
      Files.deleteIfExists(back);
    }
    return sha;
  }

  void download(String key, Path to) throws IOException {
    try (InputStream in = s3.getObject(b -> b.bucket(bucket).key(key))) {
      Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  byte[] read(String key) {
    return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
  }

  /**
   * Gia hạn Object Lock của một phiên bản đối tượng tới {@code until} nếu hạn hiện tại sớm hơn; trả
   * {@code true} khi đã gia hạn.
   */
  boolean extendRetention(String objBucket, String key, String versionId, LocalDate until) {
    Instant target = instant(until);
    Instant current = null;
    try {
      GetObjectRetentionResponse r =
          s3.getObjectRetention(b -> b.bucket(objBucket).key(key).versionId(versionId));
      current = r.retention() == null ? null : r.retention().retainUntilDate();
    } catch (S3Exception e) {
      if (e.statusCode() != 404) {
        throw e;
      }
    }
    if (current != null && !current.isBefore(target)) {
      return false;
    }
    s3.putObjectRetention(
        b ->
            b.bucket(objBucket)
                .key(key)
                .versionId(versionId)
                .retention(
                    r -> r.mode(ObjectLockRetentionMode.COMPLIANCE).retainUntilDate(target)));
    return true;
  }

  static Instant instant(LocalDate d) {
    return d.atStartOfDay().toInstant(ZoneOffset.UTC);
  }

  static String sha256(Path file) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file)) {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
          md.update(buf, 0, n);
        }
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
