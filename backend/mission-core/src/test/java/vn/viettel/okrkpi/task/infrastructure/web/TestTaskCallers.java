package vn.viettel.okrkpi.task.infrastructure.web;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;

/**
 * Người gọi dùng chung cho kiểm thử tích hợp MOD-02. Lớp cấu hình kiểm thử trong gói này được quét
 * vào mọi ngữ cảnh, nên chỉ giữ một bean {@link TaskCaller}; từng kiểm thử đặt {@link #current}.
 */
@TestConfiguration
public class TestTaskCallers {

  public static final UUID DEFAULT_PERSON = UUID.fromString("01927a02-0000-7000-8000-00000000a213");

  static volatile TaskActor current = new TaskActor(DEFAULT_PERSON, Set.of("manager"));

  /** Mức và thời điểm xác thực của phiên, cho kiểm thử xác thực bước lên (CORE-02-09). */
  static volatile String acr;

  static volatile Long authTime;

  static void reset() {
    current = new TaskActor(DEFAULT_PERSON, Set.of("manager"));
    acr = null;
    authTime = null;
  }

  @Bean
  TaskCaller testTaskCaller() {
    return new TaskCaller() {
      @Override
      public Optional<TaskActor> current() {
        return Optional.of(current);
      }

      @Override
      public Optional<String> acr() {
        return Optional.ofNullable(acr);
      }

      @Override
      public Optional<Long> authTime() {
        return Optional.ofNullable(authTime);
      }
    };
  }
}
