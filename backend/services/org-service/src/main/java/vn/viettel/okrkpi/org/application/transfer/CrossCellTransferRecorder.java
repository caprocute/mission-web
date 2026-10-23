package vn.viettel.okrkpi.org.application.transfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.AssignmentCommandService;
import vn.viettel.okrkpi.org.application.ChangeOrderRecord;
import vn.viettel.okrkpi.org.application.port.AssignmentStore;
import vn.viettel.okrkpi.org.application.port.ChangeOrderStore;
import vn.viettel.okrkpi.org.domain.AssignmentVersion;
import vn.viettel.okrkpi.org.domain.OrgConflictException;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;

/**
 * DV-13-10 · Ghi quyết định điều động liên cell đã duyệt trong một giao dịch: quyết định {@code DIEU_DONG} ở trạng thái
 * đã áp dụng (đích đến ghi trong {@code payload.crossCell}) và đóng mọi phân công còn hiệu lực của cán bộ từ ngày hiệu
 * lực. Mã quyết định và mã gói suy ra tất định từ tenant, số quyết định và cán bộ (UUID tên, SHA-1), nên gọi lại cùng
 * yêu cầu không ghi lần hai và gateway nhận cùng mã gói.
 */
@Service
public class CrossCellTransferRecorder {

  static final String ORDER_KIND = "DIEU_DONG";
  static final String APPLIED = "DA_AP_DUNG";

  /** Yêu cầu điều động liên cell đã được cấp có thẩm quyền duyệt. */
  public record Command(UUID personId, String orderNo, LocalDate orderDate, LocalDate effectiveOn, String signedDocRef,
      String sourceTenantCode, String targetCell, UUID targetTenantId, String targetTenantCode) {
  }

  /** Quyết định đã ghi; {@code created = false} khi yêu cầu đã được ghi trước đó. */
  public record Recorded(UUID orderId, UUID packageId, boolean created) {
  }

  private final ChangeOrderStore orders;
  private final AssignmentStore assignments;
  private final AssignmentCommandService assignmentCommands;

  public CrossCellTransferRecorder(ChangeOrderStore orders, AssignmentStore assignments,
      AssignmentCommandService assignmentCommands) {
    this.orders = orders;
    this.assignments = assignments;
    this.assignmentCommands = assignmentCommands;
  }

  @Transactional
  public Recorded record(Actor actor, Command cmd) {
    validate(cmd);
    UUID orderId = nameUuid(actor.tenantId() + "/DIEU_DONG_LIEN_CELL/" + cmd.orderNo());
    UUID packageId = nameUuid(actor.tenantId() + "/" + cmd.personId() + "/" + orderId);
    Map<String, Object> crossCell = new LinkedHashMap<>();
    crossCell.put("targetCell", cmd.targetCell());
    crossCell.put("targetTenantId", cmd.targetTenantId().toString());
    crossCell.put("targetTenantCode", cmd.targetTenantCode());
    crossCell.put("sourceTenantCode", cmd.sourceTenantCode());
    crossCell.put("packageId", packageId.toString());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("changeType", "DIEU_DONG_LIEN_CELL");
    payload.put("personId", cmd.personId().toString());
    payload.put("crossCell", crossCell);

    ChangeOrderRecord existing = orders.findByIds(List.of(orderId)).get(orderId);
    if (existing != null) {
      if (!payload.equals(existing.payload()) || !cmd.effectiveOn().equals(existing.effectiveOn())) {
        throw new OrgConflictException("ORG-409-ORDER-NO-EXISTS",
            "Số quyết định đã được ghi nhận cho một điều động khác.");
      }
      return new Recorded(orderId, packageId, false);
    }
    List<AssignmentVersion> current = assignments.currentForPersonForUpdate(cmd.personId());
    Set<UUID> open = new LinkedHashSet<>();
    for (AssignmentVersion v : current) {
      boolean endsLater = v.validPeriod().isOpenEnded() || v.validPeriod().to().isAfter(cmd.effectiveOn());
      if (endsLater && v.validPeriod().from().isBefore(cmd.effectiveOn())) {
        open.add(v.id());
      }
    }
    if (open.isEmpty()) {
      throw new OrgNotFoundException("Cán bộ không có phân công còn hiệu lực tại ngày điều động");
    }
    orders.insert(new ChangeOrderRecord(orderId, cmd.orderNo(), cmd.orderDate(), ORDER_KIND, cmd.effectiveOn(),
        cmd.signedDocRef(), payload, APPLIED, null), actor);
    for (UUID id : open) {
      assignmentCommands.endAssignment(actor, id, cmd.effectiveOn(), orderId);
    }
    return new Recorded(orderId, packageId, true);
  }

  private static void validate(Command c) {
    Objects.requireNonNull(c, "cmd");
    if (c.personId() == null || c.targetTenantId() == null || c.orderDate() == null || c.effectiveOn() == null) {
      throw new OrgRuleViolation("ORG-422-CELL-TRANSFER", null, null, null, "Thiếu cán bộ, tenant đích, ngày quyết định hoặc ngày hiệu lực.");
    }
    if (blank(c.orderNo(), 64) || blank(c.sourceTenantCode(), 64) || blank(c.targetTenantCode(), 64)
        || c.targetCell() == null || !c.targetCell().matches("[A-Z0-9][A-Z0-9_-]{0,15}")
        || (c.signedDocRef() != null && c.signedDocRef().length() > 200)) {
      throw new OrgRuleViolation("ORG-422-CELL-TRANSFER", null, null, null, "Số quyết định, mã tenant hoặc mã cell không hợp lệ.");
    }
    if (c.effectiveOn().isBefore(c.orderDate())) {
      throw new OrgRuleViolation("ORG-422-CELL-TRANSFER", null, null, "effectiveOn", "Ngày hiệu lực không được trước ngày quyết định.");
    }
  }

  private static boolean blank(String s, int max) {
    return s == null || s.isBlank() || s.length() > max;
  }

  /** UUID tên phiên bản 5 (RFC 9562, SHA-1) trong không gian tên URL. */
  static UUID nameUuid(String name) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(new byte[] {0x6b, (byte) 0xa7, (byte) 0xb8, 0x11, (byte) 0x9d, (byte) 0xad, 0x11, (byte) 0xd1,
          (byte) 0x80, (byte) 0xb4, 0x00, (byte) 0xc0, 0x4f, (byte) 0xd4, 0x30, (byte) 0xc8});
      byte[] h = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
      h[6] = (byte) ((h[6] & 0x0f) | 0x50);
      h[8] = (byte) ((h[8] & 0x3f) | 0x80);
      long msb = 0;
      long lsb = 0;
      for (int i = 0; i < 8; i++) {
        msb = (msb << 8) | (h[i] & 0xff);
        lsb = (lsb << 8) | (h[i + 8] & 0xff);
      }
      return new UUID(msb, lsb);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
