package vn.viettel.okrkpi.archfixture.bad;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

/** Mẫu vi phạm cho kiểm thử luật kiến trúc: executor không giới hạn, bộ lọc ghi yêu cầu. */
public class UnboundedJob {

  ExecutorService cached() {
    return Executors.newCachedThreadPool();
  }

  ExecutorService fixed() {
    return Executors.newFixedThreadPool(4);
  }

  Object requestLogging() {
    return new CommonsRequestLoggingFilter();
  }
}
