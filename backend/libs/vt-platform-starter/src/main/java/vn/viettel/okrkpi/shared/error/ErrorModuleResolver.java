package vn.viettel.okrkpi.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Xác định phần MODULE của mã lỗi sinh tự động (hiện chỉ {@code <MODULE>-409-VERSION-CONFLICT}):
 *
 * <ol>
 *   <li>gói của controller đang xử lý: {@code vn.viettel.okrkpi.<module>..} (TL-01 "Cấu trúc gói
 *       Java theo module") nếu {@code <MODULE>} viết hoa có trong danh mục;
 *   <li>thuộc tính {@code okrkpi.problem.module};
 *   <li>{@code COMMON}.
 * </ol>
 */
public class ErrorModuleResolver {

  static final String ROOT_PACKAGE = "vn.viettel.okrkpi.";
  static final String DEFAULT_MODULE = "COMMON";

  private final ErrorCatalog catalog;
  private final String configuredModule;

  public ErrorModuleResolver(ErrorCatalog catalog, String configuredModule) {
    this.catalog = catalog;
    this.configuredModule =
        configuredModule == null || configuredModule.isBlank()
            ? null
            : configuredModule.trim().toUpperCase(Locale.ROOT);
  }

  public String resolve(HttpServletRequest request) {
    Object handler =
        request == null
            ? null
            : request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
    if (handler instanceof HandlerMethod hm) {
      String fromPackage = fromPackage(hm.getBeanType().getPackageName());
      if (fromPackage != null) {
        return fromPackage;
      }
    }
    return configuredModule != null ? configuredModule : DEFAULT_MODULE;
  }

  String fromPackage(String packageName) {
    if (packageName == null || !packageName.startsWith(ROOT_PACKAGE)) {
      return null;
    }
    String rest = packageName.substring(ROOT_PACKAGE.length());
    int dot = rest.indexOf('.');
    String segment = (dot < 0 ? rest : rest.substring(0, dot)).toUpperCase(Locale.ROOT);
    return catalog.modules().contains(segment) ? segment : null;
  }
}
