package vn.viettel.okrkpi.config.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.config.application.ComponentRegistryService;
import vn.viettel.okrkpi.config.domain.UiComponent;
import vn.viettel.okrkpi.contract.config.model.Classification;
import vn.viettel.okrkpi.contract.config.model.UiComponentPage;
import vn.viettel.okrkpi.contract.config.model.UiComponentStatus;
import vn.viettel.okrkpi.shared.authz.RequiresPermission;
import vn.viettel.okrkpi.shared.paging.CursorCodec;
import vn.viettel.okrkpi.shared.paging.CursorKey;
import vn.viettel.okrkpi.shared.paging.CursorPage;
import vn.viettel.okrkpi.shared.paging.PageRequest;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Danh mục component SDUI của {@code config.yaml} (CORE-08-09, SCR-1801): {@code listUiComponents}.
 */
@RestController
@RequestMapping("/api/v1/ui/components")
public class UiComponentController {

  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private final ComponentRegistryService registry;
  private final CursorCodec cursors;
  private final ObjectMapper json;

  public UiComponentController(
      ComponentRegistryService registry, CursorCodec cursors, ObjectProvider<ObjectMapper> json) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.json = json.getIfAvailable(ObjectMapper::new);
  }

  @GetMapping
  @RequiresPermission(operationId = "listUiComponents", module = "config")
  public UiComponentPage listUiComponents(
      @RequestParam(value = "registry", required = false) String version,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "limit", required = false) Integer limit,
      @RequestParam(value = "cursor", required = false) String cursor) {
    UiComponent.Status s = status == null ? null : statusOf(status);
    PageRequest page =
        cursors.pageRequest(limit, cursor, "ui-components:" + version + ":" + status);
    List<UiComponent> fetched =
        registry.list(
            TenantContext.require(),
            version,
            s,
            page.afterKey().map(k -> k.getString(0)).orElse(null),
            page.fetchSize());
    CursorPage<UiComponent> result =
        cursors.page(fetched, page, c -> CursorKey.of(c.componentKey()));
    return new UiComponentPage(
        result.items().stream().map(this::toDto).toList(), result.nextCursor());
  }

  private vn.viettel.okrkpi.contract.config.model.UiComponent toDto(UiComponent c) {
    Map<String, Object> props;
    try {
      props = json.readValue(c.propsSchemaJson(), OBJECT);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("propsSchema hỏng: " + c.componentKey(), e);
    }
    vn.viettel.okrkpi.contract.config.model.UiComponent dto =
        new vn.viettel.okrkpi.contract.config.model.UiComponent(
            c.componentKey(),
            c.registryVersion(),
            props,
            Classification.fromValue(c.allowedClassification()),
            UiComponentStatus.fromValue(c.status().name()));
    dto.setContainer(c.container());
    dto.setMobile(c.mobile());
    dto.setPlugin(c.plugin());
    return dto;
  }

  private static UiComponent.Status statusOf(String status) {
    try {
      return UiComponent.Status.valueOf(status);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status không hợp lệ", e);
    }
  }
}
