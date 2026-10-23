package vn.viettel.okrkpi.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import vn.viettel.okrkpi.shared.error.ProblemFactory;

/** 401 và 403 của chuỗi bảo mật dưới dạng Problem Details, không lộ chi tiết lỗi token. */
final class ProblemSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final ProblemFactory problems;
  private final ObjectMapper json;

  ProblemSecurityHandler(ProblemFactory problems, ObjectMapper json) {
    this.problems = problems;
    this.json = json;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
      throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    write(request, response, HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
      throws IOException {
    write(request, response, HttpServletResponse.SC_FORBIDDEN);
  }

  private void write(HttpServletRequest request, HttpServletResponse response, int status)
      throws IOException {
    ProblemDetail pd = problems.standard(status, null, List.of(), request);
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    json.writeValue(response.getOutputStream(), pd);
  }
}
