package vn.viettel.okrkpi.appraisal.application.scoring;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.scoring.port.IncidentRepository;
import vn.viettel.okrkpi.appraisal.application.scoring.port.IncidentRepository.IncidentRow;
import vn.viettel.okrkpi.appraisal.application.scoring.port.IncidentRepository.PersonProfile;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Sự việc đã có kết luận (CORE-04-13, BS-03 DC-05): cơ quan tổ chức cán bộ ghi, điều kiện loại trừ
 * của Policy Pack đọc qua đầu vào tính điểm. Sự việc không sửa, không xoá.
 *
 * <p>Danh mục loại sự việc là dữ liệu của hồ sơ tập khách hàng: thành phần {@value
 * #CATALOG_COMPONENT} của hồ sơ ghim cho kỳ, đơn vị, khối của người (suy luận, BS-03 chưa đặt tên
 * khoá). Không có hồ sơ hoặc hồ sơ không khai danh mục thì nhận mọi mã đúng khuôn dạng, giữ hành vi
 * của lõi không phụ thuộc khách hàng (YC-TK-11).
 */
public class IncidentService {

  /** Khoá thành phần của hồ sơ chứa danh mục loại sự việc (suy luận). */
  public static final String CATALOG_COMPONENT = "loai_su_viec";

  static final String TX = ScoringInputService.TX;
  static final String SUMMARY_COLUMN = "appraisal.incident.summary_enc";
  private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");

  private final IncidentRepository repository;
  private final TextSealer sealer;
  private final PeriodApi periods;
  private final PolicyQueryApi policies;
  private final Clock clock;

  public IncidentService(
      IncidentRepository repository,
      TextSealer sealer,
      PeriodApi periods,
      PolicyQueryApi policies,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.sealer = Objects.requireNonNull(sealer, "sealer");
    this.periods = periods;
    this.policies = policies;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Lệnh ghi sự việc ({@code AppraisalIncidentCreate}). */
  public record NewIncident(
      UUID personId,
      UUID periodId,
      String incidentType,
      LocalDate concludedOn,
      String documentNo,
      String documentRef,
      String impact,
      String summary) {}

  /** Sự việc đã mở tóm tắt. */
  public record IncidentView(IncidentRow row, String summary) {}

  /**
   * Ghi một sự việc.
   *
   * @throws BusinessException {@code APPRAISAL-409-PERIOD-LOCKED} khi kỳ đã khoá, {@code
   *     APPRAISAL-422-INCIDENT-TYPE} khi loại không có trong danh mục, {@code
   *     COMMON-422-VALIDATION} khi người chưa có hồ sơ đánh giá
   */
  @Transactional(TX)
  public IncidentView newIncident(TenantId tenant, UUID actor, NewIncident cmd) {
    Objects.requireNonNull(cmd, "cmd");
    String type = cmd.incidentType() == null ? "" : cmd.incidentType().strip();
    if (!CODE.matcher(type).matches()) {
      throw incidentType(type);
    }
    String impact = cmd.impact() == null || cmd.impact().isBlank() ? null : cmd.impact().strip();
    if (impact != null && !CODE.matcher(impact).matches()) {
      throw invalid("impact", "Mã tác động không hợp lệ.");
    }
    PersonProfile profile =
        repository
            .personProfile(tenant, cmd.personId(), cmd.periodId())
            .orElseThrow(
                () -> invalid("personId", "Chưa có hồ sơ đánh giá của người này trong kỳ."));
    UUID lockCheck = cmd.periodId() != null ? cmd.periodId() : periodCovering(tenant, profile, cmd);
    if (lockCheck != null && periods != null) {
      periods.assertWritable(tenant, PeriodId.of(lockCheck));
    }
    Set<String> catalog = catalog(tenant, profile);
    if (!catalog.isEmpty() && !catalog.contains(type)) {
      throw incidentType(type);
    }
    UUID id = Ids.newUuidV7();
    SealedText summary =
        cmd.summary() == null || cmd.summary().isBlank()
            ? null
            : sealer.seal(tenant, SUMMARY_COLUMN, id, cmd.summary());
    IncidentRow row =
        new IncidentRow(
            id,
            cmd.personId(),
            cmd.periodId(),
            profile.orgUnitId(),
            type,
            cmd.concludedOn(),
            cmd.documentNo().strip(),
            cmd.documentRef(),
            impact,
            summary,
            actor,
            clock.instant(),
            0);
    repository.insert(tenant, row);
    return new IncidentView(row, summary == null ? null : cmd.summary());
  }

  /**
   * Một sự việc trong phạm vi đơn vị của người gọi.
   *
   * @throws NoSuchElementException khi không có hoặc ngoài phạm vi
   */
  @Transactional(value = TX, readOnly = true)
  public IncidentView find(TenantId tenant, UUID unitScope, UUID id) {
    IncidentRow row = repository.find(tenant, id).orElseThrow(NoSuchElementException::new);
    if (unitScope == null || !repository.withinUnit(tenant, unitScope, row.orgUnitId())) {
      throw new NoSuchElementException();
    }
    return view(tenant, row);
  }

  /** Một trang sự việc trong phạm vi đơn vị của người gọi, mới nhất trước. */
  @Transactional(value = TX, readOnly = true)
  public List<IncidentView> list(
      TenantId tenant, UUID unitScope, UUID personId, UUID periodId, UUID beforeId, int limit) {
    if (unitScope == null) {
      return List.of();
    }
    return repository.list(tenant, unitScope, personId, periodId, beforeId, limit).stream()
        .map(r -> view(tenant, r))
        .toList();
  }

  /** Sự việc trong kỳ của nhiều người cho đầu vào tính điểm (chỉ cột C1, không mở tóm tắt). */
  @Transactional(value = TX, readOnly = true)
  public Map<UUID, List<IncidentRow>> forPersons(
      TenantId tenant, UUID periodId, Collection<UUID> personIds) {
    if (personIds.isEmpty()) {
      return Map.of();
    }
    Optional<PeriodSnapshot> period =
        periods == null ? Optional.empty() : periods.findById(tenant, PeriodId.of(periodId));
    LocalDate from = period.map(PeriodSnapshot::startDate).orElse(null);
    LocalDate to = period.map(PeriodSnapshot::endDate).orElse(null);
    return repository.forPersons(tenant, periodId, from, to, personIds);
  }

  private IncidentView view(TenantId tenant, IncidentRow row) {
    return new IncidentView(row, sealer.open(tenant, SUMMARY_COLUMN, row.id(), row.summary()));
  }

  /** Kỳ của hồ sơ khi ngày kết luận nằm trong kỳ đó (sự việc không ghi kỳ). */
  private UUID periodCovering(TenantId tenant, PersonProfile profile, NewIncident cmd) {
    if (periods == null || profile.periodId() == null || cmd.concludedOn() == null) {
      return null;
    }
    return periods
        .findById(tenant, PeriodId.of(profile.periodId()))
        .filter(
            p ->
                p.startDate() != null
                    && p.endDate() != null
                    && !cmd.concludedOn().isBefore(p.startDate())
                    && !cmd.concludedOn().isAfter(p.endDate()))
        .map(p -> profile.periodId())
        .orElse(null);
  }

  /** Mã trong thành phần danh mục của hồ sơ: danh sách mã, danh sách {@code {ma}} hoặc ánh xạ. */
  private Set<String> catalog(TenantId tenant, PersonProfile profile) {
    if (policies == null || profile.periodId() == null) {
      return Set.of();
    }
    Object raw =
        policies
            .customerProfile(
                tenant,
                PeriodId.of(profile.periodId()),
                profile.flow(),
                profile.subjectGroupId(),
                profile.orgUnitId())
            .map(p -> p.components().get(CATALOG_COMPONENT))
            .orElse(null);
    Set<String> codes = new LinkedHashSet<>();
    if (raw instanceof Map<?, ?> map) {
      map.keySet().forEach(k -> codes.add(String.valueOf(k)));
    } else if (raw instanceof Collection<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m) {
          Object code = m.containsKey("ma") ? m.get("ma") : m.get("code");
          if (code != null) {
            codes.add(String.valueOf(code));
          }
        } else if (item != null) {
          codes.add(String.valueOf(item));
        }
      }
    }
    return codes;
  }

  private static BusinessException incidentType(String type) {
    String message = "Loại sự việc không có trong danh mục được áp dụng.";
    return new BusinessException(
        "APPRAISAL-422-INCIDENT-TYPE",
        message,
        List.of(new ProblemFieldError("incidentType", message, null)));
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
