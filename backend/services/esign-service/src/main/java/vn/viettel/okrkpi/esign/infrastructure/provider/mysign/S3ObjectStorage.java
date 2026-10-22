package vn.viettel.okrkpi.esign.infrastructure.provider.mysign;

import java.util.Optional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;

/** {@link ObjectStorage} trên S3 (Ceph RGW ở dev/prod, giả lập S3 trong kiểm thử). */
public class S3ObjectStorage implements ObjectStorage {

  private final S3Client s3;
  private final String bucket;

  public S3ObjectStorage(S3Client s3, String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  @Override
  public Optional<byte[]> get(String key) {
    try {
      return Optional.of(s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray());
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (SdkException e) {
      throw new SignatureProviderException("Không đọc được kho tệp: " + e.getMessage(), false, e);
    }
  }

  @Override
  public void put(String key, byte[] content, String contentType) {
    try {
      s3.putObject(
          b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
    } catch (SdkException e) {
      throw new SignatureProviderException("Không ghi được kho tệp: " + e.getMessage(), false, e);
    }
  }
}
