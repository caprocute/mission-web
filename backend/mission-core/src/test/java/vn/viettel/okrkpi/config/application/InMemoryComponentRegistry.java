package vn.viettel.okrkpi.config.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import vn.viettel.okrkpi.config.application.port.ComponentRegistryRepository;
import vn.viettel.okrkpi.config.domain.UiComponent;

/** Kho registry trong bộ nhớ cho kiểm thử (CORE-08-09). */
class InMemoryComponentRegistry implements ComponentRegistryRepository {

  final List<UiComponent> rows = new ArrayList<>();

  @Override
  public List<String> versions() {
    return rows.stream().map(UiComponent::registryVersion).distinct().toList();
  }

  @Override
  public List<UiComponent> list(
      String version, UiComponent.Status status, String afterKey, int limit) {
    return rows.stream()
        .filter(c -> c.registryVersion().equals(version))
        .filter(c -> status == null || c.status() == status)
        .filter(c -> afterKey == null || c.componentKey().compareTo(afterKey) > 0)
        .sorted(Comparator.comparing(UiComponent::componentKey))
        .limit(limit)
        .toList();
  }

  @Override
  public List<UiComponent> all(String version) {
    return list(version, null, null, Integer.MAX_VALUE);
  }

  @Override
  public void insertVersion(List<UiComponent> components) {
    rows.addAll(components);
  }
}
