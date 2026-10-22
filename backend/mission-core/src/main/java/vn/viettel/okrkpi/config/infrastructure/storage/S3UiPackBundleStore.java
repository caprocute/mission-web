package vn.viettel.okrkpi.config.infrastructure.storage;

import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import vn.viettel.okrkpi.config.application.port.UiPackBundleStore;

/** Kho gốc CDN của UI Pack trên giao diện S3 của Ceph RGW (CORE-08-11). */
public class S3UiPackBundleStore implements UiPackBundleStore, AutoCloseable {

  private final S3Client s3;
  private final String bucket;

  public S3UiPackBundleStore(S3Client s3, String bucket) {
    this.s3 = Objects.requireNonNull(s3, "s3");
    this.bucket = Objects.requireNonNull(bucket, "bucket");
  }

  @Override
  public void put(String key, byte[] content, String contentType, String cacheControl) {
    s3.putObject(
        b -> b.bucket(bucket).key(key).contentType(contentType).cacheControl(cacheControl),
        RequestBody.fromBytes(content));
  }

  @Override
  public void close() {
    s3.close();
  }

  @Override
  public Optional<byte[]> get(String key) {
    try {
      return Optional.of(s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray());
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    }
  }
}
