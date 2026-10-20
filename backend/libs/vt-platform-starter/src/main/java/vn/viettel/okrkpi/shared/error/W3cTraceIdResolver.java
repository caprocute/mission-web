package vn.viettel.okrkpi.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Mã vết theo thứ tự: header W3C {@code traceparent} hợp lệ; giá trị {@code traceId}/{@code
 * trace_id} trong MDC; nếu không có thì sinh mã ngẫu nhiên. Kết quả ghi vào thuộc tính yêu cầu
 * {@link #REQUEST_ATTRIBUTE} để phản hồi và nhật ký của cùng một yêu cầu dùng chung một mã.
 */
public class W3cTraceIdResolver implements TraceIdResolver {

  /** Thuộc tính yêu cầu giữ mã vết đã xác định. */
  public static final String REQUEST_ATTRIBUTE = W3cTraceIdResolver.class.getName() + ".traceId";

  private static final Pattern TRACEPARENT =
      Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
  private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
  private static final String INVALID_TRACE_ID = "0".repeat(32);

  private final SecureRandom random = new SecureRandom();

  @Override
  public String resolve(HttpServletRequest request) {
    Object cached = request.getAttribute(REQUEST_ATTRIBUTE);
    if (cached instanceof String s) {
      return s;
    }
    String traceId = fromTraceparent(request.getHeader("traceparent"));
    if (traceId == null) {
      traceId = valid(MDC.get("traceId"));
    }
    if (traceId == null) {
      traceId = valid(MDC.get("trace_id"));
    }
    if (traceId == null) {
      traceId = generate();
    }
    request.setAttribute(REQUEST_ATTRIBUTE, traceId);
    return traceId;
  }

  static String fromTraceparent(String header) {
    if (header == null) {
      return null;
    }
    Matcher m = TRACEPARENT.matcher(header.trim().toLowerCase(Locale.ROOT));
    return m.matches() ? valid(m.group(1)) : null;
  }

  static String valid(String candidate) {
    if (candidate == null) {
      return null;
    }
    String v = candidate.trim().toLowerCase(Locale.ROOT);
    return TRACE_ID.matcher(v).matches() && !INVALID_TRACE_ID.equals(v) ? v : null;
  }

  private String generate() {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
