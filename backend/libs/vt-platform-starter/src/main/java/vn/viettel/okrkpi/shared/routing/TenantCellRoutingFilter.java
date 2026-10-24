package vn.viettel.okrkpi.shared.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Bộ lọc HTTP kiểm tra định tuyến cell của khách thuê hiện hành trước khi đi vào tầng nghiệp vụ
 * (ADR-07, TL-01 §4).
 *
 * <ul>
 *   <li>Nếu khách thuê đang ở trạng thái {@code DANG_CHUYEN_CELL} hoặc {@code DA_CHAM_DUT}: trả về
 *       {@code 503 Service Unavailable} chuẩn RFC 9457 (kèm {@code Retry-After}).
 *   <li>Nếu khách thuê đang {@code TAM_NGUNG}: yêu cầu đọc ({@code GET}, {@code HEAD}, {@code
 *       OPTIONS}) đi tiếp vì dữ liệu được giữ nguyên; lệnh ghi bị từ chối {@code 403} kèm {@code
 *       tenantStatus = TAM_NGUNG} để giao diện hiện thông báo tạm ngừng (TL-04 mục 11.4).
 *   <li>Nếu khách thuê thuộc một cell khác với {@code localCellCode} của dịch vụ: trả về {@code 421
 *       Misdirected Request} với mã lỗi chuẩn {@code REGISTRY-421-TENANT-MOVED} để cổng API APISIX
 *       làm mới bảng định tuyến và chuyển tiếp sang đúng cell.
 * </ul>
 */
public final class TenantCellRoutingFilter extends OncePerRequestFilter {

  public static final String TENANT_MOVED_CODE = "REGISTRY-421-TENANT-MOVED";
  public static final String TARGET_CELL_HEADER = "X-Target-Cell";
  private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

  private final TenantDirectory directory;
  private final String localCellCode;
  private final ProblemFactory problemFactory;
  private final ObjectMapper objectMapper;

  public TenantCellRoutingFilter(
      TenantDirectory directory,
      String localCellCode,
      ProblemFactory problemFactory,
      ObjectMapper objectMapper) {
    this.directory = Objects.requireNonNull(directory, "directory must not be null");
    this.localCellCode = Objects.requireNonNull(localCellCode, "localCellCode must not be null");
    this.problemFactory = Objects.requireNonNull(problemFactory, "problemFactory must not be null");
    this.objectMapper =
        Objects.requireNonNull(objectMapper, "objectMapper must not be null")
            .copy()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Optional<TenantId> currentTenant = TenantContext.current();
    if (currentTenant.isEmpty()) {
      filterChain.doFilter(request, response);
      return;
    }

    TenantId tenantId = currentTenant.get();
    Optional<TenantCellRoute> routeOpt = directory.findByTenantId(tenantId.value());
    if (routeOpt.isEmpty()) {
      filterChain.doFilter(request, response);
      return;
    }

    TenantCellRoute route = routeOpt.get();
    if (route.isUnavailableStatus()) {
      response.setHeader("Retry-After", "30");
      ProblemDetail problem =
          problemFactory.standard(
              HttpServletResponse.SC_SERVICE_UNAVAILABLE,
              "Don vi '"
                  + route.tenantCode()
                  + "' dang trong qua trinh chuyen doi ha tang hoac tam ngung ("
                  + route.status()
                  + "). Vui long thu lai sau.",
              List.of(),
              request);
      problem.setProperty("tenantCode", route.tenantCode());
      problem.setProperty("tenantStatus", route.status());
      writeProblem(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, problem);
      return;
    }

    if (route.isSuspended() && !READ_METHODS.contains(request.getMethod())) {
      ProblemDetail problem =
          problemFactory.standard(
              HttpServletResponse.SC_FORBIDDEN,
              "Đơn vị của bạn đang tạm ngừng sử dụng hệ thống. Bạn vẫn xem được dữ liệu nhưng chưa"
                  + " thể cập nhật cho tới khi đơn vị được mở lại.",
              List.of(),
              request);
      problem.setProperty("tenantCode", route.tenantCode());
      problem.setProperty("tenantStatus", route.status());
      writeProblem(response, HttpServletResponse.SC_FORBIDDEN, problem);
      return;
    }

    if (!localCellCode.equalsIgnoreCase(route.cellCode())) {
      response.setHeader(TARGET_CELL_HEADER, route.cellCode());
      ProblemDetail problem =
          problemFactory.fromCode(
              problemFactory.catalog().require(TENANT_MOVED_CODE),
              "Don vi '"
                  + route.tenantCode()
                  + "' thuoc cell '"
                  + route.cellCode()
                  + "', khong phai cell hien tai '"
                  + localCellCode
                  + "'.",
              List.of(),
              null,
              request);
      problem.setProperty("tenantCode", route.tenantCode());
      problem.setProperty("expectedCell", route.cellCode());
      problem.setProperty("actualCell", localCellCode);
      if (route.cellApiBaseUrl() != null) {
        problem.setProperty("targetCellUrl", route.cellApiBaseUrl());
      }
      writeProblem(response, 421, problem);
      return;
    }

    filterChain.doFilter(request, response);
  }

  private void writeProblem(HttpServletResponse response, int status, ProblemDetail problem)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
