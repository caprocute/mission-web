package vn.viettel.okrkpi.shared.outbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.avro.Schema;

/**
 * Máy chủ giả lập Apicurio Registry v3 tối giản phục vụ kiểm thử đơn vị và tích hợp của {@code
 * ApicurioPayloadSerializer} và {@code ApicurioPayloadDeserializer}.
 */
public final class MockApicurioRegistryServer implements AutoCloseable {

  private final HttpServer server;
  private final AtomicLong nextGlobalId = new AtomicLong(100L);
  private final Map<Long, String> schemasByGlobalId = new ConcurrentHashMap<>();
  private final Map<String, Long> globalIdByArtifact = new ConcurrentHashMap<>();
  private final AtomicBoolean rejectIncompatible = new AtomicBoolean(false);

  public MockApicurioRegistryServer() {
    try {
      this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      this.server.createContext("/apis/registry/v3", this::handle);
      this.server.start();
    } catch (IOException e) {
      throw new IllegalStateException("Không khởi tạo được MockApicurioRegistryServer", e);
    }
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/apis/registry/v3";
  }

  public String url() {
    return baseUrl();
  }

  public long register(String groupId, String artifactId, Schema schema) {
    return registerAvroSchema(groupId, artifactId, schema);
  }

  public long register(String artifactId, Schema schema) {
    return registerAvroSchema("default", artifactId, schema);
  }

  public long registerAvroSchema(String artifactId, Schema schema) {
    return registerAvroSchema("default", artifactId, schema);
  }

  public long registerAvroSchema(String groupId, String artifactId, Schema schema) {
    long globalId = nextGlobalId.incrementAndGet();
    String raw = schema.toString();
    schemasByGlobalId.put(globalId, raw);
    globalIdByArtifact.put(groupId + ":" + artifactId, globalId);
    globalIdByArtifact.put(artifactId, globalId);
    return globalId;
  }

  public void setRejectIncompatible(boolean reject) {
    this.rejectIncompatible.set(reject);
  }

  public void rejectAllLookups(boolean reject) {
    setRejectIncompatible(reject);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();

    if (rejectIncompatible.get()) {
      respond(
          exchange,
          409,
          "application/json",
          "{\"error_code\":409,\"message\":\"Incompatible schema\"}");
      return;
    }

    if (path.endsWith("/references")) {
      respond(exchange, 200, "application/json", "[]");
      return;
    }

    if (path.endsWith("/search/versions")) {
      String query = exchange.getRequestURI().getQuery();
      String groupId = queryParam(query, "groupId", "default");
      String artifactId = queryParam(query, "artifactId", "");
      Long globalId = globalIdByArtifact.get(groupId + ":" + artifactId);
      if (globalId == null) {
        globalId = globalIdByArtifact.get(artifactId);
      }
      if (globalId == null) {
        respond(exchange, 200, "application/json", "{\"count\":0,\"versions\":[]}");
        return;
      }
      String searchResults =
          String.format(
              "{\"count\":1,\"versions\":[{\"globalId\":%d,\"contentId\":%d,\"version\":\"1\","
                  + "\"groupId\":\"%s\",\"artifactId\":\"%s\",\"artifactType\":\"AVRO\","
                  + "\"state\":\"ENABLED\"}]}",
              globalId, globalId, groupId, artifactId);
      respond(exchange, 200, "application/json", searchResults);
      return;
    }

    if ("GET".equalsIgnoreCase(method) && path.contains("/ids/globalIds/")) {
      String idPart = path.substring(path.lastIndexOf('/') + 1);
      long globalId = Long.parseLong(idPart);
      String schema = schemasByGlobalId.get(globalId);
      if (schema == null) {
        respond(exchange, 404, "application/json", "{\"error_code\":404}");
        return;
      }
      respond(exchange, 200, "application/json", schema);
      return;
    }

    if ("GET".equalsIgnoreCase(method) && path.contains("/ids/contentIds/")) {
      String idPart = path.substring(path.lastIndexOf('/') + 1);
      long contentId = Long.parseLong(idPart);
      String schema = schemasByGlobalId.get(contentId);
      if (schema == null) {
        respond(exchange, 404, "application/json", "{\"error_code\":404}");
        return;
      }
      respond(exchange, 200, "application/json", schema);
      return;
    }

    if (path.contains("/groups/") && path.contains("/artifacts")) {
      String afterGroups = path.substring(path.indexOf("/groups/") + "/groups/".length());
      String[] segments = afterGroups.split("/");
      String groupId = segments.length > 0 ? segments[0] : "default";
      String artifactId = segments.length > 2 ? segments[2] : "unknown";

      Long globalId = globalIdByArtifact.get(groupId + ":" + artifactId);
      if (globalId == null) {
        globalId = globalIdByArtifact.get(artifactId);
      }

      if (globalId == null) {
        respond(
            exchange,
            404,
            "application/json",
            "{\"error_code\":404,\"message\":\"Artifact not found\"}");
        return;
      }

      String versionMeta =
          String.format(
              "{\"globalId\":%d,\"contentId\":%d,\"version\":\"1\",\"versionOrder\":1,"
                  + "\"groupId\":\"%s\",\"artifactId\":\"%s\",\"type\":\"AVRO\"}",
              globalId, globalId, groupId, artifactId);
      respond(exchange, 200, "application/json", versionMeta);
      return;
    }

    respond(exchange, 404, "application/json", "{\"error_code\":404}");
  }

  private static String queryParam(String query, String key, String defaultValue) {
    if (query == null || query.isEmpty()) {
      return defaultValue;
    }
    String prefix = key + "=";
    for (String part : query.split("&")) {
      if (part.startsWith(prefix)) {
        return java.net.URLDecoder.decode(part.substring(prefix.length()), StandardCharsets.UTF_8);
      }
    }
    return defaultValue;
  }

  private static void respond(
      HttpExchange exchange, int status, String contentType, String bodyText) throws IOException {
    byte[] bytes = bodyText.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
