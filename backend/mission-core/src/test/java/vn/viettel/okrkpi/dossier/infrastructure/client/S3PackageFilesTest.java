package vn.viettel.okrkpi.dossier.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageFiles;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Ghi theo luồng bằng tải lên nhiều phần, đọc, URL tải ký có hạn (CORE-06-08). */
class S3PackageFilesTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());

  /** S3 giả: chỉ các thao tác cổng dùng, ghi lại phần đã tải. */
  static final class FakeS3 implements S3Client {
    final Map<String, byte[]> objects = new LinkedHashMap<>();
    final List<Integer> partSizes = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    int failPart = -1;

    @Override
    public String serviceName() {
      return "s3";
    }

    @Override
    public void close() {}

    @Override
    public CreateMultipartUploadResponse createMultipartUpload(CreateMultipartUploadRequest r) {
      calls.add("create:" + r.key());
      return CreateMultipartUploadResponse.builder().uploadId("u-1").build();
    }

    @Override
    public UploadPartResponse uploadPart(UploadPartRequest r, RequestBody body) {
      if (r.partNumber() == failPart) {
        throw S3Exception.builder().message("hỏng").statusCode(500).build();
      }
      try (InputStream in = body.contentStreamProvider().newStream()) {
        byte[] b = in.readAllBytes();
        partSizes.add(b.length);
        objects.merge(r.key(), b, S3PackageFilesTest::concat);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return UploadPartResponse.builder().eTag("e" + r.partNumber()).build();
    }

    @Override
    public CompleteMultipartUploadResponse completeMultipartUpload(
        CompleteMultipartUploadRequest r) {
      calls.add("complete:" + r.multipartUpload().parts().size());
      return CompleteMultipartUploadResponse.builder().build();
    }

    @Override
    public AbortMultipartUploadResponse abortMultipartUpload(AbortMultipartUploadRequest r) {
      calls.add("abort:" + r.uploadId());
      objects.remove(r.key());
      return AbortMultipartUploadResponse.builder().build();
    }

    @Override
    public PutObjectResponse putObject(PutObjectRequest r, RequestBody body) {
      try (InputStream in = body.contentStreamProvider().newStream()) {
        objects.put(r.key(), in.readAllBytes());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      calls.add("put:" + r.contentType());
      return PutObjectResponse.builder().build();
    }

    @Override
    public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest r) {
      byte[] b = objects.get(r.key());
      if (b == null) {
        throw NoSuchKeyException.builder().message(r.key()).build();
      }
      return new ResponseInputStream<>(
          GetObjectResponse.builder().build(),
          AbortableInputStream.create(new ByteArrayInputStream(b)));
    }

    @Override
    public HeadObjectResponse headObject(HeadObjectRequest r) {
      byte[] b = objects.get(r.key());
      if (b == null) {
        throw NoSuchKeyException.builder().message(r.key()).build();
      }
      return HeadObjectResponse.builder().contentLength((long) b.length).build();
    }
  }

  static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  final FakeS3 s3 = new FakeS3();
  final S3Presigner presigner =
      S3Presigner.builder()
          .region(Region.US_EAST_1)
          .endpointOverride(URI.create("http://kho.local:7480"))
          .credentialsProvider(
              StaticCredentialsProvider.create(AwsBasicCredentials.create("a", "b")))
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
          .build();

  @Test
  void ghiNhieuPhanBoDemCoDinh() throws IOException {
    S3PackageFiles files = new S3PackageFiles(s3, presigner, "ho-so", 5);
    byte[] content = "0123456789abcdefghij-xyz".getBytes(StandardCharsets.UTF_8);
    try (PackageFiles.WritableFile out = files.create(TENANT, "x/a.zip", "application/zip")) {
      out.write(content, 0, 7);
      out.write(content[7]);
      out.write(content, 8, content.length - 8);
    }
    assertThat(s3.objects.get("x/a.zip")).isEqualTo(content);
    assertThat(s3.partSizes).containsExactly(5, 5, 5, 5, 4);
    assertThat(s3.calls).containsExactly("create:x/a.zip", "complete:5");
    assertThat(files.size(TENANT, "x/a.zip")).isEqualTo(content.length);
    try (InputStream in = files.open(TENANT, "x/a.zip")) {
      assertThat(in.readAllBytes()).isEqualTo(content);
    }
  }

  @Test
  void tepNhoGhiMotLanVaDongLaiKhongGhiThem() throws IOException {
    S3PackageFiles files = new S3PackageFiles(s3, presigner, "ho-so");
    PackageFiles.WritableFile out = files.create(TENANT, "x/b.zip", "application/zip");
    out.write(new byte[] {1, 2, 3});
    out.close();
    out.close();
    out.abort();
    assertThat(s3.calls).containsExactly("put:application/zip");
    assertThatThrownBy(() -> out.write(1)).isInstanceOf(IOException.class);
  }

  @Test
  void loiGiuaChungThiHuyLanTaiLen() throws IOException {
    S3PackageFiles files = new S3PackageFiles(s3, presigner, "ho-so", 4);
    s3.failPart = 2;
    PackageFiles.WritableFile out = files.create(TENANT, "x/c.zip", "application/zip");
    assertThatThrownBy(() -> out.write(new byte[12])).isInstanceOf(IOException.class);
    assertThat(s3.calls).contains("abort:u-1");
    assertThat(s3.objects).doesNotContainKey("x/c.zip");

    PackageFiles.WritableFile other = files.create(TENANT, "x/d.zip", "application/zip");
    other.write(new byte[6]);
    other.abort();
    assertThat(s3.calls).containsSubsequence("create:x/d.zip", "abort:u-1");
  }

  @Test
  void tepKhongCoVaUrlTaiCoHan() {
    S3PackageFiles files = new S3PackageFiles(s3, presigner, "ho-so");
    assertThatThrownBy(() -> files.open(TENANT, "khong-co"))
        .isInstanceOf(FileNotFoundException.class);
    assertThatThrownBy(() -> files.size(TENANT, "khong-co"))
        .isInstanceOf(FileNotFoundException.class);
    String url = files.downloadUrl(TENANT, "x/a.zip", "DP-2026-N-1-v1.zip", Duration.ofMinutes(15));
    assertThat(url)
        .startsWith("http://kho.local:7480/ho-so/x/a.zip?")
        .contains("X-Amz-Expires=900")
        .contains("response-content-disposition=");
  }
}
