package vn.viettel.okrkpi.contract.test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.application.OpenApiConformanceEngine;
import vn.viettel.okrkpi.contract.test.domain.ConformanceReport;
import vn.viettel.okrkpi.contract.test.domain.ContractViolation;

/**
 * Lớp kiểm thử nền kiểm chứng sự tuân thủ hợp đồng OpenAPI 3.1 của một mô-đun hoặc dịch vụ (ADR-32,
 * NEN-006).
 *
 * <p>Mọi mô-đun trong {@code mission-core} và microservice Java kế thừa lớp này để:
 *
 * <ol>
 *   <li>Lấy tài liệu OpenAPI thực tế lúc chạy từ {@code /v3/api-docs} (springdoc).
 *   <li>So khớp bằng {@code openapi-diff} với {@code contracts/openapi/modules/<module>.yaml}.
 *   <li>Kiểm các quy ước mục 13 baseline ({@code /api/v1}, {@code Idempotency-Key}, {@code
 *       If-Match}, {@code limit <= 200}, {@code application/problem+json}).
 *   <li>So khớp phản hồi JSON thực tế từ {@link MockMvc} với schema và {@code examples} trong hợp
 *       đồng.
 * </ol>
 */
public abstract class ContractConformanceTest {

  /** Mô-đun hợp đồng OpenAPI cần đối chiếu. */
  protected abstract ContractModule contractModule();

  /** Mã dịch vụ {@code x-mission.owner} đang kiểm thử (mặc định {@code SVC-05}). */
  protected String ownerServiceCode() {
    return "SVC-05";
  }

  /** Đường dẫn lấy tài liệu OpenAPI thực tế từ springdoc. */
  protected String apiDocsEndpoint() {
    return "/v3/api-docs";
  }

  /**
   * Gọi {@code /v3/api-docs} qua {@link MockMvc} và trả về báo cáo đối chiếu với hợp đồng chuẩn.
   */
  protected ConformanceReport evaluateOpenApiDocs(MockMvc mockMvc) throws Exception {
    Objects.requireNonNull(mockMvc, "mockMvc must not be null");
    MvcResult apiDocsResult =
        mockMvc.perform(get(apiDocsEndpoint())).andExpect(status().isOk()).andReturn();
    String actualJson = apiDocsResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
    return OpenApiConformanceEngine.verifyConformance(
        contractModule(), ownerServiceCode(), actualJson);
  }

  /**
   * Khẳng định {@code /v3/api-docs} thực tế tuân thủ hoàn toàn hợp đồng chuẩn và các {@code
   * examples} trong hợp đồng khớp với schema.
   */
  protected void assertOpenApiConformance(MockMvc mockMvc) throws Exception {
    ConformanceReport report = evaluateOpenApiDocs(mockMvc);
    report.assertCompliant();

    OpenAPI expected = OpenApiConformanceEngine.loadExpectedSpec(contractModule());
    List<ContractViolation> exampleViolations =
        OpenApiConformanceEngine.validateContractExamples(expected, ownerServiceCode());
    if (!exampleViolations.isEmpty()) {
      new ConformanceReport(contractModule(), ownerServiceCode(), exampleViolations)
          .assertCompliant();
    }
  }

  /**
   * Khẳng định phản hồi thực tế từ {@link MockMvc} khớp với schema phản hồi đã khai báo trong hợp
   * đồng OpenAPI.
   */
  protected void assertResponseConforms(
      MvcResult result, String pathTemplate, PathItem.HttpMethod method) throws Exception {
    Objects.requireNonNull(result, "result must not be null");
    OpenAPI expected = OpenApiConformanceEngine.loadExpectedSpec(contractModule());
    int statusCode = result.getResponse().getStatus();
    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    List<ContractViolation> violations =
        OpenApiConformanceEngine.validateResponseJson(
            expected, pathTemplate, method, statusCode, body);
    if (!violations.isEmpty()) {
      new ConformanceReport(contractModule(), ownerServiceCode(), violations).assertCompliant();
    }
  }
}
