package vn.viettel.okrkpi.org.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Kiểm thử hợp đồng: đối chiếu từng cặp yêu cầu/phản hồi của MockMvc với {@code contracts/openapi/modules/org.yaml}
 * (đường dẫn, tham số, mã trạng thái có khai báo, lược đồ thân phản hồi).
 */
public final class ContractValidator {

  private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
      .createForSpecificationUrl(ContractValidator.class.getResource("/contract/modules/org.yaml").toString())
      .withResolveCombinators(true)
      .withLevelResolver(LevelResolver.create()
          // Phần mở rộng x-mission và tiêu đề bảo mật do Spring Security kiểm, không thuộc phạm vi so khớp lược đồ.
          .withLevel("validation.request.security.missing", ValidationReport.Level.IGNORE)
          .withLevel("validation.request.security.invalid", ValidationReport.Level.IGNORE)
          // Bộ kiểm mặc định coi additionalProperties = false nên luôn báo sai với allOf (OrgUnit = OrgUnitInput +
          // phần đọc); thuộc tính bắt buộc, kiểu và enum của từng nhánh vẫn được kiểm.
          .withLevel("validation.schema.additionalProperties", ValidationReport.Level.IGNORE)
          .build())
      .build();

  private ContractValidator() {
  }

  public static ValidationReport validate(MvcResult result) {
    MockHttpServletRequest req = result.getRequest();
    MockHttpServletResponse res = result.getResponse();
    SimpleRequest.Builder rb = new SimpleRequest.Builder(Request.Method.valueOf(req.getMethod()),
        req.getRequestURI());
    for (String name : Collections.list(req.getHeaderNames())) {
      rb.withHeader(name, Collections.list(req.getHeaders(name)));
    }
    req.getParameterMap().forEach((k, v) -> rb.withQueryParam(k, v));
    if (req.getContentAsByteArray() != null && req.getContentAsByteArray().length > 0) {
      rb.withBody(new String(req.getContentAsByteArray(), StandardCharsets.UTF_8));
    }
    SimpleResponse.Builder sb = SimpleResponse.Builder.status(res.getStatus());
    for (String name : res.getHeaderNames()) {
      sb.withHeader(name, res.getHeaders(name));
    }
    try {
      String body = res.getContentAsString(StandardCharsets.UTF_8);
      if (!body.isEmpty()) {
        sb.withBody(body);
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    ValidationReport report = VALIDATOR.validate(rb.build(), sb.build());
    if (res.getStatus() >= 400) {
      // Ca âm cố ý gửi yêu cầu sai hợp đồng (thiếu If-Match, sai kiểu...): chỉ so phản hồi.
      return ValidationReport.from(report.getMessages().stream()
          .filter(m -> !m.getKey().startsWith("validation.request")).toList());
    }
    return report;
  }
}
