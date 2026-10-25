package vn.viettel.okrkpi.policy.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.contract.policies.model.PolicyProspectGroupCode;
import vn.viettel.okrkpi.contract.policies.model.ProspectCriteria;
import vn.viettel.okrkpi.contract.policies.model.ProspectCriteriaGroupsInner;
import vn.viettel.okrkpi.contract.policies.model.RatingLevel;
import vn.viettel.okrkpi.policy.application.PolicyPackService;
import vn.viettel.okrkpi.policy.domain.PolicyPackVersion;
import vn.viettel.okrkpi.policy.domain.PolicyVersionStatus;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * {@code getProspectCriteria} của {@code policies.yaml} (SCR-1002 "Xem tiêu chí PL 5", BR-10-07,
 * BR-10-09): đọc khối {@code phan_nhom} trong khai báo của phiên bản chỉ định, bỏ trống là phiên
 * bản phát hành mới nhất. Phiên bản chưa có khối này (ví dụ gói khối chính quyền) trả bốn nhóm mặc
 * định giống gói mẫu khối Đảng, cùng cách {@code ProspectBoardService} dùng biểu thức BR-10-09 mặc
 * định (suy luận, wiki 14 mục QA-06).
 */
@RestController
public class ProspectCriteriaController {

  static final String LEGAL_REF = "QĐ khoản 12.1.5; PL 5";

  /** Bốn nhóm của gói mẫu {@code khoi-dang/quy-dinh-moi} khi phiên bản chưa khai báo. */
  static final List<ProspectCriteriaGroupsInner> DEFAULT_GROUPS =
      List.of(
          group(
              "NHOM_1",
              "Phát triển vượt trội",
              "Cán bộ có kết quả và năng lực phát triển vượt trội theo tiêu chí của Phụ lục 5.",
              List.of("KHTNV"),
              true),
          group(
              "NHOM_2",
              "Có triển vọng",
              "Cán bộ có triển vọng phát triển theo tiêu chí của Phụ lục 5.",
              List.of("KHTNV"),
              true),
          group(
              "NHOM_3",
              "Cần bồi dưỡng thêm",
              "Cán bộ cần được bồi dưỡng thêm theo tiêu chí của Phụ lục 5.",
              List.of(),
              false),
          group(
              "NHOM_4",
              "Cần xem xét bố trí",
              "Cán bộ cần được xem xét bố trí công tác theo tiêu chí của Phụ lục 5.",
              List.of(),
              false));

  private final PolicyPackService packs;
  private final ObjectMapper json;

  public ProspectCriteriaController(PolicyPackService packs, ObjectMapper json) {
    this.packs = Objects.requireNonNull(packs, "packs");
    this.json = Objects.requireNonNull(json, "json");
  }

  @GetMapping("/api/v1/policies/packs/{id}/prospect-criteria")
  @RequiresPermission(operationId = "getProspectCriteria", module = "policies")
  public ProspectCriteria getProspectCriteria(
      @PathVariable("id") UUID id,
      @RequestParam(value = "version", required = false) String version) {
    TenantId tenant = TenantContext.require();
    PolicyPackVersion found =
        version == null
            ? latestPublished(tenant, id)
            : packs
                .findVersion(tenant, id, version)
                .map(PolicyPackService.VersionView::version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    try {
      return criteria(id, found.version(), json.readTree(found.manifestJson()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Khai báo phiên bản " + found.version() + " hỏng", e);
    }
  }

  private PolicyPackVersion latestPublished(TenantId tenant, UUID id) {
    List<PolicyPackVersion> published;
    try {
      published = packs.listVersions(tenant, id, PolicyVersionStatus.DA_PHAT_HANH, null, 200);
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return published.stream()
        .max(
            Comparator.comparing(
                (PolicyPackVersion v) -> v.publishedAt() == null ? Instant.MIN : v.publishedAt()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  /** Tiêu chí của phiên bản {@code version} từ khai báo {@code manifest}. */
  static ProspectCriteria criteria(UUID packId, String version, JsonNode manifest) {
    JsonNode block = manifest.path("phan_nhom");
    List<ProspectCriteriaGroupsInner> groups = new ArrayList<>();
    for (JsonNode g : block.path("nhom")) {
      List<String> excluded = new ArrayList<>();
      g.path("excludedRatingLevels").forEach(r -> excluded.add(r.asText()));
      groups.add(
          group(
              g.path("groupCode").asText(),
              g.path("name").asText(),
              g.path("criteria").asText(),
              excluded,
              g.path("excludeReassignmentFlag").asBoolean(false)));
    }
    ProspectCriteria out =
        new ProspectCriteria(
            packId,
            version,
            openInQuarter(manifest, block),
            groups.isEmpty() ? DEFAULT_GROUPS : groups);
    out.setLegalRef(block.path("legal_ref").asText(LEGAL_REF));
    return out;
  }

  /** {@code mo_o_ky_quy} là giá trị hoặc tên tham số của gói (BR-10-07); thiếu là không mở. */
  private static boolean openInQuarter(JsonNode manifest, JsonNode block) {
    JsonNode open = block.path("mo_o_ky_quy");
    if (open.isBoolean()) {
      return open.asBoolean();
    }
    String param = open.isTextual() ? open.asText() : "phan_nhom.mo_o_ky_quy";
    return manifest.path("params").path(param).path("mac_dinh").asBoolean(false);
  }

  /**
   * Tên hiển thị "Nhóm 1 – Phát triển vượt trội" như ví dụ của hợp đồng; gói chỉ ghi phần sau dấu
   * gạch thì thêm số nhóm.
   */
  static String displayName(String code, String name) {
    if (name.startsWith("Nhóm ")) {
      return name;
    }
    return "Nhóm " + code.substring(code.length() - 1) + " – " + name;
  }

  private static ProspectCriteriaGroupsInner group(
      String code, String name, String criteria, List<String> excluded, boolean reassign) {
    ProspectCriteriaGroupsInner g =
        new ProspectCriteriaGroupsInner(
            PolicyProspectGroupCode.fromValue(code), displayName(code, name), criteria);
    g.setExcludedRatingLevels(excluded.stream().map(RatingLevel::fromValue).toList());
    g.setExcludeReassignmentFlag(reassign);
    return g;
  }
}
