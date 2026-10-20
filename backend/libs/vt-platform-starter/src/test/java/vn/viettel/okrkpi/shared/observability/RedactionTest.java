package vn.viettel.okrkpi.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.Test;

/** TL-01 "Che dữ liệu nhạy cảm", lớp hai: mẫu nhạy cảm bị che trước khi ghi. */
class RedactionTest {

  private static final String JWT =
      "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTYifQ.SflKxwRJSMeKKF2QT4fwpMeJf36P";

  @Test
  void cheJwtTokenCookie() {
    assertThat(Redactor.redact("token " + JWT + " het")).isEqualTo("token [REDACTED:JWT] het");
    assertThat(Redactor.redact("Authorization: Bearer abcdefgh12345678"))
        .isEqualTo("Authorization: Bearer [REDACTED:TOKEN]");
    assertThat(Redactor.redact("authorization=basic dXNlcjpwYXNzd29yZA=="))
        .isEqualTo("authorization=basic [REDACTED:TOKEN]");
    assertThat(Redactor.redact("Cookie: SESSION=abc; XSRF=def"))
        .isEqualTo("Cookie: [REDACTED:TOKEN]");
  }

  @Test
  void cheSoDinhDanhDienThoaiEmail() {
    assertThat(Redactor.redact("cccd 001203004567.")).isEqualTo("cccd [REDACTED:ID].");
    assertThat(Redactor.redact("goi 0912345678 hoac +84987654321"))
        .isEqualTo("goi [REDACTED:PHONE] hoac [REDACTED:PHONE]");
    assertThat(Redactor.redact("thu nguyen.van-a+x@so-noi-vu.gov.vn"))
        .isEqualTo("thu [REDACTED:EMAIL]");
  }

  @Test
  void khongCheNhamMaVetUuidVaSoThuong() {
    String s =
        "trace 4bf92f3577b34da6a3ce929d0e0e4736 id 0192f5a0-7b1c-7cde-8f00-012345678901"
            + " so 12345 nam 2026 ma 1234567890123";
    assertThat(Redactor.redact(s)).isSameAs(s);
    assertThat(Redactor.containsSensitive(s)).isFalse();
    assertThat(Redactor.containsSensitive("0912345678")).isTrue();
    assertThat(Redactor.redact(null)).isNull();
    assertThat(Redactor.redact("")).isEmpty();
  }

  @Test
  void rewritePolicyCheThongDiepMdcVaNgoaiLe() {
    SortedArrayStringMap mdc = new SortedArrayStringMap();
    mdc.putValue("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");
    mdc.putValue("user", "nguyen.van.a@example.vn");
    RuntimeException cause = new RuntimeException("cccd=001203004567");
    LogEvent source =
        Log4jLogEvent.newBuilder()
            .setLoggerName("thu")
            .setLevel(Level.INFO)
            .setMessage(new SimpleMessage("dang nhap voi " + JWT))
            .setContextData(mdc)
            .setThrown(new IllegalStateException("loi khi goi", cause))
            .build();

    LogEvent out = RedactingRewritePolicy.create().rewrite(source);

    assertThat(out.getMessage().getFormattedMessage()).isEqualTo("dang nhap voi [REDACTED:JWT]");
    assertThat(out.getContextData().<String>getValue("user")).isEqualTo("[REDACTED:EMAIL]");
    assertThat(out.getContextData().<String>getValue("trace_id"))
        .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    Throwable t = out.getThrown();
    assertThat(t.getMessage()).isEqualTo("loi khi goi");
    assertThat(t.getCause().getMessage()).isEqualTo("cccd=[REDACTED:ID]");
    assertThat(t.getCause().toString()).startsWith("java.lang.RuntimeException: ");
    assertThat(t.toString()).startsWith("java.lang.IllegalStateException");
    assertThat(t.getStackTrace()).isEqualTo(source.getThrown().getStackTrace());
  }

  @Test
  void rewritePolicyGiuNguyenSuKienKhongCoGiPhaiChe() {
    LogEvent source =
        Log4jLogEvent.newBuilder()
            .setLoggerName("thu")
            .setLevel(Level.INFO)
            .setMessage(new SimpleMessage("binh thuong"))
            .setThrown(new IllegalStateException("khong co gi"))
            .build();
    assertThat(RedactingRewritePolicy.create().rewrite(source)).isSameAs(source);
  }
}
