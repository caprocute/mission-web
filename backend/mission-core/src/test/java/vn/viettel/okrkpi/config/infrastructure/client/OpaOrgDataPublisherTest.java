package vn.viettel.okrkpi.config.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.config.infrastructure.client.OpaOrgDataPublisher.Binding;

/** Dạng {@code data.org.<tenant>} mà {@code authz.rego} đọc (CORE-00-03, README OPA). */
class OpaOrgDataPublisherTest {

  private static final UUID SO = UUID.fromString("d0000000-0000-4000-8000-000000000100");
  private static final UUID PHONG = UUID.fromString("d0000000-0000-4000-8000-000000000101");
  private static final UUID BAN = UUID.fromString("d0000000-0000-4000-8000-000000000102");
  private static final UUID NGUOI = UUID.fromString("d0000000-0000-4000-8000-000000000308");

  @Test
  @SuppressWarnings("unchecked")
  void subtreeGomChinhNoVaCapDuoiVaVaiTroMoRongTheoPhamVi() {
    List<UUID[]> closure =
        List.of(
            new UUID[] {SO, SO},
            new UUID[] {SO, PHONG},
            new UUID[] {SO, BAN},
            new UUID[] {PHONG, PHONG},
            new UUID[] {BAN, BAN});
    List<Binding> bindings =
        List.of(
            new Binding(NGUOI, "member", BAN, false), new Binding(NGUOI, "appraiser", SO, true));

    Map<String, Object> doc = OpaOrgDataPublisher.document(closure, bindings);

    Map<String, List<String>> subtree = (Map<String, List<String>>) doc.get("subtree");
    assertThat(subtree.get(SO.toString()))
        .containsExactly(SO.toString(), PHONG.toString(), BAN.toString());
    assertThat(subtree.get(BAN.toString())).containsExactly(BAN.toString());
    assertThat((Map<String, List<String>>) doc.get("managed_units"))
        .containsEntry(NGUOI.toString(), List.of(BAN.toString(), SO.toString()));
    Map<String, Map<String, List<String>>> roles =
        ((Map<String, Map<String, Map<String, List<String>>>>) doc.get("role_units"))
            .get(NGUOI.toString());
    assertThat(roles.get("member").get("unit")).containsExactly(BAN.toString());
    assertThat(roles.get("member").get("subtree")).isEmpty();
    assertThat(roles.get("appraiser").get("unit")).containsExactly(SO.toString());
    assertThat(roles.get("appraiser").get("subtree")).containsExactly(SO.toString());
  }
}
