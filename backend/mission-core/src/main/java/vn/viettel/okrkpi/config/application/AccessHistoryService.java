package vn.viettel.okrkpi.config.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.application.port.DelegationRepository;
import vn.viettel.okrkpi.config.application.port.RoleBindingRepository;
import vn.viettel.okrkpi.config.domain.AccessChange;
import vn.viettel.okrkpi.config.domain.Delegation;
import vn.viettel.okrkpi.config.domain.RoleBinding;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Dòng thời gian thay đổi quyền ({@code listAccessHistory}, {@code exportAccessHistory}, SCR-0106).
 * Mỗi mốc suy ra từ cột của {@code config.role_binding} và {@code config.delegation} (cấp, duyệt,
 * kết thúc, hết hạn), nên không phụ thuộc kho kiểm toán; mã mốc là UUID tên của (bản ghi, loại) để
 * con trỏ ổn định.
 */
public class AccessHistoryService {

  /** Trần số bản ghi nguồn đọc cho một lượt (mỗi bản ghi sinh tối đa ba mốc). */
  static final int SOURCE_CAP = 2_000;

  static final Map<String, String> ROLE_LABEL =
      Map.of(
          "tenant_admin", "Quản trị đơn vị",
          "policy_author", "Quản trị nghiệp vụ",
          "leader", "Lãnh đạo đơn vị",
          "manager", "Cán bộ quản lý trực tiếp",
          "member", "Cán bộ, công chức, viên chức",
          "appraiser", "Cán bộ thẩm định",
          "decider", "Người có thẩm quyền quyết định",
          "second_approver", "Người duyệt thứ hai",
          "inspector", "Cán bộ kiểm tra, giám sát");

  static final Map<Delegation.Kind, String> DELEGATION_LABEL =
      Map.of(
          Delegation.Kind.DUYET_THAY, "Duyệt thay",
          Delegation.Kind.NGHIEM_THU_THAY, "Nghiệm thu thay",
          Delegation.Kind.CHECK_IN_THAY, "Check-in KR thay");

  /** Một mốc; {@code actorId} rỗng là hệ thống (hết hạn). */
  public record Entry(
      UUID id,
      Instant at,
      AccessChange kind,
      UUID actorId,
      UUID personId,
      UUID orgUnitId,
      String summary,
      UUID refId) {}

  /** Vị trí sau mốc cuối của trang trước. */
  public record After(Instant at, UUID id) {}

  private static final Comparator<Entry> NEWEST_FIRST =
      Comparator.comparing(Entry::at).thenComparing(Entry::id).reversed();

  private final RoleBindingRepository bindings;
  private final DelegationRepository delegations;
  private final AccessDirectory directory;
  private final Clock clock;

  public AccessHistoryService(
      RoleBindingRepository bindings,
      DelegationRepository delegations,
      AccessDirectory directory,
      Clock clock) {
    this.bindings = Objects.requireNonNull(bindings, "bindings");
    this.delegations = Objects.requireNonNull(delegations, "delegations");
    this.directory = directory;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Trang dòng thời gian, mới trước.
   *
   * @param fetch số mốc cần lấy (cỡ trang cộng một)
   */
  @Transactional(value = RoleBindingService.TX, readOnly = true)
  public List<Entry> list(
      TenantId tenantId, AccessActor actor, UUID personId, UUID orgUnitId, After after, int fetch) {
    actor.requireAny(
        RoleBindingService.TENANT_ADMIN, RoleBindingService.LEADER, RoleBindingService.INSPECTOR);
    if (personId == null && orgUnitId == null) {
      throw RoleBindingService.invalid("personId", "Cần chọn người hoặc đơn vị để xem lịch sử.");
    }
    return page(tenantId, personId, orgUnitId, after, fetch);
  }

  /** Toàn bộ lịch sử của một người để xuất CSV (cần {@code aal2}). */
  @Transactional(value = RoleBindingService.TX, readOnly = true)
  public List<Entry> export(TenantId tenantId, AccessActor actor, UUID personId) {
    actor.requireAny(RoleBindingService.TENANT_ADMIN, RoleBindingService.INSPECTOR);
    actor.requireStepUp("exportAccessHistory", clock);
    if (personId == null) {
      throw RoleBindingService.invalid("personId", "Cần chọn người để xuất lịch sử.");
    }
    return page(tenantId, personId, null, null, Integer.MAX_VALUE);
  }

  private List<Entry> page(
      TenantId tenantId, UUID personId, UUID orgUnitId, After after, int fetch) {
    List<UUID> units = new ArrayList<>();
    if (orgUnitId != null) {
      units.add(orgUnitId);
      if (directory != null) {
        units.addAll(directory.subunitsOf(tenantId, orgUnitId));
      }
    }
    List<RoleBinding> roleRows = bindings.history(tenantId, personId, units, SOURCE_CAP);
    List<Delegation> delegationRows = delegations.history(tenantId, personId, units, SOURCE_CAP);
    Set<UUID> unitIds = new HashSet<>();
    roleRows.forEach(b -> unitIds.add(b.orgUnitId()));
    delegationRows.forEach(d -> unitIds.add(d.orgUnitId()));
    Map<UUID, String> unitNames =
        directory == null || unitIds.isEmpty() ? Map.of() : directory.unitNames(tenantId, unitIds);
    Instant now = clock.instant();
    List<Entry> out = new ArrayList<>();
    for (RoleBinding b : roleRows) {
      String what =
          ROLE_LABEL.getOrDefault(b.roleCode(), b.roleCode()) + at(unitNames, b.orgUnitId());
      add(out, b.id(), b.grantedAt(), AccessChange.CAP_VAI_TRO, b.grantedBy(), b, what);
      if (b.approvedAt() != null) {
        add(out, b.id(), b.approvedAt(), AccessChange.DUYET_VAI_TRO, b.approvedBy(), b, what);
      }
      if (b.endKind() == RoleBinding.EndKind.TU_CHOI) {
        add(out, b.id(), b.endedAt(), AccessChange.TU_CHOI_VAI_TRO, b.endedBy(), b, what);
      } else if (b.endKind() == RoleBinding.EndKind.THU_HOI) {
        add(out, b.id(), b.endedAt(), AccessChange.THU_HOI_VAI_TRO, b.endedBy(), b, what);
      } else if (!b.validTo().isAfter(now)) {
        add(out, b.id(), b.validTo(), AccessChange.HET_HAN_VAI_TRO, null, b, what);
      }
    }
    for (Delegation d : delegationRows) {
      String what = DELEGATION_LABEL.get(d.kind()) + at(unitNames, d.orgUnitId());
      add(out, d.id(), d.createdAt(), AccessChange.TAO_UY_QUYEN, d.createdBy(), d, what);
      if (d.ended()) {
        add(out, d.id(), d.endedAt(), AccessChange.THU_HOI_UY_QUYEN, d.endedBy(), d, what);
      } else if (!d.validTo().isAfter(now)) {
        add(out, d.id(), d.validTo(), AccessChange.HET_HAN_UY_QUYEN, null, d, what);
      }
    }
    return out.stream()
        .filter(
            e ->
                after == null
                    || NEWEST_FIRST.compare(
                            e,
                            new Entry(after.id(), after.at(), null, null, null, null, null, null))
                        > 0)
        .sorted(NEWEST_FIRST)
        .limit(fetch)
        .toList();
  }

  private static void add(
      List<Entry> out,
      UUID ref,
      Instant at,
      AccessChange kind,
      UUID actor,
      RoleBinding b,
      String what) {
    if (at != null) {
      out.add(
          new Entry(entryId(ref, kind), at, kind, actor, b.personId(), b.orgUnitId(), what, ref));
    }
  }

  private static void add(
      List<Entry> out,
      UUID ref,
      Instant at,
      AccessChange kind,
      UUID actor,
      Delegation d,
      String what) {
    if (at != null) {
      out.add(
          new Entry(entryId(ref, kind), at, kind, actor, d.delegateId(), d.orgUnitId(), what, ref));
    }
  }

  private static String at(Map<UUID, String> unitNames, UUID unit) {
    String name = unitNames.get(unit);
    return name == null ? "" : " tại " + name;
  }

  static UUID entryId(UUID ref, AccessChange kind) {
    return UUID.nameUUIDFromBytes((ref + ":" + kind.name()).getBytes(StandardCharsets.UTF_8));
  }
}
