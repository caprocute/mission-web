package vn.viettel.okrkpi.org.domain;

import java.util.List;
import java.util.UUID;

/** Nội dung nghiệp vụ của một phiên bản đơn vị ({@code org.org_unit}, các cột C0/C1). */
public record OrgUnitContent(
    String code,
    String name,
    String shortName,
    String unitType,
    UUID subjectGroupId,
    boolean collectiveSubject,
    List<String> flows,
    String level) {

  public OrgUnitContent {
    flows = flows == null ? List.of() : List.copyOf(flows);
  }

  public OrgUnitContent withCollectiveSubject(boolean value) {
    return new OrgUnitContent(code, name, shortName, unitType, subjectGroupId, value, flows, level);
  }

  public OrgUnitContent withName(String newName) {
    return new OrgUnitContent(code, newName, shortName, unitType, subjectGroupId, collectiveSubject, flows, level);
  }
}
