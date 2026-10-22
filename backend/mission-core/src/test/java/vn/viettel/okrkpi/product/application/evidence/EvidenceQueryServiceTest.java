package vn.viettel.okrkpi.product.application.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.product.api.EvidenceQueryApi;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Cổng đọc minh chứng đã xác nhận cho MOD-04. */
class EvidenceQueryServiceTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID PERSON = UUID.randomUUID();

  private final EvidenceSubmissionRepository repo = mock(EvidenceSubmissionRepository.class);
  private final EvidenceQueryService service = new EvidenceQueryService(repo);

  @Test
  void rongThiKhongTruyVan() {
    assertThat(service.confirmedEvidence(TENANT, PERSON, Arrays.asList((UUID) null))).isEmpty();
    verify(repo, never()).confirmedOf(any(), any(), any());
  }

  @Test
  void boTrungVaGioiHan() {
    UUID a = UUID.randomUUID();
    when(repo.confirmedOf(TENANT, PERSON, Set.of(a))).thenReturn(Set.of(a));
    assertThat(service.confirmedEvidence(TENANT, PERSON, List.of(a, a))).containsExactly(a);
    List<UUID> many = new ArrayList<>();
    for (int i = 0; i <= EvidenceQueryApi.MAX_IDS; i++) {
      many.add(UUID.randomUUID());
    }
    assertThatThrownBy(() -> service.confirmedEvidence(TENANT, PERSON, many))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nguoiDaNghiemThuTrongKy() {
    UUID period = UUID.randomUUID();
    UUID reviewer = UUID.randomUUID();
    when(repo.acceptorsOf(TENANT, period, PERSON)).thenReturn(Set.of(reviewer));
    assertThat(service.acceptors(TENANT, period, PERSON)).containsExactly(reviewer);
  }
}
