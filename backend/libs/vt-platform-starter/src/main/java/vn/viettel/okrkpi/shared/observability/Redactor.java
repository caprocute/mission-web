package vn.viettel.okrkpi.shared.observability;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Che các mẫu nhạy cảm nhận diện được trong chuỗi (TL-01 "Che dữ liệu nhạy cảm", lớp hai): chuỗi
 * JWT ({@code eyJ...}), token sau {@code Bearer}/{@code Basic}, giá trị header {@code Cookie}, số
 * định danh 12 chữ số (CCCD), số điện thoại Việt Nam, địa chỉ email. Dùng chung cho {@link
 * RedactingRewritePolicy} (Log4j2), nhật ký kiểm toán và {@code toString()} của kiểu có dữ liệu C2.
 *
 * <p>Số 12 chữ số chỉ bị che khi đứng riêng (không liền chữ, số hay dấu gạch), để mã UUID và mã vết
 * hex không bị che nhầm.
 */
public final class Redactor {

  /** Ký hiệu thay thế, giữ loại dữ liệu để tra cứu sự cố. */
  public static final String JWT = "[REDACTED:JWT]";

  public static final String TOKEN = "[REDACTED:TOKEN]";
  public static final String ID_NUMBER = "[REDACTED:ID]";
  public static final String PHONE = "[REDACTED:PHONE]";
  public static final String EMAIL = "[REDACTED:EMAIL]";

  private record Rule(Pattern pattern, String replacement) {}

  private static final List<Rule> RULES =
      List.of(
          new Rule(
              Pattern.compile("eyJ[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]*"), JWT),
          new Rule(
              Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}"),
              "$1 " + Matcher.quoteReplacement(TOKEN)),
          new Rule(
              Pattern.compile("(?i)\\b(cookie|set-cookie)(\\s*[:=]\\s*)[^\\r\\n]+"),
              "$1$2" + Matcher.quoteReplacement(TOKEN)),
          new Rule(
              Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
              Matcher.quoteReplacement(EMAIL)),
          new Rule(
              Pattern.compile("(?<![\\w-])\\d{12}(?![\\w-])"), Matcher.quoteReplacement(ID_NUMBER)),
          new Rule(
              Pattern.compile("(?<![\\w+-])(?:\\+84|84|0)[35789]\\d{8}(?![\\w-])"),
              Matcher.quoteReplacement(PHONE)));

  private Redactor() {}

  /** Bản đã che; trả lại chính {@code text} khi không có gì phải che. */
  public static String redact(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String out = text;
    for (Rule rule : RULES) {
      Matcher m = rule.pattern().matcher(out);
      if (m.find()) {
        out = m.replaceAll(rule.replacement());
      }
    }
    return out;
  }

  /** Đúng khi chuỗi chứa ít nhất một mẫu nhạy cảm. */
  public static boolean containsSensitive(String text) {
    return text != null && !text.equals(redact(text));
  }
}
