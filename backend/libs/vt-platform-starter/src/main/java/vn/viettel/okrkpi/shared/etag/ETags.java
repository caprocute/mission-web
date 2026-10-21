package vn.viettel.okrkpi.shared.etag;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;

/**
 * Khoá lạc quan qua {@code ETag}/{@code If-Match} (baseline mục 13; BS-02 HD-01; {@code
 * common.yaml} tham số {@code IfMatch}, header {@code ETag}, lược đồ {@code RowVersion}).
 *
 * <ul>
 *   <li>ETag là {@code row_version} trong dấu nháy kép: {@code "7"};
 *   <li>cập nhật thiếu {@code If-Match} → 428 ({@link PreconditionRequiredException}); lệch phiên
 *       bản → 409 {@code <MODULE>-409-VERSION-CONFLICT} ({@link VersionConflictException}), không
 *       dùng 412;
 *   <li>đọc có {@code If-None-Match} trùng ETag hiện tại → 304 không thân.
 * </ul>
 *
 * <p>Controller sinh từ hợp đồng nhận {@code If-Match} dạng chuỗi rồi gọi {@link
 * #requireMatch(String, long, String)} trước khi ghi; kiểm cuối cùng vẫn là {@code @Version} của
 * JPA hoặc {@code WHERE row_version = ?} để bắt ghi đồng thời giữa lúc kiểm và lúc ghi.
 */
public final class ETags {

  private ETags() {}

  /** ETag của một {@code row_version}: {@code 7} → {@code "7"}. */
  public static String of(long rowVersion) {
    if (rowVersion < 0) {
      throw new IllegalArgumentException("row_version không âm");
    }
    return "\"" + rowVersion + "\"";
  }

  /**
   * Đọc phiên bản từ một ETag mạnh {@code "7"}. ETag yếu ({@code W/"7"}), {@code *} hay giá trị lạ
   * trả rỗng.
   */
  public static OptionalLong parse(String etag) {
    if (etag == null) {
      return OptionalLong.empty();
    }
    String v = etag.trim();
    if (v.length() < 3 || v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"') {
      return OptionalLong.empty();
    }
    String digits = v.substring(1, v.length() - 1);
    if (digits.isEmpty() || digits.length() > 18 || !digits.chars().allMatch(Character::isDigit)) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(Long.parseLong(digits));
  }

  /**
   * Kiểm {@code If-Match} trước khi cập nhật.
   *
   * @param ifMatch giá trị header, có thể nhiều ETag cách nhau bằng dấu phẩy
   * @param currentVersion {@code row_version} hiện tại
   * @param module phần MODULE của mã lỗi ({@code TASK}…), {@code null} để bộ xử lý lỗi tự xác định
   *     theo gói controller
   * @return phiên bản người gọi gửi (bằng {@code currentVersion})
   * @throws PreconditionRequiredException thiếu header (428)
   * @throws VersionConflictException không ETag nào khớp (409)
   */
  public static long requireMatch(String ifMatch, long currentVersion, String module) {
    if (ifMatch == null || ifMatch.isBlank()) {
      throw new PreconditionRequiredException();
    }
    Long first = null;
    for (String candidate : split(ifMatch)) {
      OptionalLong v = parse(candidate);
      if (v.isPresent()) {
        if (v.getAsLong() == currentVersion) {
          return currentVersion;
        }
        if (first == null) {
          first = v.getAsLong();
        }
      }
    }
    throw new VersionConflictException(module, first, currentVersion);
  }

  /** Phiên bản người gọi gửi trong {@code If-Match}; thiếu → 428, sai dạng → 409. */
  public static long expectedVersion(String ifMatch, String module) {
    if (ifMatch == null || ifMatch.isBlank()) {
      throw new PreconditionRequiredException();
    }
    for (String candidate : split(ifMatch)) {
      OptionalLong v = parse(candidate);
      if (v.isPresent()) {
        return v.getAsLong();
      }
    }
    throw new VersionConflictException(module, null, null);
  }

  /**
   * Đúng khi {@code If-None-Match} khớp ETag hiện tại (so sánh yếu theo RFC 9110: bỏ {@code W/};
   * {@code *} khớp mọi phiên bản).
   */
  public static boolean notModified(String ifNoneMatch, long currentVersion) {
    if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
      return false;
    }
    for (String candidate : split(ifNoneMatch)) {
      if ("*".equals(candidate)) {
        return true;
      }
      String strong = candidate.startsWith("W/") ? candidate.substring(2) : candidate;
      OptionalLong v = parse(strong);
      if (v.isPresent() && v.getAsLong() == currentVersion) {
        return true;
      }
    }
    return false;
  }

  /** 200 kèm header {@code ETag}. */
  public static <T> ResponseEntity<T> ok(T body, long rowVersion) {
    return ResponseEntity.ok().eTag(of(rowVersion)).body(body);
  }

  /**
   * Phản hồi đọc có ETag: 304 không thân khi {@code If-None-Match} khớp, ngược lại 200 kèm thân
   * dựng lười từ {@code body}.
   */
  public static <T> ResponseEntity<T> conditional(
      String ifNoneMatch, long rowVersion, Supplier<T> body) {
    if (notModified(ifNoneMatch, rowVersion)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(of(rowVersion)).build();
    }
    return ok(body.get(), rowVersion);
  }

  /** Header {@code ETag} để gắn vào phản hồi khác 200 (ví dụ 201 sau khi tạo). */
  public static HttpHeaders headers(long rowVersion) {
    HttpHeaders h = new HttpHeaders();
    h.setETag(of(rowVersion));
    return h;
  }

  private static List<String> split(String header) {
    List<String> out = new ArrayList<>();
    for (String part : header.split(",")) {
      String p = part.trim();
      if (!p.isEmpty()) {
        out.add(p);
      }
    }
    return out;
  }
}
