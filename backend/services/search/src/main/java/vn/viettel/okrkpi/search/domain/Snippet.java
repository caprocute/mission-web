package vn.viettel.okrkpi.search.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Đoạn trích văn bản thuần và vị trí phần khớp. Highlighter của OpenSearch được đặt thẻ mở, đóng là
 * hai ký tự vùng dùng riêng (private use) U+E000, U+E001 thay cho thẻ HTML; lớp này bỏ hai ký tự đó
 * và đổi thành vị trí {@code start}, {@code length} đếm theo đơn vị UTF-16 của chuỗi trả về (khớp
 * kiểu {@code String} của Java và JavaScript). Văn bản lập chỉ mục đã bị loại ký tự vùng dùng riêng
 * ({@link VietnameseText}), nên hai dấu này chỉ có thể do highlighter sinh ra.
 */
public record Snippet(String text, List<Highlight> highlights) {

  public static final String PRE = "";
  public static final String POST = "";

  public record Highlight(int start, int length) {}

  public static Snippet parse(String marked) {
    StringBuilder out = new StringBuilder(marked.length());
    List<Highlight> hs = new ArrayList<>();
    int open = -1;
    for (int i = 0; i < marked.length(); i++) {
      char c = marked.charAt(i);
      if (c == '') {
        open = out.length();
      } else if (c == '') {
        if (open >= 0 && out.length() > open) {
          merge(hs, open, out.length() - open);
        }
        open = -1;
      } else {
        out.append(c);
      }
    }
    return new Snippet(out.toString(), List.copyOf(hs));
  }

  /** Gộp hai phần khớp liền nhau (highlighter đánh dấu từng từ) để giao diện tô một đoạn. */
  private static void merge(List<Highlight> hs, int start, int length) {
    if (!hs.isEmpty()) {
      Highlight last = hs.get(hs.size() - 1);
      if (last.start() + last.length() + 1 >= start) {
        hs.set(hs.size() - 1, new Highlight(last.start(), start + length - last.start()));
        return;
      }
    }
    hs.add(new Highlight(start, length));
  }
}
