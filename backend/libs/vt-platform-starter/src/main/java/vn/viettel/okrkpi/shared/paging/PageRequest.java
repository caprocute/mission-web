package vn.viettel.okrkpi.shared.paging;

import java.util.Optional;

/**
 * Yêu cầu một trang đã kiểm: {@code limit} 1..200 (mặc định 20) và vị trí sau dòng cuối trang trước
 * ({@code common.yaml} tham số {@code Limit}, {@code Cursor}). Dựng bằng {@link
 * CursorCodec#pageRequest(Integer, String, String)}.
 *
 * @param limit số dòng của trang
 * @param after keyset của dòng cuối trang trước, {@code null} ở trang đầu
 * @param scope phạm vi con trỏ (danh sách, bộ lọc, thứ tự) đã dùng khi kiểm
 */
public record PageRequest(int limit, CursorKey after, String scope) {

  /** Mặc định theo TL-02 ({@code common.yaml} tham số Limit). */
  public static final int DEFAULT_LIMIT = 20;

  /** Trần theo baseline mục 13. */
  public static final int MAX_LIMIT = 200;

  public PageRequest {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit ngoài 1.." + MAX_LIMIT);
    }
  }

  /** Số dòng cần đọc: {@code limit + 1} để biết còn trang sau. */
  public int fetchSize() {
    return limit + 1;
  }

  public Optional<CursorKey> afterKey() {
    return Optional.ofNullable(after);
  }

  public boolean firstPage() {
    return after == null;
  }
}
