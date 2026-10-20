package vn.viettel.okrkpi.shared.observability;

import java.io.Serial;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.rewrite.RewritePolicy;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.ReadOnlyStringMap;
import org.apache.logging.log4j.util.SortedArrayStringMap;

/**
 * {@code RewritePolicy} của Log4j2 che dữ liệu nhạy cảm trước khi ghi (TL-01 "Che dữ liệu nhạy
 * cảm", lớp hai): thông điệp, giá trị MDC và thông điệp của ngoại lệ đính kèm (cả chuỗi nguyên
 * nhân) đi qua {@link Redactor}. Dùng trong cấu hình bằng phần tử {@code <MissionRedaction/>}.
 */
@Plugin(
    name = "MissionRedaction",
    category = Core.CATEGORY_NAME,
    elementType = "rewritePolicy",
    printObject = true)
public final class RedactingRewritePolicy implements RewritePolicy {

  private static final int MAX_CAUSE_DEPTH = 16;

  @PluginFactory
  public static RedactingRewritePolicy create() {
    return new RedactingRewritePolicy();
  }

  @Override
  public LogEvent rewrite(LogEvent source) {
    String message = source.getMessage() == null ? null : source.getMessage().getFormattedMessage();
    String redactedMessage = Redactor.redact(message);
    Throwable thrown = source.getThrown();
    Throwable redactedThrown = redactThrowable(thrown, 0);
    ReadOnlyStringMap context = source.getContextData();
    SortedArrayStringMap redactedContext = redactContext(context);

    boolean messageChanged = redactedMessage != null && !redactedMessage.equals(message);
    if (!messageChanged && redactedThrown == thrown && redactedContext == null) {
      return source;
    }
    Log4jLogEvent.Builder b = new Log4jLogEvent.Builder(source);
    if (messageChanged) {
      b.setMessage(new SimpleMessage(redactedMessage));
    }
    if (redactedThrown != thrown) {
      b.setThrown(redactedThrown);
      b.setThrownProxy(null);
    }
    if (redactedContext != null) {
      b.setContextData(redactedContext);
    }
    return b.build();
  }

  private static SortedArrayStringMap redactContext(ReadOnlyStringMap context) {
    if (context == null || context.isEmpty()) {
      return null;
    }
    boolean[] changed = {false};
    SortedArrayStringMap copy = new SortedArrayStringMap(context.size());
    context.forEach(
        (key, value) -> {
          String v = value == null ? null : String.valueOf(value);
          String r = Redactor.redact(v);
          if (r != null && !r.equals(v)) {
            changed[0] = true;
          }
          copy.putValue(key, r);
        });
    return changed[0] ? copy : null;
  }

  /** Bản sao ngoại lệ với thông điệp đã che; giữ nguyên ngoại lệ khi không có gì phải che. */
  static Throwable redactThrowable(Throwable t, int depth) {
    if (t == null || depth >= MAX_CAUSE_DEPTH) {
      return t;
    }
    Throwable cause = t.getCause() == t ? null : t.getCause();
    Throwable redactedCause = redactThrowable(cause, depth + 1);
    String msg = t.getMessage();
    String redacted = Redactor.redact(msg);
    boolean msgChanged = redacted != null && !redacted.equals(msg);
    if (!msgChanged && redactedCause == cause) {
      return t;
    }
    RedactedThrowable copy = new RedactedThrowable(t.getClass().getName(), redacted, redactedCause);
    copy.setStackTrace(t.getStackTrace());
    return copy;
  }

  /** Ngoại lệ thay thế mang tên lớp gốc trong thông điệp và dấu vết ngăn xếp gốc. */
  static final class RedactedThrowable extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final String originalClassName;

    RedactedThrowable(String originalClassName, String message, Throwable cause) {
      super(message, cause, false, true);
      this.originalClassName = originalClassName;
    }

    String originalClassName() {
      return originalClassName;
    }

    @Override
    public String toString() {
      String m = getLocalizedMessage();
      return m == null ? originalClassName : originalClassName + ": " + m;
    }
  }
}
