package vn.viettel.okrkpi.contract.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Danh mục 11 mô-đun hợp đồng OpenAPI 3.1 trong {@code contracts/openapi/modules/*.yaml} (ADR-32,
 * NEN-006) kèm ánh xạ {@code x-mission.owner} sang gói Java đã sinh.
 */
public enum ContractModule {
  APPRAISALS("appraisals", 44, Set.of("SVC-05"), List.of("AppraisalsApi")),
  CONFIG("config", 34, Set.of("SVC-05"), List.of("ConfigApi", "PeriodsApi", "UiApi")),
  DOSSIERS("dossiers", 28, Set.of("SVC-05", "SVC-07"), List.of("DossiersApi", "EsignApi")),
  JOBS("jobs", 2, Set.of("SVC-05"), List.of("JobsApi")),
  OKR("okr", 40, Set.of("SVC-05"), List.of("OkrApi")),
  ORG("org", 31, Set.of("SVC-05", "SVC-06"), List.of("OrgApi")),
  OVERSIGHT("oversight", 29, Set.of("SVC-11", "SVC-13"), List.of("LedgerApi", "OversightApi")),
  PLATFORM(
      "platform",
      52,
      Set.of("SVC-05", "SVC-08", "SVC-09", "SVC-10", "SVC-12"),
      List.of(
          "AiApi", "AnalyticsApi", "NotificationsApi", "RegistryApi", "SearchApi", "WorkflowApi")),
  POLICIES("policies", 17, Set.of("SVC-05", "SVC-10"), List.of("PoliciesApi")),
  PRODUCTS("products", 43, Set.of("SVC-05"), List.of("EvidencesApi", "FilesApi", "ProductsApi")),
  TASKS("tasks", 52, Set.of("SVC-05"), List.of("CatalogApi", "PlansApi", "TasksApi"));

  public static final String BASE_PACKAGE = "vn.viettel.okrkpi.contract";
  public static final int TOTAL_OPERATIONS = 372;

  private final String moduleId;
  private final int operationCount;
  private final Set<String> owners;
  private final List<String> apiInterfaceNames;

  ContractModule(
      String moduleId, int operationCount, Set<String> owners, List<String> apiInterfaceNames) {
    this.moduleId = Objects.requireNonNull(moduleId, "moduleId must not be null");
    this.operationCount = operationCount;
    this.owners = Set.copyOf(owners);
    this.apiInterfaceNames = List.copyOf(apiInterfaceNames);
  }

  public String moduleId() {
    return moduleId;
  }

  public String specFileName() {
    return moduleId + ".yaml";
  }

  public int operationCount() {
    return operationCount;
  }

  public Set<String> owners() {
    return owners;
  }

  public List<String> apiInterfaceNames() {
    return apiInterfaceNames;
  }

  public String apiPackage() {
    return BASE_PACKAGE + "." + moduleId + ".api";
  }

  public String modelPackage() {
    return BASE_PACKAGE + "." + moduleId + ".model";
  }

  public List<String> fullyQualifiedApiInterfaces() {
    String pkg = apiPackage();
    return apiInterfaceNames.stream().map(simple -> pkg + "." + simple).toList();
  }

  public boolean isOwnedBy(String serviceCode) {
    if (serviceCode == null || serviceCode.isBlank()) {
      return false;
    }
    return owners.contains(serviceCode.trim().toUpperCase(Locale.ROOT));
  }

  public static Optional<ContractModule> fromModuleId(String moduleId) {
    if (moduleId == null || moduleId.isBlank()) {
      return Optional.empty();
    }
    String normalized = moduleId.trim().toLowerCase(Locale.ROOT);
    if (normalized.endsWith(".yaml")) {
      normalized = normalized.substring(0, normalized.length() - 5);
    }
    String target = normalized;
    return Arrays.stream(values()).filter(m -> m.moduleId.equals(target)).findFirst();
  }

  public static List<ContractModule> forOwner(String serviceCode) {
    if (serviceCode == null || serviceCode.isBlank()) {
      return List.of();
    }
    return Arrays.stream(values()).filter(m -> m.isOwnedBy(serviceCode)).toList();
  }

  public static List<ContractModule> missionCoreModules() {
    return forOwner("SVC-05");
  }
}
