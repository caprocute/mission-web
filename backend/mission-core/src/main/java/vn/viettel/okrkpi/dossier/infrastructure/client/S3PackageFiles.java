package vn.viettel.okrkpi.dossier.infrastructure.client;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageFiles;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * {@link PackageFiles} trên bucket hồ sơ số (Ceph RGW, giao diện S3, CORE-06-08). Ghi theo luồng
 * bằng tải lên nhiều phần (multipart upload) với một bộ đệm phần 8 MB: bộ nhớ dùng cố định dù tệp
 * ZIP lớn tới đâu; tệp nhỏ hơn một phần ghi bằng một lệnh {@code PutObject}. Lỗi giữa chừng thì huỷ
 * lần tải lên nhiều phần để không để lại phần rác. URL tải ký có hạn bằng {@link S3Presigner}.
 */
public class S3PackageFiles implements PackageFiles {

  /** Kích thước phần (S3 yêu cầu tối thiểu 5 MB cho mọi phần trừ phần cuối). */
  static final int PART_SIZE = 8 * 1024 * 1024;

  private static final Logger LOG = LoggerFactory.getLogger(S3PackageFiles.class);

  private final S3Client s3;
  private final S3Presigner presigner;
  private final String bucket;
  private final int partSize;

  public S3PackageFiles(S3Client s3, S3Presigner presigner, String bucket) {
    this(s3, presigner, bucket, PART_SIZE);
  }

  S3PackageFiles(S3Client s3, S3Presigner presigner, String bucket, int partSize) {
    this.s3 = Objects.requireNonNull(s3, "s3");
    this.presigner = Objects.requireNonNull(presigner, "presigner");
    this.bucket = Objects.requireNonNull(bucket, "bucket");
    this.partSize = partSize;
  }

  @Override
  public InputStream open(TenantId tenant, String objectKey) throws IOException {
    try {
      return s3.getObject(b -> b.bucket(bucket).key(objectKey));
    } catch (NoSuchKeyException e) {
      throw new FileNotFoundException(objectKey);
    } catch (SdkException e) {
      throw new IOException("Không đọc được tệp " + objectKey, e);
    }
  }

  @Override
  public long size(TenantId tenant, String objectKey) throws IOException {
    try {
      Long len = s3.headObject(b -> b.bucket(bucket).key(objectKey)).contentLength();
      return len == null ? -1 : len;
    } catch (NoSuchKeyException e) {
      throw new FileNotFoundException(objectKey);
    } catch (SdkException e) {
      throw new IOException("Không đọc được tệp " + objectKey, e);
    }
  }

  @Override
  public WritableFile create(TenantId tenant, String objectKey, String contentType) {
    return new Multipart(objectKey, contentType);
  }

  @Override
  public String downloadUrl(TenantId tenant, String objectKey, String fileName, Duration ttl) {
    return presigner
        .presignGetObject(
            p ->
                p.signatureDuration(ttl)
                    .getObjectRequest(
                        g ->
                            g.bucket(bucket)
                                .key(objectKey)
                                .responseContentDisposition(
                                    "attachment; filename=\"" + fileName + "\"")))
        .url()
        .toString();
  }

  /** Luồng ghi một tệp bằng tải lên nhiều phần. */
  final class Multipart extends WritableFile {

    private final String key;
    private final String contentType;
    private final byte[] buffer;
    private final List<CompletedPart> parts = new ArrayList<>();
    private int pos;
    private String uploadId;
    private boolean finished;

    Multipart(String key, String contentType) {
      this.key = key;
      this.contentType = contentType;
      this.buffer = new byte[partSize];
    }

    @Override
    public void write(int b) throws IOException {
      ensureOpen();
      if (pos == buffer.length) {
        uploadPart();
      }
      buffer[pos++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      ensureOpen();
      Objects.checkFromIndexSize(off, len, b.length);
      while (len > 0) {
        if (pos == buffer.length) {
          uploadPart();
        }
        int n = Math.min(len, buffer.length - pos);
        System.arraycopy(b, off, buffer, pos, n);
        pos += n;
        off += n;
        len -= n;
      }
    }

    @Override
    public void close() throws IOException {
      if (finished) {
        return;
      }
      try {
        if (uploadId == null) {
          s3.putObject(
              r -> r.bucket(bucket).key(key).contentType(contentType).contentLength((long) pos),
              RequestBody.fromInputStream(new ByteArrayInputStream(buffer, 0, pos), pos));
        } else {
          if (pos > 0) {
            uploadPart();
          }
          s3.completeMultipartUpload(
              r ->
                  r.bucket(bucket)
                      .key(key)
                      .uploadId(uploadId)
                      .multipartUpload(m -> m.parts(parts)));
        }
        finished = true;
      } catch (SdkException e) {
        abort();
        throw new IOException("Không ghi được tệp " + key, e);
      }
    }

    @Override
    public void abort() {
      if (finished) {
        return;
      }
      finished = true;
      if (uploadId != null) {
        try {
          s3.abortMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId));
        } catch (SdkException e) {
          LOG.warn("Không huỷ được lần tải lên dở của tệp {}", key, e);
        }
      }
    }

    private void uploadPart() throws IOException {
      try {
        if (uploadId == null) {
          uploadId =
              s3.createMultipartUpload(r -> r.bucket(bucket).key(key).contentType(contentType))
                  .uploadId();
        }
        int number = parts.size() + 1;
        String etag =
            s3.uploadPart(
                    r ->
                        r.bucket(bucket)
                            .key(key)
                            .uploadId(uploadId)
                            .partNumber(number)
                            .contentLength((long) pos),
                    RequestBody.fromInputStream(new ByteArrayInputStream(buffer, 0, pos), pos))
                .eTag();
        parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
        pos = 0;
      } catch (SdkException e) {
        abort();
        throw new IOException("Không ghi được tệp " + key, e);
      }
    }

    private void ensureOpen() throws IOException {
      if (finished) {
        throw new IOException("Tệp " + key + " đã đóng");
      }
    }
  }
}
