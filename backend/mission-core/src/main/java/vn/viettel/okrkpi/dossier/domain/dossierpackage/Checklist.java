package vn.viettel.okrkpi.dossier.domain.dossierpackage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Danh sách kiểm tra trước khi ký gói (BR-11-09, SCR-1102 vùng 3). Gói chỉ ký được khi mọi mục chặn
 * đều đạt; mục không chặn chỉ cảnh báo.
 */
public record Checklist(Instant evaluatedAt, List<Item> items) {

  public static final String SIGNED_PROFILES = "HO_SO_DA_KY_SO";
  public static final String HASHES_MATCH = "MA_BAM_KHOP";
  public static final String PERSONAL_SIGNED = "CA_NHAN_DA_KY";
  public static final String UNIT_FORM_SIGNED = "MAU_3_DA_KY";
  public static final String NO_OPEN_COMPLAINT = "KHONG_KHIEU_NAI_MO";

  public Checklist {
    Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    items = List.copyOf(items);
  }

  /** Một mục: {@code count} là số hồ sơ hoặc tệp chưa đạt. */
  public record Item(String code, String label, boolean ok, int count, boolean blocking) {

    public Item {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(label, "label");
    }
  }

  public boolean passed() {
    return items.stream().allMatch(i -> i.ok() || !i.blocking());
  }

  /** Số mục chặn chưa đạt (MSG-11-04). */
  public long failedCount() {
    return items.stream().filter(i -> !i.ok() && i.blocking()).count();
  }
}
