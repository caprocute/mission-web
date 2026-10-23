package vn.viettel.okrkpi.esign.application.sign;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.esign.domain.sign.ProviderCode;
import vn.viettel.okrkpi.esign.domain.sign.SignatureFormat;
import vn.viettel.okrkpi.esign.spi.SignatureProvider;

/**
 * Chọn bản hiện thực {@link SignatureProvider} cho từng mã nhà cung cấp theo cấu hình (mã → {@code
 * id()}); mã chưa cấu hình hoặc chưa có bản hiện thực là nhà cung cấp chưa sẵn sàng.
 */
public final class ProviderRegistry {

  private final Map<ProviderCode, SignatureProvider> byCode = new EnumMap<>(ProviderCode.class);
  private final ProviderCode defaultCode;
  private final Map<UUID, TenantProviders> tenants;

  /**
   * Nhà cung cấp được bật và nhà cung cấp mặc định của một tenant (DV-10-04). Tenant không khai thì
   * dùng mọi nhà cung cấp đã lắp và mặc định chung.
   */
  public record TenantProviders(Set<ProviderCode> enabled, ProviderCode defaultProvider) {
    public TenantProviders {
      enabled = enabled == null || enabled.isEmpty() ? Set.of() : Set.copyOf(enabled);
    }
  }

  public ProviderRegistry(
      List<SignatureProvider> providers, Map<ProviderCode, String> ids, ProviderCode defaultCode) {
    this(providers, ids, defaultCode, Map.of());
  }

  public ProviderRegistry(
      List<SignatureProvider> providers,
      Map<ProviderCode, String> ids,
      ProviderCode defaultCode,
      Map<UUID, TenantProviders> tenants) {
    this.tenants = Map.copyOf(tenants);
    Map<String, SignatureProvider> byId = new HashMap<>();
    for (SignatureProvider provider : providers) {
      if (byId.putIfAbsent(provider.id(), provider) != null) {
        throw new IllegalStateException("Trùng nhà cung cấp chữ ký " + provider.id());
      }
    }
    ids.forEach(
        (code, id) -> {
          SignatureProvider provider = byId.get(id);
          if (provider != null) {
            byCode.put(code, provider);
          }
        });
    this.defaultCode = defaultCode;
  }

  public ProviderCode defaultCode() {
    return defaultCode;
  }

  /** Nhà cung cấp mặc định của tenant. */
  public ProviderCode defaultCode(UUID tenantId) {
    TenantProviders t = tenants.get(tenantId);
    return t == null || t.defaultProvider() == null ? defaultCode : t.defaultProvider();
  }

  /** Tenant được dùng nhà cung cấp này không (chỉ xét cấu hình, không xét mạch). */
  public boolean enabled(UUID tenantId, ProviderCode code) {
    TenantProviders t = tenants.get(tenantId);
    return t == null || t.enabled().isEmpty() || t.enabled().contains(code);
  }

  /** Các nhà cung cấp đã lắp mà tenant được dùng, mỗi bản hiện thực một lần. */
  public List<SignatureProvider> enabledFor(UUID tenantId) {
    Set<SignatureProvider> out = new LinkedHashSet<>();
    byCode.forEach(
        (code, provider) -> {
          if (enabled(tenantId, code)) {
            out.add(provider);
          }
        });
    return List.copyOf(out);
  }

  public Optional<SignatureProvider> forCode(ProviderCode code) {
    return Optional.ofNullable(byCode.get(code));
  }

  /** Định dạng của CSDL/API ánh xạ sang tên của TL-01 trong giao diện nhà cung cấp. */
  public static vn.viettel.okrkpi.esign.spi.SignatureFormat toSpi(SignatureFormat format) {
    return switch (format) {
      case PADES_LTA -> vn.viettel.okrkpi.esign.spi.SignatureFormat.PADES_B_LTA;
      case CADES_T -> vn.viettel.okrkpi.esign.spi.SignatureFormat.CADES_B_T;
      case JWS -> vn.viettel.okrkpi.esign.spi.SignatureFormat.JWS;
    };
  }
}
