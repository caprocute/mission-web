package vn.viettel.okrkpi.analytics.infrastructure.export;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import vn.viettel.okrkpi.analytics.application.ExportPorts;

/**
 * Tệp xuất trong kho đối tượng của cell (Ceph RGW, giao diện S3): khoá {@code <tenant>/<tác
 * vụ>/<tên tệp>} trong bucket xuất của SVC-15, URL tải ký ngắn hạn. Bucket thiếu thì tạo khi ghi
 * lần đầu; vòng đời xoá tệp cũ đặt ở hạ tầng (suy luận).
 */
final class S3ExportStore implements ExportPorts.Store {

  private static final Logger LOG = LoggerFactory.getLogger(S3ExportStore.class);

  private final S3Client s3;
  private final S3Presigner presigner;
  private final String bucket;
  private final Duration urlTtl;

  S3ExportStore(S3Client s3, S3Presigner presigner, String bucket, Duration urlTtl) {
    this.s3 = s3;
    this.presigner = presigner;
    this.bucket = bucket;
    this.urlTtl = urlTtl;
  }

  @Override
  public String save(UUID tenant, String objectName, byte[] content, String contentType) {
    String key = tenant + "/" + objectName;
    try {
      put(key, content, contentType);
    } catch (NoSuchBucketException e) {
      LOG.info("Tạo bucket xuất {}", bucket);
      s3.createBucket(b -> b.bucket(bucket));
      put(key, content, contentType);
    }
    return key;
  }

  private void put(String key, byte[] content, String contentType) {
    s3.putObject(
        b ->
            b.bucket(bucket).key(key).contentType(contentType).contentLength((long) content.length),
        RequestBody.fromBytes(content));
  }

  @Override
  public String downloadUrl(String objectKey) {
    String name = objectKey.substring(objectKey.lastIndexOf('/') + 1);
    return presigner
        .presignGetObject(
            p ->
                p.signatureDuration(urlTtl)
                    .getObjectRequest(
                        g ->
                            g.bucket(bucket)
                                .key(objectKey)
                                .responseContentDisposition(
                                    "attachment; filename=\"" + name + "\"")))
        .url()
        .toString();
  }
}
