package vn.viettel.okrkpi.shared.paging;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import vn.viettel.okrkpi.shared.error.ProblemFactory;
import vn.viettel.okrkpi.shared.kernel.Clock;

/**
 * Auto-configuration phân trang con trỏ (baseline mục 13).
 *
 * <ul>
 *   <li>{@code okrkpi.paging.cursor-key-file}: tệp khoá ký con trỏ (tối thiểu 32 byte) do ESO hoặc
 *       CSI gắn từ OpenBao; mọi bản sao của service phải dùng cùng khoá. Không có tệp thì sinh khoá
 *       ngẫu nhiên trong tiến trình và ghi cảnh báo: chỉ đúng khi chạy một bản sao (dev, kiểm thử).
 *   <li>{@code okrkpi.paging.cursor-ttl}: thời hạn con trỏ, mặc định 24 giờ.
 * </ul>
 */
@AutoConfiguration(afterName = "vn.viettel.okrkpi.shared.error.ProblemAutoConfiguration")
public class PagingAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(PagingAutoConfiguration.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  static final Duration DEFAULT_TTL = Duration.ofHours(24);

  @Bean
  @ConditionalOnMissingBean
  public CursorCodec cursorCodec(Environment environment, ObjectProvider<Clock> clock) {
    Duration ttl = environment.getProperty("okrkpi.paging.cursor-ttl", Duration.class, DEFAULT_TTL);
    String file = environment.getProperty("okrkpi.paging.cursor-key-file");
    byte[] key;
    if (file != null && !file.isBlank()) {
      try {
        key = Files.readAllBytes(Path.of(file.trim()));
      } catch (IOException e) {
        throw new UncheckedIOException("Không đọc được tệp khoá ký con trỏ", e);
      }
    } else {
      log.warn(
          "Chưa đặt okrkpi.paging.cursor-key-file: dùng khoá ký con trỏ ngẫu nhiên trong tiến"
              + " trình, con trỏ không dùng được giữa các bản sao");
      key = new byte[32];
      RANDOM.nextBytes(key);
    }
    return new CursorCodec(key, ttl, clock.getIfAvailable(Clock::systemUtc));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnBean(ProblemFactory.class)
  public PagingProblemHandler pagingProblemHandler(ProblemFactory problemFactory) {
    return new PagingProblemHandler(problemFactory);
  }
}
