package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.util.Map;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.ObjectStore;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.TransientFailure;

/**
 * Kho đối tượng S3 (Ceph RGW ở dev/prod, giả lập S3 trong kiểm thử). Tên bucket logic ({@code
 * templates}, {@code dossier}) ánh xạ sang tên bucket thật theo cấu hình.
 */
public class S3ObjectStore implements ObjectStore {

  private final S3Client s3;
  private final Map<String, String> buckets;

  public S3ObjectStore(S3Client s3, Map<String, String> buckets) {
    this.s3 = s3;
    this.buckets = Map.copyOf(buckets);
  }

  @Override
  public byte[] get(String bucket, String key) {
    try {
      return s3.getObjectAsBytes(b -> b.bucket(bucket(bucket)).key(key)).asByteArray();
    } catch (NoSuchKeyException e) {
      throw new RejectedDocument("Không có tệp " + key + " trong kho " + bucket);
    } catch (SdkException e) {
      throw new TransientFailure("Không đọc được kho tệp: " + e.getMessage(), e);
    }
  }

  @Override
  public void put(String bucket, String key, byte[] content, String contentType) {
    try {
      s3.putObject(
          b -> b.bucket(bucket(bucket)).key(key).contentType(contentType),
          RequestBody.fromBytes(content));
    } catch (SdkException e) {
      throw new TransientFailure("Không ghi được kho tệp: " + e.getMessage(), e);
    }
  }

  private String bucket(String logical) {
    return buckets.getOrDefault(logical, logical);
  }
}
