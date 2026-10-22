package vn.viettel.okrkpi.search.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.search.application.InvalidCursorException;
import vn.viettel.okrkpi.search.application.SearchPage;
import vn.viettel.okrkpi.search.application.SearchQuery;
import vn.viettel.okrkpi.search.application.SearchService;
import vn.viettel.okrkpi.search.domain.DocType;
import vn.viettel.okrkpi.search.domain.Snippet;
import vn.viettel.okrkpi.search.infrastructure.web.api.SearchApi;
import vn.viettel.okrkpi.search.infrastructure.web.model.SearchResult;
import vn.viettel.okrkpi.search.infrastructure.web.model.SearchResultHighlightsInner;
import vn.viettel.okrkpi.search.infrastructure.web.model.SearchResultPage;
import vn.viettel.okrkpi.search.infrastructure.web.model.SearchResultType;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;

/**
 * {@code GET /api/v1/search} ({@code searchFullText}, DV-16-02) theo giao diện sinh từ {@code
 * platform.yaml}. Tenant lấy từ JWT, không bao giờ từ tham số. Nhật ký chỉ ghi băm SHA-256 của từ
 * khoá, vì từ khoá có thể chứa tên người.
 *
 * <p>Tham số {@code period} chưa lọc được: sự kiện nhiệm vụ chưa mang kỳ đánh giá nên chỉ mục không
 * có trường kỳ (câu hỏi mở ở wiki/14); tham số được nhận và bỏ qua.
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController implements SearchApi {

  private static final Logger LOG = LoggerFactory.getLogger(SearchController.class);

  private final SearchService service;
  private final ProblemFactory problems;

  public SearchController(SearchService service, ProblemFactory problems) {
    this.service = service;
    this.problems = problems;
  }

  /**
   * Giao diện sinh mang {@code @Validated}, nên tham số sai ({@code q} ngoài 2..200 ký tự, {@code
   * limit} ngoài 1..200) ném {@link ConstraintViolationException}; với tham số truy vấn, quy ước
   * mục 13 baseline và hợp đồng là 400.
   */
  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ProblemDetail> invalidParameter(
      ConstraintViolationException e, HttpServletRequest request) {
    List<ProblemFieldError> errors = new java.util.ArrayList<>();
    if (e.getConstraintViolations() != null) {
      e.getConstraintViolations()
          .forEach(
              v -> {
                String path = v.getPropertyPath().toString();
                errors.add(
                    ProblemFieldError.of(
                        path.substring(path.lastIndexOf('.') + 1), v.getMessage()));
              });
    }
    return ProblemFactory.toResponse(problems.standard(400, null, errors, request), null);
  }

  @Override
  public ResponseEntity<SearchResultPage> searchFullText(
      String q, SearchResultType type, UUID period, UUID unit, Integer limit, String cursor) {
    Jwt jwt = currentJwt();
    String tenant = jwt == null ? null : jwt.getClaimAsString("tenant_id");
    if (tenant == null || q == null || q.strip().length() < 2) {
      throw new ErrorResponseException(HttpStatus.BAD_REQUEST);
    }
    SearchQuery query;
    try {
      query =
          new SearchQuery(
              tenant,
              q.strip(),
              type == null ? null : DocType.valueOf(type.getValue()),
              unit == null ? null : unit.toString(),
              limit == null ? 20 : limit);
    } catch (IllegalArgumentException e) {
      throw new ErrorResponseException(HttpStatus.BAD_REQUEST);
    }
    String binding =
        String.join(
            "|",
            tenant,
            jwt.getSubject(),
            query.text(),
            String.valueOf(type),
            String.valueOf(unit));
    SearchPage page;
    try {
      page = service.search(query, cursor, binding);
    } catch (SecurityException e) {
      throw new AccessDeniedException("search");
    } catch (InvalidCursorException e) {
      throw new ErrorResponseException(HttpStatus.BAD_REQUEST);
    }
    LOG.debug("Tìm kiếm q#{} trả {} kết quả", sha256(query.text()), page.items().size());
    SearchResultPage body =
        new SearchResultPage(
            page.items().stream().map(SearchController::result).toList(), page.nextCursor());
    return ResponseEntity.ok(body);
  }

  private static SearchResult result(SearchPage.Item i) {
    SearchResult r =
        new SearchResult(
            SearchResultType.fromValue(i.type().name()),
            UUID.fromString(i.id()),
            i.title(),
            BigDecimal.valueOf(i.score()).setScale(4, RoundingMode.HALF_UP));
    r.setCode(i.code());
    r.setStatus(i.status());
    r.setDeepLink(i.deepLink());
    Snippet s = i.snippet();
    if (s != null && !s.text().isEmpty()) {
      r.setSnippet(s.text());
      List<SearchResultHighlightsInner> hs =
          s.highlights().stream()
              .map(h -> new SearchResultHighlightsInner(h.start(), h.length()))
              .toList();
      r.setHighlights(hs);
    }
    return r;
  }

  private static Jwt currentJwt() {
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    return auth != null && auth.getPrincipal() instanceof Jwt jwt ? jwt : null;
  }

  static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)))
          .substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
