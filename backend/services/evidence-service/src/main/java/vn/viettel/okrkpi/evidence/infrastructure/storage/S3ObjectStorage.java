package vn.viettel.okrkpi.evidence.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortIncompleteMultipartUpload;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DefaultRetention;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.ObjectLockConfiguration;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRule;
import software.amazon.awssdk.services.s3.model.PutObjectLockConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import vn.viettel.okrkpi.evidence.application.ObjectStorage;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;

/**
 * {@link ObjectStorage} trên Ceph RGW qua S3 (AWS SDK for Java v2, đường dẫn kiểu path-style).
 * Object Lock chế độ compliance không cho ai, kể cả tài khoản root của RGW, xoá phiên bản trước hạn
 * (TL-03 "Tổ chức bucket", tiêu chí I5, E4).
 */
public class S3ObjectStorage implements ObjectStorage {

  private final S3Client s3;
  private final S3Presigner presigner;
  private final Set<String> ready = ConcurrentHashMap.newKeySet();

  public S3ObjectStorage(S3Client s3, S3Presigner presigner) {
    this.s3 = Objects.requireNonNull(s3, "s3");
    this.presigner = presigner;
  }

  @Override
  public boolean ensureLockedBucket(String bucket, int lockDays) {
    if (ready.contains(bucket)) {
      return false;
    }
    boolean created = createBucket(bucket, true);
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
    ready.add(bucket);
    return created;
  }

  @Override
  public void ensureStagingBucket(String bucket, int days) {
    if (ready.contains(bucket)) {
      return;
    }
    createBucket(bucket, false);
    s3.putBucketLifecycleConfiguration(
        b ->
            b.bucket(bucket)
                .lifecycleConfiguration(
                    BucketLifecycleConfiguration.builder()
                        .rules(
                            LifecycleRule.builder()
                                .id("don-tep-tam")
                                .status(ExpirationStatus.ENABLED)
                                .filter(LifecycleRuleFilter.builder().prefix("t/").build())
                                .expiration(LifecycleExpiration.builder().days(days).build())
                                .abortIncompleteMultipartUpload(
                                    AbortIncompleteMultipartUpload.builder()
                                        .daysAfterInitiation(days)
                                        .build())
                                .build())
                        .build()));
    ready.add(bucket);
  }

  private boolean createBucket(String bucket, boolean objectLock) {
    try {
      s3.createBucket(
          CreateBucketRequest.builder()
              .bucket(bucket)
              .objectLockEnabledForBucket(objectLock ? Boolean.TRUE : null)
              .build());
      return true;
    } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
      return false;
    }
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

  @Override
  public String createMultipart(ObjectLocation location, String mediaType) {
    return s3.createMultipartUpload(
            b -> b.bucket(location.bucket()).key(location.key()).contentType(mediaType))
        .uploadId();
  }

  @Override
  public String uploadPart(
      ObjectLocation location, String uploadId, int partNo, InputStream content, long length) {
    try {
      return s3.uploadPart(
              b ->
                  b.bucket(location.bucket())
                      .key(location.key())
                      .uploadId(uploadId)
                      .partNumber(partNo)
                      .contentLength(length),
              RequestBody.fromInputStream(content, length))
          .eTag();
    } catch (S3Exception e) {
      throw gone(e);
    } catch (SdkClientException e) {
      throw new PartInterruptedException("Luồng phần bị cắt giữa chừng", e);
    }
  }

  @Override
  public List<Part> listParts(ObjectLocation location, String uploadId) {
    try {
      return s3
          .listPartsPaginator(
              b -> b.bucket(location.bucket()).key(location.key()).uploadId(uploadId))
          .parts()
          .stream()
          .map(p -> new Part(p.partNumber(), p.size(), p.eTag()))
          .toList();
    } catch (S3Exception e) {
      throw gone(e);
    }
  }

  @Override
  public void completeMultipart(ObjectLocation location, String uploadId, List<Part> parts) {
    try {
      s3.completeMultipartUpload(
          b ->
              b.bucket(location.bucket())
                  .key(location.key())
                  .uploadId(uploadId)
                  .multipartUpload(
                      CompletedMultipartUpload.builder()
                          .parts(
                              parts.stream()
                                  .map(
                                      p ->
                                          CompletedPart.builder()
                                              .partNumber(p.partNo())
                                              .eTag(p.etag())
                                              .build())
                                  .toList())
                          .build()));
    } catch (S3Exception e) {
      throw gone(e);
    }
  }

  @Override
  public void abortMultipart(ObjectLocation location, String uploadId) {
    try {
      s3.abortMultipartUpload(
          b -> b.bucket(location.bucket()).key(location.key()).uploadId(uploadId));
    } catch (S3Exception e) {
      if (e.statusCode() != 404) {
        throw e;
      }
    }
  }

  @Override
  public <T> T stream(ObjectLocation location, String versionId, StreamReader<T> reader)
      throws IOException {
    try (ResponseInputStream<GetObjectResponse> in =
        s3.getObject(
            GetObjectRequest.builder()
                .bucket(location.bucket())
                .key(location.key())
                .versionId(versionId)
                .build())) {
      return reader.read(in);
    }
  }

  @Override
  public StoredObject copyLocked(
      ObjectLocation from, ObjectLocation to, String mediaType, Instant lockUntil) {
    CopyObjectResponse r =
        s3.copyObject(
            b ->
                b.sourceBucket(from.bucket())
                    .sourceKey(from.key())
                    .destinationBucket(to.bucket())
                    .destinationKey(to.key())
                    .contentType(mediaType)
                    .metadataDirective("REPLACE")
                    .objectLockMode(ObjectLockMode.COMPLIANCE)
                    .objectLockRetainUntilDate(lockUntil));
    return new StoredObject(to, r.versionId(), lockUntil);
  }

  @Override
  public void deleteStaging(ObjectLocation location) {
    s3.deleteObject(b -> b.bucket(location.bucket()).key(location.key()));
  }

  @Override
  public URI presignGet(
      ObjectLocation location,
      String versionId,
      Duration ttl,
      String mediaType,
      String contentDisposition) {
    try {
      return presigner
          .presignGetObject(
              p ->
                  p.signatureDuration(ttl)
                      .getObjectRequest(
                          g ->
                              g.bucket(location.bucket())
                                  .key(location.key())
                                  .versionId(versionId)
                                  .responseContentType(mediaType)
                                  .responseContentDisposition(contentDisposition)
                                  .responseCacheControl("no-store")))
          .url()
          .toURI();
    } catch (java.net.URISyntaxException e) {
      throw new IllegalStateException("URL ký trước không hợp lệ", e);
    }
  }

  /** Multipart không còn trên kho trả 404 {@code NoSuchUpload}. */
  private static RuntimeException gone(S3Exception e) {
    if (e.statusCode() == 404) {
      return new UploadGoneException("Multipart không còn trên kho", e);
    }
    return e;
  }
}
