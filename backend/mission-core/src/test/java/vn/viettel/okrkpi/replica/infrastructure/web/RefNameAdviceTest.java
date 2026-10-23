package vn.viettel.okrkpi.replica.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.contract.dossiers.model.Ref;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** {@link RefNameAdvice}: điền tên rỗng, giữ tên đã có, tra người trước rồi đơn vị, một lượt. */
class RefNameAdviceTest {

  private static final UUID PERSON = UUID.randomUUID();
  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID UNKNOWN = UUID.randomUUID();

  private final TenantId tenant = TenantId.of(UUID.randomUUID());

  @Test
  void fillsBlankNamesOfNestedContractRefsInOneLookup() {
    Directory dir = new Directory();
    Ref person = new Ref(PERSON, "");
    Ref unit = new Ref(UNIT, "");
    Ref named = new Ref(PERSON, "Tên do controller đặt");
    Ref unknown = new Ref(UNKNOWN, "");
    Ref sameUnitAgain = new Ref(UNIT, null);

    new RefNameAdvice(null)
        .fill(
            tenant,
            dir,
            List.of(person, Map.of("x", List.of(unit, named)), unknown, sameUnitAgain));

    assertThat(person.getName()).isEqualTo("Nguyễn Văn An");
    assertThat(unit.getName()).isEqualTo("Phòng Kế hoạch");
    assertThat(sameUnitAgain.getName()).isEqualTo("Phòng Kế hoạch");
    assertThat(named.getName()).isEqualTo("Tên do controller đặt");
    assertThat(unknown.getName()).isEmpty();
    assertThat(dir.personCalls).isEqualTo(1);
    assertThat(dir.unitCalls).isEqualTo(1);
    assertThat(dir.unitIds).containsExactlyInAnyOrder(UNIT, UNKNOWN);
  }

  @Test
  void skipsLookupWhenNothingIsBlank() {
    Directory dir = new Directory();
    new RefNameAdvice(null).fill(tenant, dir, List.of(new Ref(PERSON, "Đã có tên"), "chuỗi"));
    assertThat(dir.personCalls).isZero();
    assertThat(dir.unitCalls).isZero();
  }

  private static final class Directory implements AccessDirectory {
    int personCalls;
    int unitCalls;
    Collection<UUID> unitIds = List.of();

    @Override
    public Map<UUID, String> personNames(TenantId tenant, Collection<UUID> personIds) {
      personCalls++;
      return personIds.contains(PERSON) ? Map.of(PERSON, "Nguyễn Văn An") : Map.of();
    }

    @Override
    public Map<UUID, String> unitNames(TenantId tenant, Collection<UUID> ids) {
      unitCalls++;
      unitIds = List.copyOf(ids);
      return ids.contains(UNIT) ? Map.of(UNIT, "Phòng Kế hoạch") : Map.of();
    }

    @Override
    public List<UUID> subunitsOf(TenantId tenant, UUID orgUnitId) {
      return List.of();
    }

    @Override
    public List<UUID> ancestorsOf(TenantId tenant, UUID orgUnitId) {
      return List.of();
    }
  }
}
