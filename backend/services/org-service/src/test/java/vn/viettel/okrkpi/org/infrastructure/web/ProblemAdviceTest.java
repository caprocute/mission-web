package vn.viettel.okrkpi.org.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/** QA-13 (ASVS V13.1.5): thân multipart hỏng là lỗi yêu cầu 400, tệp quá lớn giữ 413, không rơi vào 500. */
class ProblemAdviceTest {

  private final ProblemAdvice advice = new ProblemAdvice();
  private final MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/org/imports");

  @Test
  void multipartHongTra400() {
    ResponseEntity<ProblemDetail> r = advice.multipart(new MultipartException("Failed to parse multipart servlet request"),
        req);
    assertThat(r.getStatusCode().value()).isEqualTo(400);
    assertThat(r.getBody().getProperties()).containsEntry("code", "COMMON-400-BAD-REQUEST");
    assertThat(r.getBody().getDetail()).doesNotContain("parse", "multipart servlet");
  }

  @Test
  void tepQuaLonGiu413() {
    ResponseEntity<ProblemDetail> r = advice.multipart(new MaxUploadSizeExceededException(10 * 1024 * 1024), req);
    assertThat(r.getStatusCode().value()).isEqualTo(413);
    assertThat(r.getBody().getProperties()).containsEntry("code", "COMMON-413-PAYLOAD-TOO-LARGE");
  }
}
