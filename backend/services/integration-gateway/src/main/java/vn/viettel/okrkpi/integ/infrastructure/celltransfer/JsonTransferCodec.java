package vn.viettel.okrkpi.integ.infrastructure.celltransfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import vn.viettel.okrkpi.integ.application.port.TransferCodec;

/**
 * Định dạng gói chuyển liên cell (DV-13-10, suy luận): danh mục và phần đầu là JSON UTF-8 giữ
 * nguyên byte khi ký; khối phần là ZIP có mục {@value #INDEX} liệt kê tên, loại, kiểu nội dung theo
 * thứ tự, chặn tên lạ, mục trùng và tổng cỡ giải nén vượt trần (chống bom nén).
 */
public class JsonTransferCodec implements TransferCodec {

  static final String INDEX = "parts.json";
  static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
  static final Pattern CODE = Pattern.compile("[A-Z0-9_-]{1,32}");
  static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

  private final ObjectMapper json;
  private final long maxBytes;

  public JsonTransferCodec(ObjectMapper json, long maxBytes) {
    this.json = json;
    this.maxBytes = maxBytes;
  }

  @Override
  public String manifest(List<Part> parts) {
    ObjectNode root = json.createObjectNode().put("version", 1);
    ArrayNode items = root.putArray("parts");
    for (Part p : parts) {
      items
          .addObject()
          .put("name", p.name())
          .put("kind", p.kind())
          .put("mediaType", p.mediaType())
          .put("size", p.content().length)
          .put("sha256", HexFormat.of().formatHex(sha256(p.content())));
    }
    return write(root);
  }

  @Override
  public Map<String, String> manifestDigests(String manifest) {
    JsonNode root = read(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Map<String, String> out = new LinkedHashMap<>();
    for (JsonNode p : root.path("parts")) {
      String name = p.path("name").asText("");
      String digest = p.path("sha256").asText("");
      if (!NAME.matcher(name).matches()
          || !HEX.matcher(digest).matches()
          || out.put(name, digest) != null) {
        throw new MalformedException("Danh mục gói không hợp lệ", null);
      }
    }
    return out;
  }

  @Override
  public byte[] header(Header h) {
    ObjectNode root =
        json.createObjectNode()
            .put("version", h.version())
            .put("packageId", h.packageId().toString())
            .put("sourceCell", h.sourceCell())
            .put("sourceTenantId", h.sourceTenantId().toString())
            .put("sourceTenantCode", h.sourceTenantCode())
            .put("targetCell", h.targetCell())
            .put("targetTenantId", h.targetTenantId().toString())
            .put("targetTenantCode", h.targetTenantCode())
            .put("personId", h.personId().toString())
            .put("manifest", h.manifest())
            .put("packageSha256", h.packageSha256())
            .put("wrappedKeySha256", h.wrappedKeySha256());
    return write(root).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Override
  public Header parseHeader(byte[] header) {
    if (header == null || header.length > 256 * 1024) {
      throw new MalformedException("Phần đầu gói thiếu hoặc quá lớn", null);
    }
    JsonNode n = read(header);
    try {
      Header h =
          new Header(
              n.path("version").asInt(-1),
              UUID.fromString(n.path("packageId").asText()),
              code(n, "sourceCell"),
              UUID.fromString(n.path("sourceTenantId").asText()),
              code(n, "sourceTenantCode"),
              code(n, "targetCell"),
              UUID.fromString(n.path("targetTenantId").asText()),
              code(n, "targetTenantCode"),
              UUID.fromString(n.path("personId").asText()),
              n.path("manifest").asText(""),
              hex(n, "packageSha256"),
              hex(n, "wrappedKeySha256"));
      manifestDigests(h.manifest());
      return h;
    } catch (IllegalArgumentException e) {
      throw new MalformedException("Phần đầu gói không hợp lệ", e);
    }
  }

  @Override
  public byte[] bundle(List<Part> parts) {
    ArrayNode index = json.createArrayNode();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Part p : parts) {
        if (!NAME.matcher(p.name()).matches() || INDEX.equals(p.name())) {
          throw new IllegalArgumentException("Tên phần hồ sơ không hợp lệ");
        }
        index
            .addObject()
            .put("name", p.name())
            .put("kind", p.kind())
            .put("mediaType", p.mediaType());
      }
      zip.putNextEntry(new ZipEntry(INDEX));
      zip.write(json.writeValueAsBytes(index));
      zip.closeEntry();
      for (Part p : parts) {
        zip.putNextEntry(new ZipEntry(p.name()));
        zip.write(p.content());
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new IllegalStateException("Không dựng được khối phần", e);
    }
    return out.toByteArray();
  }

  @Override
  public List<Part> unbundle(byte[] bundle) {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    long total = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bundle))) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        String name = e.getName();
        if (!NAME.matcher(name).matches() || entries.containsKey(name)) {
          throw new MalformedException("Mục lạ hoặc trùng trong khối phần", null);
        }
        byte[] content = readLimited(zip, maxBytes - total);
        total += content.length;
        entries.put(name, content);
      }
    } catch (IOException e) {
      throw new MalformedException("Khối phần không đọc được", e);
    }
    byte[] indexBytes = entries.remove(INDEX);
    if (indexBytes == null) {
      throw new MalformedException("Khối phần thiếu mục lục", null);
    }
    List<Part> parts = new ArrayList<>();
    for (JsonNode p : read(indexBytes)) {
      byte[] content = entries.remove(p.path("name").asText(""));
      if (content == null) {
        throw new MalformedException("Mục lục trỏ tới phần không có", null);
      }
      parts.add(
          new Part(
              p.path("name").asText(),
              p.path("kind").asText(),
              p.path("mediaType").asText("application/octet-stream"),
              content));
    }
    if (!entries.isEmpty()) {
      throw new MalformedException("Khối phần có phần ngoài mục lục", null);
    }
    return parts;
  }

  @Override
  public byte[] outcome(Outcome o) {
    ObjectNode root =
        json.createObjectNode()
            .put("packageId", o.packageId().toString())
            .put("sourceTenantId", o.sourceTenantId().toString())
            .put("cell", o.cell())
            .put("status", o.status());
    if (o.reason() != null) {
      root.put("reason", o.reason());
    }
    return write(root).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Override
  public Outcome parseOutcome(byte[] outcome) {
    if (outcome == null || outcome.length > 4096) {
      throw new MalformedException("Kết quả nhập thiếu hoặc quá lớn", null);
    }
    JsonNode n = read(outcome);
    try {
      return new Outcome(
          UUID.fromString(n.path("packageId").asText()),
          UUID.fromString(n.path("sourceTenantId").asText()),
          code(n, "cell"),
          code(n, "status"),
          n.hasNonNull("reason") ? code(n, "reason") : null);
    } catch (IllegalArgumentException e) {
      throw new MalformedException("Kết quả nhập không hợp lệ", e);
    }
  }

  private static String code(JsonNode n, String field) {
    String v = n.path(field).asText("");
    if (!CODE.matcher(v).matches()) {
      throw new IllegalArgumentException("Trường " + field + " không hợp lệ");
    }
    return v;
  }

  private static String hex(JsonNode n, String field) {
    String v = n.path(field).asText("");
    if (!HEX.matcher(v).matches()) {
      throw new IllegalArgumentException("Trường " + field + " không hợp lệ");
    }
    return v;
  }

  private static byte[] readLimited(InputStream in, long limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    long read = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      read += n;
      if (read > limit) {
        throw new MalformedException("Khối phần giải nén vượt trần", null);
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private String write(JsonNode node) {
    try {
      return json.writeValueAsString(node);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode read(byte[] bytes) {
    try {
      JsonNode n = json.readTree(bytes);
      if (n == null || n.isMissingNode()) {
        throw new MalformedException("JSON rỗng", null);
      }
      return n;
    } catch (IOException e) {
      throw new MalformedException("JSON không đọc được", e);
    }
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
