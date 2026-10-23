package vn.viettel.okrkpi.analytics.infrastructure.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.application.ExportJob;
import vn.viettel.okrkpi.analytics.application.ExportPorts;
import vn.viettel.okrkpi.shared.cache.TenantCache;
import vn.viettel.okrkpi.shared.cache.TenantCacheKey;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Trạng thái tác vụ xuất trong Valkey (dùng chung giữa các bản sao): khoá {@code
 * t:<tenant>:analytics:export:<tác vụ>} và {@code t:<tenant>:analytics:export-key:<người>:<băm
 * khoá>}, giữ 24 giờ (suy luận). Bản ghi chỉ có mã người khởi tạo, trạng thái và khoá đối tượng.
 */
final class ValkeyExportJobs implements ExportPorts.Jobs {

  static final Duration TTL = Duration.ofHours(24);

  private final TenantCache cache;
  private final ObjectMapper json;

  ValkeyExportJobs(TenantCache cache, ObjectMapper json) {
    this.cache = cache;
    this.json = json;
  }

  @Override
  public void save(ExportJob job) {
    try {
      cache.put(jobKey(job.tenant(), job.id()), json.writeValueAsString(job), TTL);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Optional<ExportJob> find(UUID tenant, String jobId) {
    try {
      UUID.fromString(jobId);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    Optional<String> v = cache.get(jobKey(tenant, jobId));
    if (v.isEmpty()) {
      return Optional.empty();
    }
    try {
      ExportJob job = json.readValue(v.get(), ExportJob.class);
      return tenant.equals(job.tenant()) ? Optional.of(job) : Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Optional<String> keyed(UUID tenant, UUID user, String idempotencyKey) {
    return cache.get(idemKey(tenant, user, idempotencyKey));
  }

  @Override
  public void bind(UUID tenant, UUID user, String idempotencyKey, String jobId) {
    cache.put(idemKey(tenant, user, idempotencyKey), jobId, TTL);
  }

  private static TenantCacheKey jobKey(UUID tenant, String id) {
    return TenantCacheKey.forTenant(TenantId.of(tenant), "analytics", "export", id);
  }

  private static TenantCacheKey idemKey(UUID tenant, UUID user, String key) {
    return TenantCacheKey.forTenant(
        TenantId.of(tenant), "analytics", "export-key", user.toString(), sha256(key));
  }

  private static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
