package vn.viettel.okrkpi.config.infrastructure.web;

import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Mô-đun của controller đang xử lý yêu cầu HTTP hiện tại, lấy từ gói {@code
 * vn.viettel.okrkpi.<mô-đun>..} giống {@code ErrorModuleResolver} của thư viện nền; ngoài yêu cầu
 * HTTP (consumer, tác vụ định kỳ) trả {@code null}.
 */
public final class RequestModule implements Supplier<String> {

  static final String ROOT = "vn.viettel.okrkpi.";

  @Override
  public String get() {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (attrs == null) {
      return null;
    }
    Object handler =
        attrs.getAttribute(
            HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    return handler instanceof HandlerMethod hm ? moduleOf(hm.getBeanType().getPackageName()) : null;
  }

  static String moduleOf(String packageName) {
    if (packageName == null || !packageName.startsWith(ROOT)) {
      return null;
    }
    String rest = packageName.substring(ROOT.length());
    int dot = rest.indexOf('.');
    return (dot < 0 ? rest : rest.substring(0, dot)).toUpperCase(Locale.ROOT);
  }
}
