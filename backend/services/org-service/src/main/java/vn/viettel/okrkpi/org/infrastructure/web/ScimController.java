package vn.viettel.okrkpi.org.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.scim.ScimException;
import vn.viettel.okrkpi.org.application.scim.ScimModels.Client;
import vn.viettel.okrkpi.org.application.scim.ScimModels.Created;
import vn.viettel.okrkpi.org.application.scim.ScimModels.GroupView;
import vn.viettel.okrkpi.org.application.scim.ScimModels.ListPage;
import vn.viettel.okrkpi.org.application.scim.ScimModels.UserView;
import vn.viettel.okrkpi.org.application.scim.ScimService;
import vn.viettel.okrkpi.org.infrastructure.tenant.TenantContextHolder;

/**
 * DV-04-06 · SCIM 2.0 ({@code contracts/openapi/modules/scim.yaml}, RFC 7644) cho HRM, IdP đã đăng ký của tenant.
 * Chỉ token client credentials có vai trò {@link #CLIENT_ROLE} gọi được (chuỗi bảo mật {@code /scim/v2/**}); tenant lấy
 * từ token, nguồn là client ({@code azp}). Tiêu đề {@code X-Tenant-Id} khác tenant của token bị từ chối 403.
 */
@RestController
@RequestMapping(value = "/scim/v2", produces = {ScimJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
@ConditionalOnWebApplication
public class ScimController {

  /** Vai trò realm của client SCIM (AC-11). */
  public static final String CLIENT_ROLE = "service_client";
  private static final MediaType SCIM = MediaType.parseMediaType(ScimJson.MEDIA_TYPE);
  private static final UUID SYSTEM_ACTOR = InternalSyncController.SYSTEM_ACTOR;

  private final ScimService scim;
  private final ScimJson mapper;
  private final ObjectMapper json;
  private final boolean authEnabled;
  private final UUID devTenantId;

  public ScimController(ScimService scim, ObjectMapper json,
      @Value("${okrkpi.org.auth-enabled:true}") boolean authEnabled,
      @Value("${okrkpi.org.dev-tenant-id:01960000-0000-7000-8000-000000000001}") UUID devTenantId) {
    this.scim = scim;
    this.json = json;
    this.mapper = new ScimJson(json);
    this.authEnabled = authEnabled;
    this.devTenantId = devTenantId;
  }

  // ------------------------------------------------------------- cấu hình

  @GetMapping("/ServiceProviderConfig")
  public ResponseEntity<JsonNode> serviceProviderConfig() {
    return ok(resource("service-provider-config.json"));
  }

  @GetMapping("/ResourceTypes")
  public ResponseEntity<JsonNode> resourceTypes() {
    ArrayNode types = (ArrayNode) resource("resource-types.json");
    return ok(listOf(types));
  }

  @GetMapping("/ResourceTypes/{name}")
  public ResponseEntity<JsonNode> resourceType(@PathVariable String name) {
    return ok(find((ArrayNode) resource("resource-types.json"), name));
  }

  @GetMapping("/Schemas")
  public ResponseEntity<JsonNode> schemas() {
    return ok(listOf((ArrayNode) resource("schemas.json")));
  }

  @GetMapping("/Schemas/{id}")
  public ResponseEntity<JsonNode> schema(@PathVariable String id) {
    return ok(find((ArrayNode) resource("schemas.json"), id));
  }

  // ------------------------------------------------------------------ Users

  @GetMapping("/Users")
  public ResponseEntity<JsonNode> listUsers(@RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
      @RequestParam(required = false) String filter, @RequestParam(defaultValue = "1") int startIndex,
      @RequestParam(defaultValue = "100") int count) {
    Client c = client(tenant);
    String[] f = ScimJson.filter(filter, Set.of("userName", "externalId"));
    ListPage<UserView> page = in(c, () -> scim.listUsers(c, f == null ? null : f[0], f == null ? null : f[1],
        startIndex, count));
    return ok(mapper.list(page.totalResults(), page.startIndex(),
        page.resources().stream().map(u -> mapper.user(u, base())).toList()));
  }

  @GetMapping("/Users/{id}")
  public ResponseEntity<JsonNode> getUser(@RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
      @PathVariable UUID id) {
    Client c = client(tenant);
    return user(HttpStatus.OK, in(c, () -> scim.getUser(c, id)));
  }

  @PostMapping("/Users")
  public ResponseEntity<JsonNode> createUser(@RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
      @RequestBody JsonNode body) {
    Client c = client(tenant);
    Created<UserView> out = in(c, () -> scim.createUser(c, mapper.user(body), hash(c, "POST /Users", body)));
    return user(HttpStatus.CREATED, out.value());
  }

  @PutMapping("/Users/{id}")
  public ResponseEntity<JsonNode> replaceUser(@RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, @PathVariable UUID id,
      @RequestBody JsonNode body) {
    Client c = client(tenant);
    return user(HttpStatus.OK, in(c, () -> scim.replaceUser(c, id, mapper.user(body), ScimJson.version(ifMatch),
        "REPLACE", hash(c, "PUT /Users/" + id, body))));
  }

  @PatchMapping("/Users/{id}")
  public ResponseEntity<JsonNode> patchUser(@RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, @PathVariable UUID id,
      @RequestBody JsonNode body) {
    Client c = client(tenant);
    return user(HttpStatus.OK, in(c, () -> {
      UserView current = scim.getUser(c, id);
      return scim.replaceUser(c, id, mapper.patchUser(current, body),
          ifMatch == null ? current.resource().version() : ScimJson.version(ifMatch), "PATCH",
          hash(c, "PATCH /Users/" + id, body));
    }));
  }

  @DeleteMapping("/Users/{id}")
  public ResponseEntity<Void> deleteUser(@RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
      @PathVariable UUID id) {
    Client c = client(tenant);
    in(c, () -> {
      scim.deleteUser(c, id, hash(c, "DELETE /Users/" + id, null));
      return null;
    });
    return ResponseEntity.noContent().build();
  }

  // ----------------------------------------------------------------- Groups

  @GetMapping("/Groups")
  public ResponseEntity<JsonNode> listGroups(@RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
      @RequestParam(required = false) String filter, @RequestParam(defaultValue = "1") int startIndex,
      @RequestParam(defaultValue = "100") int count) {
    Client c = client(tenant);
    String[] f = ScimJson.filter(filter, Set.of("displayName", "externalId"));
    ListPage<GroupView> page = in(c, () -> scim.listGroups(c, f == null ? null : f[0], f == null ? null : f[1],
        startIndex, count));
    return ok(mapper.list(page.totalResults(), page.startIndex(),
        page.resources().stream().map(g -> mapper.group(g, base())).toList()));
  }

  @GetMapping("/Groups/{id}")
  public ResponseEntity<JsonNode> getGroup(@RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
      @PathVariable UUID id) {
    Client c = client(tenant);
    return group(HttpStatus.OK, in(c, () -> scim.getGroup(c, id)));
  }

  @PostMapping("/Groups")
  public ResponseEntity<JsonNode> createGroup(@RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
      @RequestBody JsonNode body) {
    Client c = client(tenant);
    Created<GroupView> out = in(c, () -> scim.createGroup(c, mapper.group(body), hash(c, "POST /Groups", body)));
    return group(HttpStatus.CREATED, out.value());
  }

  @PutMapping("/Groups/{id}")
  public ResponseEntity<JsonNode> replaceGroup(@RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, @PathVariable UUID id,
      @RequestBody JsonNode body) {
    Client c = client(tenant);
    return group(HttpStatus.OK, in(c, () -> scim.replaceGroup(c, id, mapper.group(body), ScimJson.version(ifMatch),
        "REPLACE", hash(c, "PUT /Groups/" + id, body))));
  }

  @PatchMapping("/Groups/{id}")
  public ResponseEntity<JsonNode> patchGroup(@RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, @PathVariable UUID id,
      @RequestBody JsonNode body) {
    Client c = client(tenant);
    return group(HttpStatus.OK, in(c, () -> {
      GroupView current = scim.getGroup(c, id);
      return scim.replaceGroup(c, id, mapper.patchGroup(current, body),
          ifMatch == null ? current.resource().version() : ScimJson.version(ifMatch), "PATCH",
          hash(c, "PATCH /Groups/" + id, body));
    }));
  }

  @DeleteMapping("/Groups/{id}")
  public ResponseEntity<Void> deleteGroup(@RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
      @PathVariable UUID id) {
    Client c = client(tenant);
    in(c, () -> {
      scim.deleteGroup(c, id, hash(c, "DELETE /Groups/" + id, null));
      return null;
    });
    return ResponseEntity.noContent().build();
  }

  // ------------------------------------------------------------------- lỗi

  @ExceptionHandler(ScimException.class)
  public ResponseEntity<JsonNode> scimError(ScimException e) {
    return ResponseEntity.status(e.status()).contentType(SCIM).body(mapper.error(e.status(), e.scimType(),
        e.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<JsonNode> unreadable(HttpMessageNotReadableException e) {
    return scimError(new ScimException(400, "invalidSyntax", "Thân yêu cầu không đọc được."));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<JsonNode> badId(MethodArgumentTypeMismatchException e) {
    return scimError(ScimException.notFound());
  }

  // --------------------------------------------------------------- tiện ích

  private Client client(String headerTenant) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    UUID tenant;
    UUID subject = SYSTEM_ACTOR;
    String source;
    if (auth instanceof JwtAuthenticationToken token) {
      Jwt jwt = token.getToken();
      tenant = uuid(jwt.getClaimAsString("tenant_id"));
      if (tenant == null) {
        tenant = uuid(jwt.getClaimAsString("tenant"));
      }
      UUID sub = uuid(jwt.getSubject());
      subject = sub == null ? SYSTEM_ACTOR : sub;
      source = jwt.getClaimAsString("azp");
      if (source == null) {
        source = jwt.getClaimAsString("client_id");
      }
    } else if (!authEnabled) {
      UUID header = uuid(headerTenant);
      tenant = header == null ? devTenantId : header;
      source = "DEV";
    } else {
      throw new ScimException(401, null, "Chưa xác thực.");
    }
    if (tenant == null || source == null || source.isBlank() || source.length() > 32) {
      throw new ScimException(403, null, "Client SCIM chưa được gắn với tenant và nguồn.");
    }
    if (headerTenant != null && !headerTenant.isBlank() && !tenant.equals(uuid(headerTenant))) {
      throw new ScimException(403, null, "Client chỉ được ghi trong tenant của mình.");
    }
    return new Client(new Actor(tenant, subject, Set.of(CLIENT_ROLE), "aal1", Instant.now(), null, null), source);
  }

  private static <T> T in(Client c, Supplier<T> work) {
    TenantContextHolder.set(c.actor().tenantId());
    try {
      return work.get();
    } finally {
      TenantContextHolder.clear();
    }
  }

  private byte[] hash(Client c, String target, JsonNode body) {
    return scim.requestHash(c, target + "\n" + (body == null ? "" : body.toString()));
  }

  private ResponseEntity<JsonNode> user(HttpStatus status, UserView v) {
    ObjectNode body = mapper.user(v, base());
    return withMeta(status, body, ScimJson.etag(v.resource()), base() + "/Users/" + v.resource().id());
  }

  private ResponseEntity<JsonNode> group(HttpStatus status, GroupView v) {
    ObjectNode body = mapper.group(v, base());
    return withMeta(status, body, ScimJson.etag(v.resource()), base() + "/Groups/" + v.resource().id());
  }

  private static ResponseEntity<JsonNode> withMeta(HttpStatus status, JsonNode body, String etag, String location) {
    ResponseEntity.BodyBuilder b = ResponseEntity.status(status).contentType(SCIM).eTag(etag);
    if (status == HttpStatus.CREATED) {
      b.location(URI.create(location));
    }
    return b.body(body);
  }

  private static ResponseEntity<JsonNode> ok(JsonNode body) {
    return ResponseEntity.ok().contentType(SCIM).body(body);
  }

  private String base() {
    return ServletUriComponentsBuilder.fromCurrentContextPath().path("/scim/v2").toUriString();
  }

  private JsonNode resource(String name) {
    try (InputStream in = ScimController.class.getResourceAsStream("/scim/" + name)) {
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("{base}", base());
      return json.readTree(text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private JsonNode listOf(ArrayNode items) {
    List<ObjectNode> list = new java.util.ArrayList<>();
    items.forEach(i -> list.add((ObjectNode) i));
    return mapper.list(list.size(), 1, list);
  }

  private static JsonNode find(ArrayNode items, String id) {
    for (JsonNode i : items) {
      if (id.equals(i.path("id").asText())) {
        return i;
      }
    }
    throw ScimException.notFound();
  }

  private static UUID uuid(String value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
