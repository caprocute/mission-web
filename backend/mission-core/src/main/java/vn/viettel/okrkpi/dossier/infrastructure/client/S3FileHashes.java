package vn.viettel.okrkpi.dossier.infrastructure.client;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.FileHashes;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tính lại SHA-256 của tệp PDF/A trên bucket hồ sơ số (Ceph RGW) bằng cách đọc luồng tệp, để đối
 * chiếu trước khi ký gói (CORE-06-07). Không đọc được tệp thì trả rỗng và ghi nhật ký.
 */
public class S3FileHashes implements FileHashes {

  private static final Logger LOG = LoggerFactory.getLogger(S3FileHashes.class);

  private final S3Client s3;
  private final String bucket;

  public S3FileHashes(S3Client s3, String bucket) {
    this.s3 = Objects.requireNonNull(s3, "s3");
    this.bucket = Objects.requireNonNull(bucket, "bucket");
  }

  @Override
  public Optional<byte[]> sha256(TenantId tenant, String objectKey) {
    if (objectKey == null || objectKey.isBlank()) {
      return Optional.empty();
    }
    try (InputStream in = s3.getObject(b -> b.bucket(bucket).key(objectKey))) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[64 * 1024];
      for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
        md.update(buffer, 0, n);
      }
      return Optional.of(md.digest());
    } catch (SdkException | IOException e) {
      LOG.warn("Không đọc được tệp {} để đối chiếu băm: {}", objectKey, e.getMessage());
      return Optional.empty();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
