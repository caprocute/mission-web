package vn.viettel.okrkpi.config.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.application.port.UserPreferenceRepository;
import vn.viettel.okrkpi.config.domain.SavedFilter;
import vn.viettel.okrkpi.config.domain.UserPreference;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.PreconditionRequiredException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tuỳ chọn cá nhân và bộ lọc đã lưu của chính người dùng (CNH-02, NFR-11, đề xuất CNH-01). Mọi thao
 * tác lấy chủ bản ghi từ người gọi; bản ghi của người khác coi như không tồn tại (404). Ghi có khoá
 * lạc quan theo {@code row_version}; khoá đã có mà thiếu {@code If-Match} trả 428.
 */
public class UserPreferenceService {

  static final String TX = "configTransactionManager";
  static final String MODULE = "CONFIG";

  /**
   * Phiên bản lược đồ giá trị hiện hành ({@code schemaVersion} của user-preference.schema.json).
   */
  public static final int SCHEMA_VERSION = 1;

  private final UserPreferenceRepository repository;
  private final ObjectMapper json;
  private final Clock clock;

  public UserPreferenceService(
      UserPreferenceRepository repository, ObjectMapper json, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Mọi tuỳ chọn đã lưu kèm ETag gộp (đổi khi bất kỳ khoá nào được tạo, sửa hay xoá). */
  @Transactional(transactionManager = TX, readOnly = true)
  public Preferences preferences(TenantId tenantId, UUID userId) {
    List<UserPreference> items =
        repository.findAll(tenantId, userId).stream()
            .sorted(Comparator.comparing(UserPreference::prefKey))
            .toList();
    StringBuilder seed = new StringBuilder("prefs");
    for (UserPreference p : items) {
      seed.append('|')
          .append(p.prefKey())
          .append(':')
          .append(p.rowVersion())
          .append(':')
          .append(p.id())
          .append(':')
          .append(p.updatedAt() == null ? 0 : p.updatedAt().toEpochMilli());
    }
    return new Preferences(items, sha(seed.toString()));
  }

  /** Một khoá đã lưu, dùng khi ghép bố cục trang chủ và chế độ hiển thị. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<UserPreference> preference(TenantId tenantId, UUID userId, String prefKey) {
    return repository.find(tenantId, userId, prefKey);
  }

  /**
   * Tạo hoặc thay giá trị một khoá.
   *
   * @param expectedVersion phiên bản trong {@code If-Match}; {@code null} khi người gọi không gửi
   */
  @Transactional(TX)
  public Saved put(
      TenantId tenantId,
      UUID userId,
      String prefKey,
      JsonNode value,
      Integer schemaVersion,
      Long expectedVersion) {
    requireKey(prefKey);
    int version = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
    if (version != SCHEMA_VERSION) {
      throw invalid("schemaVersion", "Phiên bản lược đồ chưa được hỗ trợ.");
    }
    UserPreferenceSchema.requireValidPreference(prefKey, value);
    String text = write(value);
    if (text.getBytes(StandardCharsets.UTF_8).length > UserPreference.MAX_VALUE_BYTES) {
      throw invalid("value", "Giá trị tuỳ chọn vượt quá 16 KB.");
    }
    Optional<UserPreference> current = repository.find(tenantId, userId, prefKey);
    if (current.isEmpty()) {
      if (expectedVersion != null && expectedVersion != 0) {
        throw VersionConflictException.of(MODULE, expectedVersion, 0);
      }
      UserPreference created =
          new UserPreference(Ids.newUuidV7(), userId, prefKey, text, version, clock.instant(), 1);
      if (!repository.insert(tenantId, created, userId)) {
        throw VersionConflictException.of(MODULE, 0, latest(tenantId, userId, prefKey));
      }
      return new Saved(created, true);
    }
    UserPreference existing = current.get();
    if (expectedVersion == null) {
      throw new PreconditionRequiredException();
    }
    if (expectedVersion != existing.rowVersion()) {
      throw VersionConflictException.of(MODULE, expectedVersion, existing.rowVersion());
    }
    UserPreference next = existing.changed(text, version, clock.instant());
    if (!repository.update(tenantId, next, existing.rowVersion(), userId)) {
      throw VersionConflictException.of(MODULE, expectedVersion, latest(tenantId, userId, prefKey));
    }
    return new Saved(next, false);
  }

  /**
   * Xoá lớp phủ cá nhân của một khoá.
   *
   * @throws NoSuchElementException khi người dùng chưa lưu khoá này
   */
  @Transactional(TX)
  public void delete(TenantId tenantId, UUID userId, String prefKey, long expectedVersion) {
    requireKey(prefKey);
    UserPreference existing = repository.find(tenantId, userId, prefKey).orElseThrow();
    if (expectedVersion != existing.rowVersion()
        || !repository.delete(tenantId, userId, prefKey, existing.rowVersion())) {
      throw VersionConflictException.of(MODULE, expectedVersion, latest(tenantId, userId, prefKey));
    }
  }

  /** Bộ lọc của người dùng trên màn hình, mặc định đứng đầu rồi theo thứ tự hiển thị. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<SavedFilter> filters(TenantId tenantId, UUID userId, String screenCode) {
    requireScreen(screenCode);
    return repository.filters(tenantId, userId, screenCode);
  }

  /** Lưu bộ lọc mới; quá 20 bộ lọc trên màn hình hoặc trùng tên trả 422. */
  @Transactional(TX)
  public SavedFilter createFilter(
      TenantId tenantId,
      UUID userId,
      String screenCode,
      String name,
      JsonNode criteria,
      boolean isDefault,
      int displayOrder) {
    requireScreen(screenCode);
    String cleanName = requireName(name);
    String text = requireCriteria(criteria);
    requireOrder(displayOrder);
    repository.lockScreen(tenantId, userId, screenCode);
    List<SavedFilter> existing = repository.filters(tenantId, userId, screenCode);
    if (existing.size() >= SavedFilter.MAX_PER_SCREEN) {
      throw invalid(
          "screenCode", "Mỗi màn hình lưu được tối đa " + SavedFilter.MAX_PER_SCREEN + " bộ lọc.");
    }
    requireUniqueName(existing, cleanName, null);
    SavedFilter created =
        new SavedFilter(
            Ids.newUuidV7(),
            userId,
            screenCode,
            cleanName,
            text,
            isDefault,
            displayOrder,
            clock.instant(),
            1);
    if (isDefault) {
      repository.clearDefault(tenantId, userId, screenCode, created.id(), userId);
    }
    if (!repository.insertFilter(tenantId, created, userId)) {
      throw invalid("name", "Tên bộ lọc đã được dùng trên màn hình này.");
    }
    return created;
  }

  /**
   * Sửa bộ lọc của chính người dùng.
   *
   * @throws NoSuchElementException khi bộ lọc không có hoặc thuộc người khác
   */
  @Transactional(TX)
  public SavedFilter updateFilter(
      TenantId tenantId,
      UUID userId,
      UUID filterId,
      long expectedVersion,
      String name,
      JsonNode criteria,
      boolean isDefault,
      int displayOrder) {
    SavedFilter current = repository.filter(tenantId, userId, filterId).orElseThrow();
    String cleanName = requireName(name);
    String text = requireCriteria(criteria);
    requireOrder(displayOrder);
    if (expectedVersion != current.rowVersion()) {
      throw VersionConflictException.of(MODULE, expectedVersion, current.rowVersion());
    }
    repository.lockScreen(tenantId, userId, current.screenCode());
    requireUniqueName(
        repository.filters(tenantId, userId, current.screenCode()), cleanName, filterId);
    SavedFilter next = current.changed(cleanName, text, isDefault, displayOrder, clock.instant());
    if (isDefault) {
      repository.clearDefault(tenantId, userId, current.screenCode(), filterId, userId);
    }
    if (!repository.updateFilter(tenantId, next, current.rowVersion(), userId)) {
      throw VersionConflictException.of(MODULE, expectedVersion, current.rowVersion() + 1L);
    }
    return next;
  }

  /**
   * Xoá bộ lọc của chính người dùng.
   *
   * @throws NoSuchElementException khi bộ lọc không có hoặc thuộc người khác
   */
  @Transactional(TX)
  public void deleteFilter(TenantId tenantId, UUID userId, UUID filterId, long expectedVersion) {
    SavedFilter current = repository.filter(tenantId, userId, filterId).orElseThrow();
    if (expectedVersion != current.rowVersion()
        || !repository.deleteFilter(tenantId, userId, filterId, current.rowVersion())) {
      throw VersionConflictException.of(MODULE, expectedVersion, current.rowVersion());
    }
  }

  /** Đọc lại giá trị JSON đã lưu. */
  public JsonNode read(String valueJson) {
    try {
      return json.readTree(valueJson);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cột JSON của tuỳ chọn cá nhân hỏng", e);
    }
  }

  private long latest(TenantId tenantId, UUID userId, String prefKey) {
    return repository.find(tenantId, userId, prefKey).map(UserPreference::rowVersion).orElse(0);
  }

  private static void requireKey(String prefKey) {
    if (prefKey == null || !UserPreference.KEY.matcher(prefKey).matches()) {
      throw invalid("prefKey", "Khoá tuỳ chọn không hợp lệ.");
    }
  }

  private static void requireScreen(String screenCode) {
    if (screenCode == null || !SavedFilter.SCREEN.matcher(screenCode).matches()) {
      throw invalid("screenCode", "Mã màn hình không hợp lệ.");
    }
  }

  private static String requireName(String name) {
    String clean = name == null ? "" : name.strip();
    if (clean.isEmpty() || clean.length() > SavedFilter.MAX_NAME_LENGTH) {
      throw invalid("name", "Tên bộ lọc từ 1 đến " + SavedFilter.MAX_NAME_LENGTH + " ký tự.");
    }
    return clean;
  }

  private static void requireOrder(int displayOrder) {
    if (displayOrder < 0 || displayOrder >= SavedFilter.MAX_PER_SCREEN) {
      throw invalid("displayOrder", "Thứ tự hiển thị từ 0 đến 19.");
    }
  }

  private String requireCriteria(JsonNode criteria) {
    UserPreferenceSchema.requireValidCriteria(criteria);
    String text = write(criteria);
    if (text.getBytes(StandardCharsets.UTF_8).length > SavedFilter.MAX_CRITERIA_BYTES) {
      throw invalid("criteria", "Điều kiện lọc vượt quá 8 KB.");
    }
    return text;
  }

  private static void requireUniqueName(List<SavedFilter> filters, String name, UUID exceptId) {
    boolean taken =
        filters.stream().anyMatch(f -> !f.id().equals(exceptId) && f.name().equalsIgnoreCase(name));
    if (taken) {
      throw invalid("name", "Tên bộ lọc đã được dùng trên màn hình này.");
    }
  }

  private String write(JsonNode node) {
    try {
      return json.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Không tuần tự hoá được giá trị tuỳ chọn", e);
    }
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static String sha(String text) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d, 0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Tuỳ chọn đã lưu của người dùng và ETag gộp. */
  public record Preferences(List<UserPreference> items, String etag) {}

  /** Kết quả lưu: {@code created} khi khoá được tạo lần đầu (201). */
  public record Saved(UserPreference preference, boolean created) {}
}
