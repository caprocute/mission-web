package vn.viettel.okrkpi.shared.infrastructure.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client {@code GET /api/v1/workflow/inbox} ({@code listWorkflowInbox} của {@code platform.yaml},
 * SVC-06) bằng token của chính người dùng: hộp việc tính theo người gọi, vai trò và đơn vị trong
 * ngữ cảnh, nên không gọi bằng token dịch vụ. Lỗi bất kỳ trả rỗng để bên gọi dùng nguồn dự phòng.
 */
public class WorkflowInboxClient {

  /** Trần số việc đọc cho một lần (năm trang 200 dòng). */
  static final int MAX_ITEMS = 1000;

  static final int PAGE = 200;

  private static final Logger LOG = LoggerFactory.getLogger(WorkflowInboxClient.class);

  private static final ParameterizedTypeReference<Map<String, Object>> MAP =
      new ParameterizedTypeReference<>() {};

  private final RestClient http;

  public WorkflowInboxClient(RestClient.Builder builder, String baseUrl) {
    this.http = builder.baseUrl(Objects.requireNonNull(baseUrl, "baseUrl")).build();
  }

  /** {@code businessId} của các việc trong hộp việc thuộc loại {@code businessType}. */
  public Optional<List<String>> businessIds(String bearer, String businessType) {
    Objects.requireNonNull(bearer, "bearer");
    List<String> out = new ArrayList<>();
    String cursor = null;
    try {
      do {
        String c = cursor;
        Map<String, Object> page =
            http.get()
                .uri(
                    u -> {
                      u.path("/api/v1/workflow/inbox")
                          .queryParam("assignee", "me")
                          .queryParam("businessType", businessType)
                          .queryParam("limit", PAGE);
                      if (c != null) {
                        u.queryParam("cursor", c);
                      }
                      return u.build();
                    })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .retrieve()
                .body(MAP);
        if (page == null || !(page.get("items") instanceof List<?> items)) {
          return Optional.empty();
        }
        for (Object item : items) {
          if (item instanceof Map<?, ?> m && m.get("businessId") instanceof String id) {
            out.add(id);
          }
        }
        cursor = page.get("nextCursor") instanceof String next ? next : null;
      } while (cursor != null && out.size() < MAX_ITEMS);
      return Optional.of(out);
    } catch (RestClientException e) {
      LOG.warn("Không đọc được hộp việc workflow-service: {}", e.getMessage());
      return Optional.empty();
    }
  }
}
