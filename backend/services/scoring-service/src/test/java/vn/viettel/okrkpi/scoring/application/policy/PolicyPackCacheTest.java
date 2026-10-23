package vn.viettel.okrkpi.scoring.application.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.scoring.application.port.PolicyPackSource;
import vn.viettel.okrkpi.scoring.domain.PinnedPolicy;
import vn.viettel.okrkpi.scoring.domain.PolicyBindingKey;
import vn.viettel.okrkpi.scoring.domain.PolicyContent;
import vn.viettel.okrkpi.shared.error.BusinessException;

class PolicyPackCacheTest {

  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000a001");
  static final UUID PERIOD = UUID.fromString("0192f1a2-0000-7000-8000-0000000000bb");

  /** Nguồn giả: ánh xạ khoá → phiên bản ghim, phiên bản → nội dung; đếm số lần tải. */
  static final class FakeSource implements PolicyPackSource {
    final Map<PolicyBindingKey, PinnedPolicy> bindings = new HashMap<>();
    final Map<UUID, PolicyContent> contents = new HashMap<>();
    final AtomicInteger resolves = new AtomicInteger();
    final AtomicInteger loads = new AtomicInteger();

    PinnedPolicy publish(PolicyContent c) {
      contents.put(c.pinned().versionId(), c);
      return c.pinned();
    }

    @Override
    public Optional<PinnedPolicy> resolve(PolicyBindingKey key) {
      resolves.incrementAndGet();
      return Optional.ofNullable(bindings.get(key));
    }

    @Override
    public PolicyContent load(UUID tenantId, UUID packId, UUID versionId, String version) {
      loads.incrementAndGet();
      return contents.get(versionId);
    }
  }

  static PolicyContent version(String v, Map<String, String> overrides) {
    return SamplePolicyPack.content(
        SamplePolicyPack.content().pinned().packId(), UUID.randomUUID(), v, overrides);
  }

  @Test
  void haiLanTinhCungPhienBanChiBienDichMotLan() {
    FakeSource source = new FakeSource();
    PinnedPolicy v1 = source.publish(version("2.1.0", Map.of()));
    PolicyBindingKey dang = PolicyBindingKey.of(TENANT, PERIOD, "DANG");
    PolicyBindingKey nhaNuoc = PolicyBindingKey.of(TENANT, PERIOD, "NHA_NUOC");
    source.bindings.put(dang, v1);
    source.bindings.put(nhaNuoc, v1);
    PolicyPackCache cache = new PolicyPackCache(source, PolicyCompilerTest.compiler(), 4);

    CompiledPolicy first = cache.forBinding(dang);
    CompiledPolicy second = cache.forBinding(dang);
    CompiledPolicy other = cache.forBinding(nhaNuoc);

    assertThat(second).isSameAs(first);
    assertThat(other).isSameAs(first);
    assertThat(cache.compilations()).isEqualTo(1);
    assertThat(source.loads).hasValue(1);
    assertThat(source.resolves).hasValue(2);
    assertThat(cache.cached(v1.contentHash().toUpperCase())).containsSame(first);
    assertThat(cache.size()).isEqualTo(1);
  }

  @Test
  void khongCoPhienBanGhimThiTuChoiTinh() {
    PolicyPackCache cache = new PolicyPackCache(new FakeSource(), PolicyCompilerTest.compiler(), 4);
    assertThatThrownBy(() -> cache.forBinding(PolicyBindingKey.of(TENANT, PERIOD, "DANG")))
        .isInstanceOf(BusinessException.class)
        .hasMessage("POLICY-404-NO-BINDING");
  }

  @Test
  void noiDungKhongKhopBamGhimThiKhongDung() {
    FakeSource source = new FakeSource();
    PolicyContent c = version("2.1.0", Map.of());
    source.publish(c);
    PinnedPolicy forged =
        new PinnedPolicy(
            c.pinned().packId(), "x", c.pinned().versionId(), "2.1.0", "ab".repeat(32), false);
    PolicyPackCache cache = new PolicyPackCache(source, PolicyCompilerTest.compiler(), 4);
    assertThatThrownBy(() -> cache.get(TENANT, forged))
        .isInstanceOf(BusinessException.class)
        .hasMessage("POLICY-409-VERSION-CONFLICT");
    assertThat(cache.compilations()).isZero();
  }

  @Test
  void vuotGioiHanThiBoMucNapSomNhat() {
    FakeSource source = new FakeSource();
    PinnedPolicy a = source.publish(version("1.0.0", Map.of()));
    PinnedPolicy b = source.publish(version("1.0.1", Map.of("kpi_nhiem_vu", "kpi_A")));
    PolicyPackCache cache = new PolicyPackCache(source, PolicyCompilerTest.compiler(), 1);

    cache.get(TENANT, a);
    cache.get(TENANT, b);

    assertThat(cache.cached(a.contentHash())).isEmpty();
    assertThat(cache.cached(b.contentHash())).isPresent();
    assertThat(cache.size()).isEqualTo(1);
    assertThatThrownBy(() -> new PolicyPackCache(source, PolicyCompilerTest.compiler(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void napTruocVaQuenAnhXaKyChuaDongBang() {
    FakeSource source = new FakeSource();
    PinnedPolicy v1 = source.publish(version("2.1.0", Map.of()));
    PolicyContent frozenContent = version("2.0.0", Map.of());
    source.publish(frozenContent);
    PinnedPolicy frozen =
        new PinnedPolicy(
            frozenContent.pinned().packId(),
            "x",
            frozenContent.pinned().versionId(),
            "2.0.0",
            frozenContent.pinned().contentHash(),
            true);
    UUID oldPeriod = UUID.randomUUID();
    PolicyBindingKey open = PolicyBindingKey.of(TENANT, PERIOD, "DANG");
    PolicyBindingKey locked = PolicyBindingKey.of(TENANT, oldPeriod, "DANG");
    PolicyBindingKey otherTenant = PolicyBindingKey.of(UUID.randomUUID(), PERIOD, "DANG");
    source.bindings.put(open, v1);
    source.bindings.put(locked, frozen);
    source.bindings.put(otherTenant, v1);
    PolicyPackCache cache = new PolicyPackCache(source, PolicyCompilerTest.compiler(), 4);
    cache.pin(open);
    cache.pin(locked);
    cache.pin(otherTenant);

    CompiledPolicy pre =
        cache.preload(TENANT, v1.packId(), v1.versionId(), v1.contentHash().toUpperCase());
    assertThat(cache.preload(TENANT, v1.packId(), v1.versionId(), v1.contentHash())).isSameAs(pre);
    assertThat(cache.forgetPins(TENANT)).isEqualTo(1);
    assertThat(cache.forBinding(open)).isSameAs(pre);
    assertThat(source.resolves).hasValue(4);
    assertThat(cache.compilations()).isEqualTo(1);
  }
}
