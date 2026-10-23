package vn.viettel.okrkpi.integ.infrastructure.celltransfer;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import vn.viettel.okrkpi.integ.application.SourceUnavailableException;
import vn.viettel.okrkpi.integ.application.port.TransferObjects;

/**
 * Bucket {@code transfer} trên Ceph RGW (S3): bản mã gói chuyển, khoá giữ GOVERNANCE {@code
 * retention} (mặc định 1 năm, TL-03 từ điển {@code integ.transfer_package}); để {@code retention}
 * bằng 0 thì không đặt khoá giữ (bucket chưa bật Object Lock ở môi trường giả lập).
 */
public class S3TransferObjects implements TransferObjects {

  private final S3Client s3;
  private final String bucket;
  private final Duration retention;
  private final Clock clock;

  public S3TransferObjects(S3Client s3, String bucket, Duration retention, Clock clock) {
    this.s3 = Objects.requireNonNull(s3, "s3");
    this.bucket = Objects.requireNonNull(bucket, "bucket");
    this.retention = Objects.requireNonNull(retention, "retention");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void put(String key, byte[] content) {
    PutObjectRequest.Builder req =
        PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/octet-stream");
    if (!retention.isZero()) {
      req.objectLockMode(ObjectLockMode.GOVERNANCE)
          .objectLockRetainUntilDate(clock.instant().plus(retention));
    }
    try {
      s3.putObject(req.build(), RequestBody.fromBytes(content));
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  @Override
  public byte[] get(String key) {
    try {
      return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
          .asByteArray();
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  private static SourceUnavailableException unavailable(SdkException e) {
    return new SourceUnavailableException(
        SourceUnavailableException.Reason.ERROR, "Kho đối tượng gói chuyển lỗi", e);
  }
}
