package vn.viettel.okrkpi.integ.infrastructure.hrm;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hồ sơ kết nối HRM, ERP, HIS, quản lý trường học theo mã kết nối (DV-13-08): REST kéo nhân sự theo
 * con trỏ, tệp lô SFTP, đọc chỉ số; ánh xạ trường và giá trị khai báo ở đây chứ không viết cứng cho
 * một hãng. Địa chỉ, bí mật, bật tắt theo tenant vẫn nằm ở {@code integ.connector}.
 *
 * <p>Khoá của {@code fields}: {@code externalId}, {@code fullName}, {@code nationalId}, {@code
 * birthDate}, {@code employmentStatus}, {@code partyMember}, {@code orgUnit}, {@code jobTitle},
 * {@code kind}, {@code head}, {@code effectiveFrom}; giá trị là con trỏ JSON (REST, ví dụ {@code
 * /hoTen}) hoặc tên cột (tệp lô). {@code values} đổi giá trị nguồn sang giá trị chuẩn, ví dụ {@code
 * employmentStatus: {"Đang làm việc": DANG_CONG_TAC}}.
 */
@ConfigurationProperties("okrkpi.integ.hrm")
public record HrmProperties(Boolean enabled, Map<String, Profile> connectors) {

  public HrmProperties {
    connectors = connectors == null ? Map.of() : Map.copyOf(connectors);
  }

  /** Hồ sơ một mã kết nối. */
  public record Profile(
      Rest rest,
      Batch batch,
      Indicator indicator,
      Map<String, String> fields,
      Map<String, Map<String, String>> values,
      List<String> required,
      Duration period,
      Duration requestTimeout,
      Integer batchSize,
      Trigger trigger,
      Party party) {

    public Profile {
      trigger = trigger == null ? Trigger.POLL : trigger;
      fields = fields == null ? Map.of() : Map.copyOf(fields);
      values = values == null ? Map.of() : Map.copyOf(values);
      required = required == null ? List.of("externalId", "fullName") : List.copyOf(required);
      period = period == null ? Duration.ofMinutes(15) : period;
      requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
      batchSize = batchSize == null || batchSize < 1 ? 500 : batchSize;
    }
  }

  /**
   * Hồ sơ là danh sách đảng viên (DV-13-07, CSDL đảng viên qua hạ tầng riêng khối Đảng): tổ chức
   * đảng ({@code orgUnit}) và chức vụ đảng ({@code jobTitle}) quy đổi qua {@code mapping_xref} như
   * đơn vị, chức danh; dòng không ghi đảng tịch là đảng viên, người đã ra khỏi Đảng ({@code
   * partyMember} quy đổi thành sai) không mang phân công sinh hoạt; nguồn không ghi trạng thái công
   * tác thì để trống thay vì mặc định. {@code outbound} là chiều ra "theo quy định", tắt cho tới
   * khi có văn bản xác định dữ liệu được gửi (wiki 14).
   */
  public record Party(Boolean outbound) {

    public Party {
      outbound = outbound != null && outbound;
    }
  }

  /**
   * Cách kéo nhân sự: {@code POLL} kéo một trang mỗi chu kỳ {@code period}; {@code RUN} chỉ kéo
   * trong lần đồng bộ (DV-13-06: lịch đêm {@code connector.schedule_cron}, "Đồng bộ ngay", chạy lại
   * thư chết), mỗi lần kéo hết trang từ con trỏ, như CSDL quốc gia CBCCVC qua NDXP.
   */
  public enum Trigger {
    POLL,
    RUN
  }

  /** Kéo nhân sự qua REST: {@code GET <endpoint><path>?<cursorParam>=..&<limitParam>=..}. */
  public record Rest(
      String path,
      String cursorParam,
      String limitParam,
      Integer pageSize,
      String initialCursor,
      String items,
      String nextCursor,
      String updatedAt) {

    public Rest {
      path = path == null ? "/api/persons" : path;
      cursorParam = cursorParam == null ? "updatedSince" : cursorParam;
      limitParam = limitParam == null ? "limit" : limitParam;
      pageSize = pageSize == null || pageSize < 1 ? 200 : Math.min(pageSize, 1000);
      initialCursor = initialCursor == null ? "1970-01-01T00:00:00Z" : initialCursor;
      items = items == null ? "/items" : items;
      nextCursor = nextCursor == null ? "/nextCursor" : nextCursor;
    }
  }

  /**
   * Tệp lô CSV/Excel trên SFTP; {@code endpoint} của kết nối có dạng {@code
   * sftp://máy:cổng/thư-mục}.
   */
  public record Batch(
      String knownHosts, Boolean strictHostKey, Integer maxFiles, Long maxBytes, Duration minAge) {

    public Batch {
      // Tệp sửa trong khoảng này có thể đang tải lên dở, để lượt sau.
      minAge = minAge == null || minAge.isNegative() ? Duration.ofMinutes(1) : minAge;
      strictHostKey = strictHostKey == null || strictHostKey;
      maxFiles = maxFiles == null || maxFiles < 1 ? 20 : maxFiles;
      maxBytes = maxBytes == null || maxBytes < 1 ? 20L * 1024 * 1024 : maxBytes;
    }
  }

  /** Đọc chỉ số: {@code GET <endpoint><path>} với {@code {id}} là mã chỉ số ngoài. */
  public record Indicator(String path, String value, String measuredAt) {

    public Indicator {
      path = path == null ? "/api/indicators/{id}" : path;
      value = value == null ? "/value" : value;
      measuredAt = measuredAt == null ? "/measuredAt" : measuredAt;
    }
  }
}
