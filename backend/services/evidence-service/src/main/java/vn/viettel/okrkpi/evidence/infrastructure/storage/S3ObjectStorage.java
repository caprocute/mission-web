package vn.viettel.okrkpi.evidence.infrastructure.storage;

import java.time.Instant;
import java.util.Objects;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DefaultRetention;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockConfiguration;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRule;
import software.amazon.awssdk.services.s3.model.PutObjectLockConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import vn.viettel.okrkpi.evidence.application.ObjectStorage;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;

/**
 * {@link ObjectStorage} trên Ceph RGW qua S3 (AWS SDK for Java v2, đường dẫn kiểu path-style).
 * Object Lock chế độ compliance không cho ai, kể cả tài khoản root của RGW, xoá phiên bản trước hạn
 * (TL-03 "Tổ chức bucket", tiêu chí I5, E4).
 */
public class S3ObjectStorage implements ObjectStorage {

  private final S3Client s3;

  public S3ObjectStorage(S3Client s3) {
    this.s3 = Objects.requireNonNull(s3, "s3");
  }

  @Override
  public boolean ensureLockedBucket(String bucket, int lockDays) {
    boolean created;
    try {
      s3.createBucket(
          CreateBucketRequest.builder().bucket(bucket).objectLockEnabledForBucket(true).build());
      created = true;
    } catch (BucketAlreadyOwnedByYouException e) {
      created = false;
    }
    s3.putObjectLockConfiguration(
        PutObjectLockConfigurationRequest.builder()
            .bucket(bucket)
            .objectLockConfiguration(
                ObjectLockConfiguration.builder()
                    .objectLockEnabled(ObjectLockEnabled.ENABLED)
                    .rule(
                        ObjectLockRule.builder()
                            .defaultRetention(
                                DefaultRetention.builder()
                                    .mode(ObjectLockRetentionMode.COMPLIANCE)
                                    .days(lockDays)
                                    .build())
                            .build())
                    .build())
            .build());
    return created;
  }

  @Override
  public StoredObject put(
      ObjectLocation location, byte[] content, String mediaType, Instant lockUntil) {
    PutObjectResponse r =
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(location.bucket())
                .key(location.key())
                .contentType(mediaType)
                .objectLockMode(ObjectLockMode.COMPLIANCE)
                .objectLockRetainUntilDate(lockUntil)
                .build(),
            RequestBody.fromBytes(content));
    return new StoredObject(location, r.versionId(), lockUntil);
  }

  @Override
  public byte[] read(ObjectLocation location, String versionId) {
    return s3.getObjectAsBytes(
            GetObjectRequest.builder()
                .bucket(location.bucket())
                .key(location.key())
                .versionId(versionId)
                .build())
        .asByteArray();
  }
}
