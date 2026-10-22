package vn.viettel.okrkpi.oversight.domain.reopen;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Một đối tượng trong {@code scope_json.items}: bảng, bản ghi, cột được phép sửa. */
public record ReopenScopeItem(
    String table, UUID profileId, UUID unitId, UUID recordId, List<String> fields) {

  private static final Pattern TABLE =
      Pattern.compile("^[a-z][a-z0-9_]{0,62}\\.[a-z][a-z0-9_]{0,62}$");
  private static final Pattern COLUMN = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

  public ReopenScopeItem {
    fields = fields == null ? List.of() : List.copyOf(fields);
  }

  /** Tên bảng {@code schema.bảng} và tên cột chỉ gồm chữ thường, số, gạch dưới. */
  public boolean wellFormed() {
    return table != null
        && TABLE.matcher(table).matches()
        && fields.stream().allMatch(f -> f != null && COLUMN.matcher(f).matches());
  }
}
