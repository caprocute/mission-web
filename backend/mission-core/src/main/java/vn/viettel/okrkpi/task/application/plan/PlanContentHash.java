package vn.viettel.okrkpi.task.application.plan;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.Assignee;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.PlanRow;

/**
 * Mã băm SHA-256 của phiên bản kế hoạch khi gửi duyệt (QT-04 bước 5, SCR-0403 trường 5,
 * CORE-02-08), là dữ liệu được ký số ở CORE-02-09.
 *
 * <p>Biểu diễn chuẩn hoá (canonical form), mã hoá UTF-8, mỗi bản ghi một dòng kết thúc bằng ký tự
 * xuống dòng LF:
 *
 * <ol>
 *   <li>Dòng đầu: {@code
 *       KE_HOACH|v1|<planId>|<periodId>|<ownerUnitId>|<ownerPersonId>|<versionNo>}.
 *   <li>Mỗi dòng kế hoạch theo {@code item_no} tăng dần, 16 trường cách nhau bằng {@code |}: {@code
 *       item_no, catalog_item_id, key_result_id, title, task_group, lead, co_assignees,
 *       product_name, quantity, unit, difficulty, standard_score, due_date, origin_item_id,
 *       change_reason, recurrence}.
 * </ol>
 *
 * <p>Quy ước giá trị: thiếu là chuỗi rỗng; UUID chữ thường có gạch; số thập phân bỏ số 0 thừa,
 * không dùng dạng mũ ({@code 1.50 → 1.5}, {@code 10 → 10}); ngày ISO {@code yyyy-MM-dd}; người chủ
 * trì {@code <kind>:<id>}; người phối hợp là danh sách {@code <kind>:<id>} sắp tăng dần, nối bằng
 * {@code ,}; tên người không vào băm. Trong chuỗi văn bản, dấu gạch ngược viết thành hai dấu gạch
 * ngược, còn {@code |}, xuống dòng (LF) và về đầu dòng (CR) viết thành dấu gạch ngược theo sau là
 * {@code |}, {@code n} và {@code r}; chuỗi giữ nguyên dấu tiếng Việt như đã lưu (không chuẩn hoá
 * Unicode). Bên ký số và công cụ kiểm tra độc lập dựng lại đúng chuỗi này để tính lại băm.
 */
public final class PlanContentHash {

  static final String HEADER = "KE_HOACH|v1";

  private PlanContentHash() {}

  public static byte[] of(PlanRow plan, int versionNo, List<ItemRow> items) {
    return sha256(canonical(plan, versionNo, items));
  }

  static String canonical(PlanRow plan, int versionNo, List<ItemRow> items) {
    StringBuilder b = new StringBuilder(64 + items.size() * 160);
    b.append(HEADER)
        .append('|')
        .append(uuid(plan.id()))
        .append('|')
        .append(uuid(plan.periodId()))
        .append('|')
        .append(uuid(plan.ownerUnitId()))
        .append('|')
        .append(uuid(plan.ownerPersonId()))
        .append('|')
        .append(versionNo)
        .append('\n');
    items.stream()
        .sorted(Comparator.comparingInt(ItemRow::itemNo))
        .forEach(
            i ->
                b.append(i.itemNo())
                    .append('|')
                    .append(uuid(i.catalogItemId()))
                    .append('|')
                    .append(uuid(i.keyResultId()))
                    .append('|')
                    .append(text(i.title()))
                    .append('|')
                    .append(text(i.taskGroup()))
                    .append('|')
                    .append(assignee(i.lead()))
                    .append('|')
                    .append(coAssignees(i.coAssignees()))
                    .append('|')
                    .append(text(i.productName()))
                    .append('|')
                    .append(number(i.quantity()))
                    .append('|')
                    .append(text(i.unit()))
                    .append('|')
                    .append(i.difficulty() == null ? "" : i.difficulty().toString())
                    .append('|')
                    .append(number(i.standardScore()))
                    .append('|')
                    .append(i.dueDate() == null ? "" : i.dueDate().toString())
                    .append('|')
                    .append(uuid(i.originItemId()))
                    .append('|')
                    .append(text(i.changeReason()))
                    .append('|')
                    .append(text(i.recurrence()))
                    .append('\n'));
    return b.toString();
  }

  private static String coAssignees(List<Assignee> co) {
    return co == null
        ? ""
        : String.join(",", co.stream().map(PlanContentHash::assignee).sorted().toList());
  }

  private static String assignee(Assignee a) {
    return a == null || a.id() == null ? "" : text(a.kind()) + ":" + uuid(a.id());
  }

  private static String uuid(UUID id) {
    return id == null ? "" : id.toString();
  }

  private static String number(BigDecimal v) {
    if (v == null) {
      return "";
    }
    return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
  }

  private static String text(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder b = new StringBuilder(s.length());
    for (char c : s.toCharArray()) {
      switch (c) {
        case '\\' -> b.append("\\\\");
        case '|' -> b.append("\\|");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        default -> b.append(c);
      }
    }
    return b.toString();
  }

  private static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM thiếu SHA-256", e);
    }
  }
}
