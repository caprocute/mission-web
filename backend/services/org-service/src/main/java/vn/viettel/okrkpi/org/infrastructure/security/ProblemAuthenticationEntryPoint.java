package vn.viettel.okrkpi.org.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** 401 theo RFC 9457, không lộ chi tiết lỗi token. */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

  @Override
  public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
      throws IOException {
    response.setStatus(401);
    response.setHeader("WWW-Authenticate", "Bearer");
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write("{\"type\":\"https://okrkpi.vn/problems/unauthorized\","
        + "\"title\":\"Phiên đăng nhập không hợp lệ hoặc đã hết hạn\",\"status\":401,"
        + "\"code\":\"COMMON-401-UNAUTHENTICATED\"}");
  }
}
