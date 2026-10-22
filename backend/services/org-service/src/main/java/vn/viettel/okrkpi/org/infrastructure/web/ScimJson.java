package vn.viettel.okrkpi.org.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.org.application.port.ScimStore.Resource;
import vn.viettel.okrkpi.org.application.scim.ScimException;
import vn.viettel.okrkpi.org.application.scim.ScimModels.GroupInput;
import vn.viettel.okrkpi.org.application.scim.ScimModels.GroupView;
import vn.viettel.okrkpi.org.application.scim.ScimModels.UserInput;
import vn.viettel.okrkpi.org.application.scim.ScimModels.UserView;

/**
 * DV-04-06 · Chuyển đổi tài nguyên SCIM (RFC 7643) và áp lệnh PATCH (RFC 7644 mục 3.5.2) cho các thuộc tính nền tảng
 * hỗ trợ. Số định danh, ngày sinh có {@code returned: never}: đọc vào, không bao giờ ghi ra.
 */
final class ScimJson {

  static final String MEDIA_TYPE = "application/scim+json";
  static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
  static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
  static final String MISSION_USER = "urn:okrkpi:params:scim:schemas:extension:mission:2.0:User";
  static final String LIST = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
  static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
  static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";

  /** Bộ lọc được hỗ trợ: một phép {@code eq} trên thuộc tính đơn. */
  private static final Pattern FILTER = Pattern.compile("^\\s*([A-Za-z]+)\\s+(?i:eq)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$");
  private static final Pattern MEMBER_PATH = Pattern.compile("^members\\[\\s*value\\s+(?i:eq)\\s+\"([^\"]+)\"\\s*]$",
      Pattern.CASE_INSENSITIVE);
  private static final Map<String, String> USER_ATTRIBUTES = Map.of("username", "userName", "externalid",
      "externalId", "displayname", "displayName", "title", "title", "active", "active", "name.formatted",
      "name.formatted");
  private static final Map<String, String> EXTENSION_ATTRIBUTES = Map.of("nationalid", "nationalId", "birthdate",
      "birthDate", "partymember", "partyMember");

  private final ObjectMapper json;

  ScimJson(ObjectMapper json) {
    this.json = json;
  }

  /** Bộ lọc {@code attr eq "value"}; trả {@code [canonicalAttr, value]} hoặc null khi không lọc. */
  static String[] filter(String filter, Set<String> allowed) {
    if (filter == null || filter.isBlank()) {
      return null;
    }
    Matcher m = FILTER.matcher(filter);
    if (!m.matches()) {
      throw new ScimException(400, "invalidFilter", "Bộ lọc không được hỗ trợ.");
    }
    for (String a : allowed) {
      if (a.equalsIgnoreCase(m.group(1))) {
        return new String[] {a, m.group(2).replaceAll("\\\\(.)", "$1")};
      }
    }
    throw new ScimException(400, "invalidFilter", "Bộ lọc không được hỗ trợ.");
  }

  // ------------------------------------------------------------------- User

  UserInput user(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new ScimException(400, "invalidSyntax", "Thân yêu cầu không phải đối tượng JSON.");
    }
    JsonNode ext = body.path(MISSION_USER);
    String displayName = text(body, "displayName");
    if (displayName == null) {
      displayName = text(body.path("name"), "formatted");
    }
    LocalDate birth = null;
    String birthText = text(ext, "birthDate");
    if (birthText != null) {
      try {
        birth = LocalDate.parse(birthText.length() > 10 ? birthText.substring(0, 10) : birthText);
      } catch (DateTimeParseException e) {
        throw ScimException.invalidValue("Ngày sinh không hợp lệ.");
      }
    }
    JsonNode active = body.get("active");
    JsonNode party = ext.get("partyMember");
    return new UserInput(text(body, "externalId"), text(body, "userName"), displayName, text(body, "title"),
        active == null || active.isNull() || active.asBoolean(true), text(ext, "nationalId"), birth,
        party == null || party.isNull() ? null : party.asBoolean());
  }

  ObjectNode user(UserView v, String baseUrl) {
    Resource r = v.resource();
    ObjectNode out = json.createObjectNode();
    out.putArray("schemas").add(USER_SCHEMA).add(MISSION_USER);
    out.put("id", r.id().toString());
    out.put("externalId", r.externalId());
    out.put("userName", r.userName());
    if (r.displayName() != null) {
      out.put("displayName", r.displayName());
      out.putObject("name").put("formatted", r.displayName());
    }
    if (r.titleCode() != null) {
      out.put("title", r.titleCode());
    }
    out.put("active", r.active());
    ObjectNode ext = out.putObject(MISSION_USER);
    ext.put("partyMember", v.partyMember());
    if (v.employmentStatus() != null) {
      ext.put("employmentStatus", v.employmentStatus());
    }
    meta(out, r, "User", baseUrl + "/Users/" + r.id());
    return out;
  }

  /** Áp PATCH lên bản trình bày hiện hành (đã thêm lại các thuộc tính chỉ ghi) rồi đọc thành bộ thuộc tính đầy đủ. */
  UserInput patchUser(UserView current, JsonNode patch) {
    ObjectNode doc = user(current, "");
    for (JsonNode op : operations(patch)) {
      String kind = op.path("op").asText("").toLowerCase(Locale.ROOT);
      String path = op.hasNonNull("path") ? op.get("path").asText() : null;
      JsonNode value = op.get("value");
      if (path == null) {
        if (!"remove".equals(kind) && value != null && value.isObject()) {
          for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().equalsIgnoreCase(MISSION_USER) && e.getValue().isObject()) {
              e.getValue().fields().forEachRemaining(x -> setUser(doc, MISSION_USER + ":" + x.getKey(), x.getValue()));
            } else {
              setUser(doc, e.getKey(), e.getValue());
            }
          }
          continue;
        }
        throw new ScimException(400, "noTarget", "Lệnh PATCH thiếu đường dẫn.");
      }
      switch (kind) {
        case "add", "replace" -> setUser(doc, path, value);
        case "remove" -> setUser(doc, path, null);
        default -> throw new ScimException(400, "invalidSyntax", "Phép PATCH không hợp lệ.");
      }
    }
    return user(doc);
  }

  private void setUser(ObjectNode doc, String path, JsonNode value) {
    String p = path.trim();
    if (p.regionMatches(true, 0, MISSION_USER + ":", 0, MISSION_USER.length() + 1)) {
      String attr = EXTENSION_ATTRIBUTES.get(p.substring(MISSION_USER.length() + 1).toLowerCase(Locale.ROOT));
      if (attr == null) {
        throw new ScimException(400, "invalidPath", "Thuộc tính không được hỗ trợ.");
      }
      set(doc.with(MISSION_USER), attr, value);
      return;
    }
    if (p.regionMatches(true, 0, USER_SCHEMA + ":", 0, USER_SCHEMA.length() + 1)) {
      p = p.substring(USER_SCHEMA.length() + 1);
    }
    String attr = USER_ATTRIBUTES.get(p.toLowerCase(Locale.ROOT));
    if (attr == null) {
      if ("name".equalsIgnoreCase(p) && value != null && value.isObject()) {
        setUser(doc, "name.formatted", value.get("formatted"));
        return;
      }
      throw new ScimException(400, "invalidPath", "Thuộc tính không được hỗ trợ.");
    }
    if ("userName".equals(attr) && (value == null || value.isNull())) {
      throw new ScimException(400, "mutability", "userName là thuộc tính bắt buộc.");
    }
    if ("name.formatted".equals(attr) || "displayName".equals(attr)) {
      set(doc, "displayName", value);
      doc.remove("name");
      return;
    }
    if ("externalId".equals(attr)) {
      return; // externalId là khoá ánh xạ của nguồn, không đổi qua PATCH (suy luận)
    }
    set(doc, attr, value);
  }

  // ------------------------------------------------------------------ Group

  GroupInput group(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new ScimException(400, "invalidSyntax", "Thân yêu cầu không phải đối tượng JSON.");
    }
    return new GroupInput(text(body, "externalId"), text(body, "displayName"), memberIds(body.get("members")));
  }

  ObjectNode group(GroupView v, String baseUrl) {
    Resource r = v.resource();
    ObjectNode out = json.createObjectNode();
    out.putArray("schemas").add(GROUP_SCHEMA);
    out.put("id", r.id().toString());
    out.put("externalId", r.externalId());
    out.put("displayName", r.displayName() == null ? r.externalId() : r.displayName());
    ArrayNode members = out.putArray("members");
    for (UUID m : v.members()) {
      members.addObject().put("value", m.toString()).put("$ref", baseUrl + "/Users/" + m).put("type", "User");
    }
    meta(out, r, "Group", baseUrl + "/Groups/" + r.id());
    return out;
  }

  GroupInput patchGroup(GroupView current, JsonNode patch) {
    Set<UUID> members = new LinkedHashSet<>(current.members());
    String displayName = current.resource().displayName();
    for (JsonNode op : operations(patch)) {
      String kind = op.path("op").asText("").toLowerCase(Locale.ROOT);
      String path = op.hasNonNull("path") ? op.get("path").asText().trim() : null;
      JsonNode value = op.get("value");
      if (path == null && value != null && value.isObject() && !"remove".equals(kind)) {
        if (value.has("displayName")) {
          displayName = value.get("displayName").asText();
        }
        if (value.has("members")) {
          if ("replace".equals(kind)) {
            members.clear();
          }
          members.addAll(memberIds(value.get("members")));
        }
        continue;
      }
      if (path == null) {
        throw new ScimException(400, "noTarget", "Lệnh PATCH thiếu đường dẫn.");
      }
      Matcher one = MEMBER_PATH.matcher(path);
      if ("displayName".equalsIgnoreCase(path) && !"remove".equals(kind)) {
        displayName = value == null ? null : value.asText();
      } else if ("members".equalsIgnoreCase(path)) {
        switch (kind) {
          case "add" -> members.addAll(memberIds(value));
          case "replace" -> {
            members.clear();
            members.addAll(memberIds(value));
          }
          case "remove" -> {
            if (value == null || value.isNull()) {
              members.clear();
            } else {
              memberIds(value).forEach(members::remove);
            }
          }
          default -> throw new ScimException(400, "invalidSyntax", "Phép PATCH không hợp lệ.");
        }
      } else if (one.matches() && "remove".equals(kind)) {
        members.remove(uuid(one.group(1)));
      } else {
        throw new ScimException(400, "invalidPath", "Thuộc tính không được hỗ trợ.");
      }
    }
    return new GroupInput(current.resource().externalId(), displayName, new ArrayList<>(members));
  }

  // ------------------------------------------------------------------ chung

  ObjectNode list(int total, int startIndex, List<ObjectNode> items) {
    ObjectNode out = json.createObjectNode();
    out.putArray("schemas").add(LIST);
    out.put("totalResults", total);
    out.put("startIndex", startIndex);
    out.put("itemsPerPage", items.size());
    ArrayNode resources = out.putArray("Resources");
    items.forEach(resources::add);
    return out;
  }

  ObjectNode error(int status, String scimType, String detail) {
    ObjectNode out = json.createObjectNode();
    out.putArray("schemas").add(ERROR);
    out.put("status", String.valueOf(status));
    if (scimType != null) {
      out.put("scimType", scimType);
    }
    out.put("detail", detail);
    return out;
  }

  static String etag(Resource r) {
    return "W/\"" + r.version() + "\"";
  }

  /** If-Match: {@code W/"n"} hoặc {@code "n"}; {@code *} hay vắng là không kiểm. */
  static Integer version(String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
      return null;
    }
    String v = ifMatch.trim().replaceFirst("^W/", "").replace("\"", "");
    try {
      return Integer.parseInt(v);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static void meta(ObjectNode out, Resource r, String type, String location) {
    ObjectNode meta = out.putObject("meta");
    meta.put("resourceType", type);
    if (r.created() != null) {
      meta.put("created", r.created().toString());
    }
    if (r.lastModified() != null) {
      meta.put("lastModified", r.lastModified().toString());
    }
    meta.put("location", location);
    meta.put("version", etag(r));
  }

  private static List<JsonNode> operations(JsonNode patch) {
    JsonNode ops = patch == null ? null : patch.get("Operations");
    if (ops == null || !ops.isArray() || ops.isEmpty()) {
      throw new ScimException(400, "invalidSyntax", "Thiếu danh sách Operations của lệnh PATCH.");
    }
    List<JsonNode> out = new ArrayList<>();
    ops.forEach(out::add);
    return out;
  }

  private static List<UUID> memberIds(JsonNode members) {
    List<UUID> out = new ArrayList<>();
    if (members == null || members.isNull()) {
      return out;
    }
    if (!members.isArray()) {
      throw ScimException.invalidValue("members phải là danh sách.");
    }
    for (JsonNode m : members) {
      out.add(uuid(m.path("value").asText(null)));
    }
    return out;
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw ScimException.invalidValue("Mã thành viên không hợp lệ.");
    }
  }

  private static void set(ObjectNode node, String attr, JsonNode value) {
    if (value == null || value.isNull()) {
      node.remove(attr);
    } else {
      node.set(attr, value);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node == null ? null : node.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }
}
