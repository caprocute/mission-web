package vn.viettel.okrkpi.evidence.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.S3Exception;
import vn.viettel.okrkpi.evidence.application.ObjectStorage.StoredObject;
import vn.viettel.okrkpi.evidence.application.TenantBucketService;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;

/**
 * Bucket theo tenant có Object Lock chế độ compliance (DV-08-01) trên S3 giả lập LocalStack; Ceph
 * RGW thật kiểm ở môi trường dev chung. Phiên bản đã khoá không xoá được trước hạn.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3ObjectLockIT {

  @Container
  static final GenericContainer<?> S3 =
      new GenericContainer<>("localstack/localstack:4.4")
          .withEnv("SERVICES", "s3")
          .withExposedPorts(4566)
          .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566));

  private static S3Client s3;

  @BeforeAll
  static void client() {
    s3 =
        S3Client.builder()
            .endpointOverride(URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(4566)))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .httpClient(UrlConnectionHttpClient.create())
            .build();
  }

  @AfterAll
  static void close() {
    s3.close();
  }

  @Test
  void bucketKhoaComplianceVaKhongXoaDuocTruocHan() throws NoSuchAlgorithmException {
    S3ObjectStorage storage = new S3ObjectStorage(s3, null);
    TenantBucketService buckets = new TenantBucketService(storage, "dev", 1);
    UUID tenant = UUID.randomUUID();

    String bucket = buckets.provision(tenant);
    assertThat(bucket).isEqualTo("ev-dev-" + tenant);
    assertThat(buckets.provision(tenant)).isEqualTo(bucket);
    assertThat(
            s3.getObjectLockConfiguration(b -> b.bucket(bucket))
                .objectLockConfiguration()
                .rule()
                .defaultRetention()
                .mode())
        .isEqualTo(ObjectLockRetentionMode.COMPLIANCE);

    byte[] content = "Biên bản nghiệm thu".getBytes(StandardCharsets.UTF_8);
    byte[] sha = MessageDigest.getInstance("SHA-256").digest(content);
    ObjectLocation loc = ObjectLocation.of("dev", tenant, sha);
    StoredObject stored =
        storage.put(loc, content, "text/plain", Instant.now().plus(1, ChronoUnit.DAYS));
    assertThat(stored.versionId()).isNotBlank();
    assertThat(storage.read(loc, stored.versionId())).isEqualTo(content);

    assertThatThrownBy(
            () ->
                s3.deleteObject(
                    DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(loc.key())
                        .versionId(stored.versionId())
                        .build()))
        .isInstanceOf(S3Exception.class);
    assertThat(storage.read(loc, stored.versionId())).isEqualTo(content);
  }
}
