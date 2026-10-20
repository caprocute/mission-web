package vn.viettel.okrkpi.org.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.OrgUnitCommandService;
import vn.viettel.okrkpi.org.application.OrgUnitHistoryItem;
import vn.viettel.okrkpi.org.application.OrgUnitQueryService;
import vn.viettel.okrkpi.org.application.OrgUnitView;
import vn.viettel.okrkpi.org.application.Page;
import vn.viettel.okrkpi.org.application.UnitRef;
import vn.viettel.okrkpi.org.domain.TreeKind;
import vn.viettel.okrkpi.org.infrastructure.security.ActorFilter;
import vn.viettel.okrkpi.org.infrastructure.web.api.OrgApi;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgUnitHistory200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgUnits200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListSubjectGroups200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgTree;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgTreeKind;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnit;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnitInput;
import vn.viettel.okrkpi.org.infrastructure.web.model.SearchOrgUnits200Response;

/**
 * Hiện thực giao diện {@link OrgApi} sinh từ {@code contracts/openapi/modules/org.yaml} (ADR-32, interfaceOnly) cho
 * các thao tác cây tổ chức của QT-01, SCR-0103. Thao tác chưa thuộc phạm vi (phân công, nhập Excel, đồng bộ) giữ
 * hiện thực mặc định 501 của giao diện sinh.
 */
@RestController
@RequestMapping("/api/v1")
public class OrgController implements OrgApi {

  private final OrgUnitQueryService queries;
  private final OrgUnitCommandService commands;
  private final RequestHasher hasher;
  private final HttpServletRequest request;

  public OrgController(OrgUnitQueryService queries, OrgUnitCommandService commands, RequestHasher hasher, HttpServletRequest request) {
    this.queries = queries;
    this.commands = commands;
    this.hasher = hasher;
    this.request = request;
  }

  @Override
  public ResponseEntity<OrgUnit> getOrgUnit(UUID id, LocalDate asOf, OffsetDateTime recordedAsOf) {
    OrgUnitView view = queries.getUnit(actor(), id, asOf, recordedAsOf);
    return ResponseEntity.ok().eTag(etag(view.rowVersion())).body(OrgWebMapper.toDto(view));
  }

  @Override
  public ResponseEntity<OrgUnit> updateOrgUnit(String ifMatch, String idempotencyKey, UUID id, OrgUnitInput input) {
    int expected = IfMatch.parse(ifMatch);
    OrgUnitView view = commands.update(actor(), id, expected,
        hasher.of(idempotencyKey, "PUT", request.getRequestURI(), input), OrgWebMapper.toCommand(input));
    return ResponseEntity.ok().eTag(etag(view.rowVersion())).body(OrgWebMapper.toDto(view));
  }

  @Override
  public ResponseEntity<OrgTree> getOrgTree(LocalDate asOf, OffsetDateTime recordedAsOf, OrgTreeKind tree,
      Integer depth) {
    TreeKind kind = tree == null ? null : TreeKind.valueOf(tree.getValue());
    return ResponseEntity.ok(OrgWebMapper.toDto(queries.getTree(actor(), asOf, recordedAsOf, kind,
        depth == null ? 3 : depth)));
  }

  @Override
  public ResponseEntity<OrgTree> getOrgSubtree(UUID id, LocalDate asOf, OffsetDateTime recordedAsOf, Integer depth) {
    return ResponseEntity.ok(OrgWebMapper.toDto(queries.getSubtree(actor(), id, asOf, recordedAsOf,
        depth == null ? 2 : depth)));
  }

  @Override
  public ResponseEntity<SearchOrgUnits200Response> searchOrgUnits(String q, LocalDate asOf,
      OffsetDateTime recordedAsOf, OrgTreeKind tree, Integer limit) {
    TreeKind kind = tree == null ? null : TreeKind.valueOf(tree.getValue());
    SearchOrgUnits200Response body = new SearchOrgUnits200Response();
    body.setItems(queries.search(actor(), q, asOf, recordedAsOf, kind, limit == null ? 20 : limit).stream()
        .map(OrgWebMapper::toDto).toList());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<ListOrgUnits200Response> listOrgUnits(String scope, String q, Integer limit, String cursor) {
    Page<UnitRef> page = queries.listUnits(actor(), scope, q, limit == null ? 20 : limit, cursor);
    ListOrgUnits200Response body = new ListOrgUnits200Response();
    body.setItems(page.items().stream().map(OrgWebMapper::toDto).toList());
    body.setNextCursor(page.nextCursor());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<ListOrgUnitHistory200Response> listOrgUnitHistory(UUID id, OffsetDateTime recordedAsOf,
      Integer limit, String cursor) {
    Page<OrgUnitHistoryItem> page = queries.history(actor(), id, recordedAsOf, limit == null ? 20 : limit, cursor);
    ListOrgUnitHistory200Response body = new ListOrgUnitHistory200Response();
    body.setItems(page.items().stream().map(OrgWebMapper::toDto).toList());
    body.setNextCursor(page.nextCursor());
    return ResponseEntity.ok(body);
  }

  @Override
  public ResponseEntity<ListSubjectGroups200Response> listSubjectGroups(LocalDate asOf) {
    ListSubjectGroups200Response body = new ListSubjectGroups200Response();
    body.setItems(queries.listSubjectGroups(actor(), asOf).stream().map(OrgWebMapper::toDto).toList());
    return ResponseEntity.ok(body);
  }

  private Actor actor() {
    Object actor = request.getAttribute(ActorFilter.ATTRIBUTE);
    if (actor == null) {
      throw new IllegalStateException("Thiếu chủ thể đã xác thực");
    }
    return (Actor) actor;
  }

  private static String etag(int rowVersion) {
    return "\"" + rowVersion + "\"";
  }

}
