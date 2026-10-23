package vn.viettel.okrkpi.org.application.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.org.application.port.WorkHistoryTransferStore;
import vn.viettel.okrkpi.org.domain.UuidV7;

/**
 * DV-13-10 · Phần "quá trình công tác" của gói chuyển hồ sơ liên cell (TL-03 "Đồng bộ giữa các cell", luồng thứ ba).
 * Cell nguồn trả các phân công đã ghi nhận của cán bộ, kèm tên đơn vị và chức danh tại thời điểm phân công vì mã của
 * tenant nguồn không có nghĩa ở tenant đích. Cell đích nhận phần đã được SVC-13 kiểm chữ ký và băm, giữ nguyên tài
 * liệu, chống trùng theo (gói, tên phần): gọi lại cùng nội dung là thành công, khác nội dung là xung đột.
 */
@Service
public class WorkHistoryTransferService {

  /** Loại phần, khớp {@code okrkpi.integ.cell-transfer.parts} của SVC-13. */
  public static final String KIND = "QUA_TRINH_CONG_TAC";

  /** Cỡ tối đa một phần. */
  public static final int MAX_BYTES = 1024 * 1024;

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  /** Thân phần "quá trình công tác". */
  public record WorkHistory(int version, UUID personId, List<WorkHistoryTransferStore.Entry> entries) {
  }

  /** Phần nhận được kèm thông tin từ tiêu đề lời gọi. */
  public record Part(UUID packageId, String partName, String kind, String sourceTenantCode, UUID sourcePersonId,
      String mediaType, byte[] content) {
  }

  /** Phần sai loại, sai tên, quá cỡ, không phải JSON hoặc không phải của cán bộ này. */
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

  private final WorkHistoryTransferStore store;
  private final ObjectMapper json;
  private final Clock clock;

  public WorkHistoryTransferService(WorkHistoryTransferStore store, ObjectMapper json, Clock clock) {
    this.store = store;
    this.json = json;
    this.clock = clock;
  }

  /** Rỗng khi cán bộ chưa có phân công nào. */
  @Transactional(readOnly = true)
  public Optional<WorkHistory> collect(UUID personId) {
    List<WorkHistoryTransferStore.Entry> entries = store.history(personId);
    return entries.isEmpty() ? Optional.empty() : Optional.of(new WorkHistory(1, personId, entries));
  }

  /** {@code true} khi nhập mới, {@code false} khi phần đã nhập trước đó với cùng nội dung. */
  @Transactional
  public boolean importPart(Part p, UUID actor) {
    String content = validate(p);
    byte[] sha = sha256(p.content());
    Optional<byte[]> existing = store.importedSha256(p.packageId(), p.partName());
    if (existing.isPresent()) {
      return same(existing.get(), sha);
    }
    boolean created = store.insertImported(new WorkHistoryTransferStore.Imported(UuidV7.next(clock), p.packageId(),
        p.partName(), p.sourceTenantCode(), p.sourcePersonId(), content, sha), actor);
    return created || same(store.importedSha256(p.packageId(), p.partName()).orElse(new byte[0]), sha);
  }

  private static boolean same(byte[] stored, byte[] sha) {
    if (!MessageDigest.isEqual(stored, sha)) {
      throw new ConflictException("Phần này của gói đã được nhập với nội dung khác");
    }
    return false;
  }

  private String validate(Part p) {
    if (p.packageId() == null || p.sourcePersonId() == null) {
      throw new InvalidPartException("Thiếu mã gói hoặc mã cán bộ");
    }
    if (p.partName() == null || !NAME.matcher(p.partName()).matches()) {
      throw new InvalidPartException("Tên phần không hợp lệ");
    }
    if (!KIND.equals(p.kind())) {
      throw new InvalidPartException("Loại phần không thuộc quá trình công tác");
    }
    if (p.sourceTenantCode() == null || p.sourceTenantCode().isBlank() || p.sourceTenantCode().length() > 64) {
      throw new InvalidPartException("Thiếu mã tenant nguồn");
    }
    if (p.mediaType() == null || !p.mediaType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
      throw new InvalidPartException("Phần quá trình công tác phải là JSON");
    }
    if (p.content() == null || p.content().length == 0 || p.content().length > MAX_BYTES) {
      throw new InvalidPartException("Nội dung rỗng hoặc quá cỡ");
    }
    String text = new String(p.content(), StandardCharsets.UTF_8);
    JsonNode node;
    try {
      node = json.readTree(text);
    } catch (JsonProcessingException e) {
      throw new InvalidPartException("Phần quá trình công tác phải là JSON");
    }
    if (node == null || !node.isObject() || !node.path("entries").isArray()
        || !p.sourcePersonId().toString().equals(node.path("personId").asText())) {
      throw new InvalidPartException("Phần quá trình công tác không đúng cấu trúc hoặc không phải của cán bộ này");
    }
    return text;
  }

  private static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
