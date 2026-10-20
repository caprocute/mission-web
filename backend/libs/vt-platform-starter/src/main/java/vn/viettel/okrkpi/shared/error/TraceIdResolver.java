package vn.viettel.okrkpi.shared.error;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Lấy mã vết ({@code traceId}, 32 ký tự hex theo W3C Trace Context) của yêu cầu để đặt vào Problem
 * Details và nhật ký. Bản mặc định {@link W3cTraceIdResolver} đọc header {@code traceparent};
 * NEN-016 thay bằng bean đọc span OpenTelemetry hiện hành.
 */
@FunctionalInterface
public interface TraceIdResolver {

  /** Mã vết của yêu cầu, không rỗng. */
  String resolve(HttpServletRequest request);
}
