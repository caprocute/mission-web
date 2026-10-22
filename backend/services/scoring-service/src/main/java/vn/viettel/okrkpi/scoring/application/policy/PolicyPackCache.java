package vn.viettel.okrkpi.scoring.application.policy;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import vn.viettel.okrkpi.scoring.application.port.PolicyPackSource;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.shared.error.BusinessException;

/**
 * Bộ đệm trong tiến trình của Policy Pack đã biên dịch (DV-07-02, ADR-19). Khoá là băm nội dung
 * {@code policy_content_hash}: nội dung một phiên bản bất biến nên mục không hết hạn theo thời
 * gian, chỉ bị bỏ theo thứ tự nạp khi vượt {@code maxEntries}. Khoá (tenant, kỳ, luồng, khối, đơn
 * vị) ánh xạ sang phiên bản ghim; ánh xạ của kỳ chưa đóng băng bị quên khi có EVT-13 để lần sau
 * phân giải lại. Không tìm thấy phiên bản ghim thì từ chối tính, không bao giờ rơi về phiên bản mới
 * nhất (BR-08-07).
 */
public class PolicyPackCache {

  static final String NO_BINDING = "POLICY-404-NO-BINDING";
  static final String HASH_MISMATCH = "POLICY-409-VERSION-CONFLICT";

  private final PolicyPackSource source;
  private final PolicyCompiler compiler;
  private final int maxEntries;
  private final Map<String, CompiledPolicy> compiled = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
  private final Map<PolicyBindingKey, PinnedPolicy> pins = new ConcurrentHashMap<>();
  private final Object loading = new Object();
  private final AtomicLong compilations = new AtomicLong();

  public PolicyPackCache(PolicyPackSource source, PolicyCompiler compiler, int maxEntries) {
    this.source = Objects.requireNonNull(source, "source");
    this.compiler = Objects.requireNonNull(compiler, "compiler");
    if (maxEntries < 1) {
      throw new IllegalArgumentException("Bộ đệm Policy Pack cần ít nhất một mục");
    }
    this.maxEntries = maxEntries;
  }

  /**
   * Phiên bản đã biên dịch ghim cho khoá; không có gắn kết thì ném {@code POLICY-404-NO-BINDING}.
   */
  public CompiledPolicy forBinding(PolicyBindingKey key) {
    PinnedPolicy pinned = pin(key);
    return get(key.tenantId(), pinned);
  }

  /** Phiên bản ghim của khoá, phân giải qua MOD-05 khi chưa có trong ánh xạ. */
  public PinnedPolicy pin(PolicyBindingKey key) {
    PinnedPolicy known = pins.get(key);
    if (known != null) {
      return known;
    }
    PinnedPolicy resolved =
        source
            .resolve(key)
            .orElseThrow(
                () ->
                    new BusinessException(
                        NO_BINDING,
                        "Không tìm thấy phiên bản quy tắc chấm điểm ghim cho kỳ "
                            + key.periodId()
                            + ", luồng "
                            + key.flow()
                            + "; không tính điểm"));
    pins.put(key, resolved);
    return resolved;
  }

  /** Phiên bản đã biên dịch theo băm; chưa có thì tải nội dung và biên dịch một lần. */
  public CompiledPolicy get(UUID tenantId, PinnedPolicy pinned) {
    return load(
        pinned.contentHash(),
        pinned.version(),
        () -> source.load(tenantId, pinned.packId(), pinned.versionId(), pinned.version()));
  }

  /**
   * Nạp trước phiên bản vừa phát hành (EVT-13); băm của sự kiện phải khớp băm MOD-05 công bố cho
   * phiên bản.
   */
  public CompiledPolicy preload(UUID tenantId, UUID packId, UUID versionId, String contentHash) {
    return load(
        PinnedPolicy.normalize(contentHash),
        versionId.toString(),
        () -> source.load(tenantId, packId, versionId, null));
  }

  private CompiledPolicy load(String hash, String version, Supplier<PolicyContent> loader) {
    CompiledPolicy hit = compiled.get(hash);
    if (hit != null) {
      return hit;
    }
    synchronized (loading) {
      hit = compiled.get(hash);
      if (hit != null) {
        return hit;
      }
      PolicyContent content = loader.get();
      if (!content.pinned().contentHash().equals(hash)) {
        throw new BusinessException(
            HASH_MISMATCH,
            "Nội dung phiên bản quy tắc "
                + version
                + " không khớp băm đã ghim; không dùng phiên bản này");
      }
      CompiledPolicy fresh = compiler.compile(content);
      compilations.incrementAndGet();
      compiled.put(hash, fresh);
      order.add(hash);
      while (compiled.size() > maxEntries) {
        compiled.remove(order.poll());
      }
      return fresh;
    }
  }

  /** Quên ánh xạ khoá → phiên bản của các kỳ chưa đóng băng của tenant. */
  public int forgetPins(UUID tenantId) {
    int before = pins.size();
    pins.entrySet().removeIf(e -> e.getKey().tenantId().equals(tenantId) && !e.getValue().frozen());
    return before - pins.size();
  }

  public Optional<CompiledPolicy> cached(String contentHash) {
    return Optional.ofNullable(compiled.get(PinnedPolicy.normalize(contentHash)));
  }

  /** Số lần đã biên dịch (đo tỉ lệ trúng đệm). */
  public long compilations() {
    return compilations.get();
  }

  public int size() {
    return compiled.size();
  }
}
