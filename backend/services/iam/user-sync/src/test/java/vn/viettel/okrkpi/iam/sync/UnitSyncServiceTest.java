package vn.viettel.okrkpi.iam.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class UnitSyncServiceTest {

  private static final UUID TENANT = UUID.fromString("0192f000-0000-7000-8000-0000000000a1");
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
  private final PersonSyncServiceTest.FakeDirectory dir = new PersonSyncServiceTest.FakeDirectory();
  private final UnitSyncService service = new UnitSyncService(dir);

  private UUID user(UUID tenant, UUID unit) {
    UUID id = UUID.randomUUID();
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("tenant_id", List.of(tenant.toString()));
    attrs.put(UnitSyncService.CONTEXT_UNIT, List.of(unit.toString()));
    attrs.put(UnitSyncService.CONTEXT_ASSIGNMENT, List.of(UUID.randomUUID().toString()));
    Map<String, Object> u = new LinkedHashMap<>();
    u.put("id", id.toString());
    u.put("attributes", attrs);
    dir.users.put(id, u);
    return id;
  }

  @Test
  void donViHetHieuLucThiGoNguCanhVaDangXuat() {
    UUID unit = UUID.randomUUID();
    UUID a = user(TENANT, unit);
    UUID other = user(UUID.randomUUID(), unit);
    UUID elsewhere = user(TENANT, UUID.randomUUID());

    assertThat(service.apply(TENANT, unit, TODAY, TODAY)).isEqualTo(1);
    assertThat((Map<String, Object>) dir.users.get(a).get("attributes")).doesNotContainKeys(UnitSyncService.CONTEXT_UNIT,
        UnitSyncService.CONTEXT_ASSIGNMENT).containsKey("tenant_id");
    assertThat(dir.logouts).containsExactly(a);
    assertThat((Map<String, Object>) dir.users.get(other).get("attributes")).containsKey(UnitSyncService.CONTEXT_UNIT);
    assertThat((Map<String, Object>) dir.users.get(elsewhere).get("attributes")).containsKey(UnitSyncService.CONTEXT_UNIT);
    assertThat(service.apply(TENANT, unit, TODAY, TODAY)).isZero();
  }

  @Test
  void donViConHieuLucHoacHetHanTuongLaiThiKhongLamGi() {
    UUID unit = UUID.randomUUID();
    user(TENANT, unit);
    assertThat(service.apply(TENANT, unit, null, TODAY)).isZero();
    assertThat(service.apply(TENANT, unit, TODAY.plusDays(1), TODAY)).isZero();
    assertThat(dir.logouts).isEmpty();
  }
}
