package vn.viettel.okrkpi.dossier.application.transfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.transfer.port.TransferredRecords;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Nhập một phần của gói chuyển liên cell vào MOD-06 của tenant đích (DV-13-10). SVC-13 đã kiểm chữ
 * ký, giải mã và đối chiếu băm trước khi gọi; ở đây chỉ nhận ba loại phần của hồ sơ, giữ nguyên tài
 * liệu của cell nguồn dưới dạng C2 mã hoá phong bì, và chống trùng theo (gói, tên phần): gọi lại
 * cùng nội dung là thành công, khác nội dung là xung đột.
 */
public class TransferImportService {

  /** Cột đủ đưa vào AAD của nội dung. */
  public static final String CONTENT_COLUMN = "dossier.transferred_record.content_enc";

  /** Loại phần MOD-06 nhận, khớp {@code okrkpi.integ.cell-transfer.parts} của SVC-13. */
  public static final Set<String> KINDS =
      Set.of("KET_QUA_XEP_LOAI", "HO_SO_DA_KY", "KET_LUAN_KIEM_DIEM");

  /** Cỡ tối đa một phần. */
  public static final int MAX_BYTES = 4 * 1024 * 1024;

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  /** Thông tin đi kèm phần, lấy từ tiêu đề của lời gọi. */
  public record Part(
      UUID packageId,
      String partName,
      String kind,
      String sourceTenantCode,
      UUID sourcePersonId,
      String mediaType,
      byte[] content) {}

  /** Kết quả: {@code created = false} khi phần đã nhập trước đó với cùng nội dung. */
  public record Result(boolean created) {}

  /** Phần sai loại, sai tên, quá cỡ hoặc không phải JSON. */
  public static final class InvalidPartException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidPartException(String message) {
      super(message);
    }
  }

  /** Phần đã nhập với nội dung khác. */
  public static final class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
      super(message);
    }
  }

  private final TransferredRecords records;
  private final CommentSealer sealer;

  public TransferImportService(TransferredRecords records, CommentSealer sealer) {
    this.records = Objects.requireNonNull(records, "records");
    this.sealer = Objects.requireNonNull(sealer, "sealer");
  }

  public Result importPart(TenantId tenant, Part p, UUID actor) {
    validate(p);
    byte[] sha = sha256(p.content());
    var existing = records.contentSha256(tenant, p.packageId(), p.partName());
    if (existing.isPresent()) {
      return same(existing.get(), sha);
    }
    UUID id = UUID.randomUUID();
    CommentSealer.Sealed s =
        sealer.seal(tenant, CONTENT_COLUMN, id, new String(p.content(), StandardCharsets.UTF_8));
    boolean created =
        records.insert(
            tenant,
            new TransferredRecords.NewRecord(
                id,
                p.packageId(),
                p.partName(),
                p.kind(),
                p.sourceTenantCode(),
                p.sourcePersonId(),
                p.mediaType(),
                sha,
                s.cipher(),
                s.dekId()),
            actor);
    if (created) {
      return new Result(true);
    }
    return same(
        records.contentSha256(tenant, p.packageId(), p.partName()).orElse(new byte[0]), sha);
  }

  private static Result same(byte[] stored, byte[] sha) {
    if (!MessageDigest.isEqual(stored, sha)) {
      throw new ConflictException("Phần này của gói đã được nhập với nội dung khác");
    }
    return new Result(false);
  }

  private static void validate(Part p) {
    if (p.packageId() == null || p.sourcePersonId() == null) {
      throw new InvalidPartException("Thiếu mã gói hoặc mã cán bộ");
    }
    if (p.partName() == null || !NAME.matcher(p.partName()).matches()) {
      throw new InvalidPartException("Tên phần không hợp lệ");
    }
    if (p.kind() == null || !KINDS.contains(p.kind())) {
      throw new InvalidPartException("Loại phần không thuộc hồ sơ");
    }
    if (p.sourceTenantCode() == null
        || p.sourceTenantCode().isBlank()
        || p.sourceTenantCode().length() > 64) {
      throw new InvalidPartException("Thiếu mã tenant nguồn");
    }
    if (p.mediaType() == null || !p.mediaType().toLowerCase().startsWith("application/json")) {
      throw new InvalidPartException("Phần hồ sơ phải là JSON");
    }
    byte[] c = p.content();
    if (c == null || c.length == 0 || c.length > MAX_BYTES) {
      throw new InvalidPartException("Nội dung rỗng hoặc quá cỡ");
    }
    int first = 0;
    while (first < c.length && Character.isWhitespace(c[first])) {
      first++;
    }
    if (first == c.length || (c[first] != '{' && c[first] != '[')) {
      throw new InvalidPartException("Phần hồ sơ phải là JSON");
    }
  }

  private static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
