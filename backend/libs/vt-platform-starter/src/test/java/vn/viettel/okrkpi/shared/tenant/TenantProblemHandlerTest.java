package vn.viettel.okrkpi.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import vn.viettel.okrkpi.shared.error.ErrorCatalog;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.error.W3cTraceIdResolver;

class TenantProblemHandlerTest {

  @Test
  void missingTenantIsForbiddenProblemNotServerError() {
    TenantProblemHandler handler =
        new TenantProblemHandler(
            new ProblemFactory(new W3cTraceIdResolver(), ErrorCatalog.standard()));
    ResponseEntity<ProblemDetail> r =
        handler.handleMissingTenant(
            new MissingTenantException(), new MockHttpServletRequest("GET", "/api/v1/okr/sets"));
    assertThat(r.getStatusCode().value()).isEqualTo(403);
    assertThat(r.getBody().getDetail()).isEqualTo(TenantProblemHandler.DETAIL);
  }
}
