package vn.viettel.okrkpi.evidence.application;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import vn.viettel.okrkpi.evidence.domain.ObjectLocation;

/** Cổng kho đối tượng S3 (Ceph RGW); hiện thực ở hạ tầng bằng AWS SDK for Java v2. */
public interface ObjectStorage {

  /** Đối tượng đã ghi: mã phiên bản RGW và hạn khoá compliance. */
  record StoredObject(ObjectLocation location, String versionId, Instant lockedUntil) {}

  /** Một phần đã nhận của multipart. */
  record Part(int partNo, long size, String etag) {}

  /** Đọc luồng nội dung; luồng đóng sau khi hàm trả về. */
  @FunctionalInterface
  interface StreamReader<T> {
    T read(InputStream in) throws IOException;
  }

  /** Multipart không còn trên kho (đã hoàn tất, đã huỷ hoặc bị vòng đời bucket xoá). */
  class UploadGoneException extends RuntimeException {
    public UploadGoneException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Luồng phần bị cắt giữa chừng (client mất kết nối): kho không ghi nhận phần này. */
  class PartInterruptedException extends RuntimeException {
    public PartInterruptedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Tạo bucket có versioning và Object Lock (bật từ lúc tạo, không bật sau được), đặt khoá mặc định
   * chế độ compliance {@code lockDays} ngày. Gọi lại khi bucket đã có thì không lỗi.
   *
   * @return {@code true} khi bucket vừa được tạo
   */
  boolean ensureLockedBucket(String bucket, int lockDays);

  /** Tạo bucket tạm không khoá, có vòng đời xoá đối tượng và multipart dở sau {@code days} ngày. */
  void ensureStagingBucket(String bucket, int days);

  /** Ghi nội dung, khoá chế độ compliance đến {@code lockUntil}. */
  StoredObject put(ObjectLocation location, byte[] content, String mediaType, Instant lockUntil);

  /** Đọc một phiên bản. */
  byte[] read(ObjectLocation location, String versionId);

  /** Mở multipart, trả mã multipart của RGW. */
  String createMultipart(ObjectLocation location, String mediaType);

  /** Gửi một phần theo luồng, độ dài biết trước; gửi lại cùng số phần thì ghi đè. Trả ETag. */
  String uploadPart(
      ObjectLocation location, String uploadId, int partNo, InputStream content, long length);

  /** Các phần đã nhận, theo số phần. */
  List<Part> listParts(ObjectLocation location, String uploadId);

  void completeMultipart(ObjectLocation location, String uploadId, List<Part> parts);

  /** Huỷ multipart; đã không còn thì bỏ qua. */
  void abortMultipart(ObjectLocation location, String uploadId);

  /** Đọc theo luồng (phiên bản hiện hành khi {@code versionId} rỗng). */
  <T> T stream(ObjectLocation location, String versionId, StreamReader<T> reader)
      throws IOException;

  /** Sao chép phía kho sang vị trí đích, khoá compliance đến {@code lockUntil}. */
  StoredObject copyLocked(
      ObjectLocation from, ObjectLocation to, String mediaType, Instant lockUntil);

  /** Xoá đối tượng tạm (không dùng cho bucket có khoá). */
  void deleteStaging(ObjectLocation location);

  /** URL ký trước cho đúng một phiên bản đối tượng. */
  URI presignGet(
      ObjectLocation location,
      String versionId,
      Duration ttl,
      String mediaType,
      String contentDisposition);
}
