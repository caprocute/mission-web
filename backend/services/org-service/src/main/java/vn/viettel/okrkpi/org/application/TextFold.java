package vn.viettel.okrkpi.org.application;

import java.text.Normalizer;
import java.util.Locale;

/** Bỏ dấu tiếng Việt, chữ thường; cùng quy tắc với hàm SQL {@code org.fn_fold_vi}. */
public final class TextFold {

  private TextFold() {
  }

  public static String fold(String text) {
    String nfd = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    return nfd.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
  }
}
