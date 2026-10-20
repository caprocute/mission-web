package vn.viettel.okrkpi.contract.test.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;
import org.openapitools.openapidiff.core.model.ChangedOperation;
import org.openapitools.openapidiff.core.model.Endpoint;
import vn.viettel.okrkpi.contract.application.OpenApiContractRegistry;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.domain.ConformanceReport;
import vn.viettel.okrkpi.contract.test.domain.ContractViolation;

/**
 * Động cơ kiểm thử tuân thủ hợp đồng OpenAPI 3.1 bằng {@code openapi-diff-core}, kiểm quy ước mục
 * 13 baseline và đối chiếu phản hồi thực tế với schema/examples (ADR-32, NEN-006).
 */
public final class OpenApiConformanceEngine {

  private static final String API_V1_PREFIX = "/api/v1";
  private static final String PROBLEM_MEDIA_TYPE = "application/problem+json";
  private static final String JSON_MEDIA_TYPE = "application/json";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private OpenApiConformanceEngine() {}

  /** Nạp hợp đồng chuẩn của một mô-đun từ {@code contracts/dist/<module>.yaml}. */
  public static OpenAPI loadExpectedSpec(ContractModule module) {
    Path specPath = OpenApiContractRegistry.resolveContractSpecPath(module, Path.of("."));
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    SwaggerParseResult result =
        new OpenAPIV3Parser().readLocation(specPath.toString(), null, options);
    if (result.getOpenAPI() == null) {
      throw new IllegalStateException(
          "Không đọc được hợp đồng OpenAPI tại " + specPath + ": " + result.getMessages());
    }
    return normalizeOpenApi31Types(result.getOpenAPI());
  }

  /** Phân tích tài liệu OpenAPI JSON/YAML thực tế (từ {@code /v3/api-docs}). */
  public static OpenAPI parseSpecContent(String content) {
    Objects.requireNonNull(content, "content must not be null");
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    SwaggerParseResult result = new OpenAPIV3Parser().readContents(content, null, options);
    if (result.getOpenAPI() == null) {
      throw new IllegalArgumentException(
          "Tài liệu OpenAPI thực tế không hợp lệ: " + result.getMessages());
    }
    return normalizeOpenApi31Types(result.getOpenAPI());
  }

  /**
   * Đối chiếu tài liệu OpenAPI thực tế ({@code /v3/api-docs}) với hợp đồng chuẩn của mô-đun theo
   * phạm vi {@code x-mission.owner}.
   */
  public static ConformanceReport verifyConformance(
      ContractModule module, String ownerFilter, String actualOpenApiContent) {
    OpenAPI expected = loadExpectedSpec(module);
    OpenAPI actual = parseSpecContent(actualOpenApiContent);
    return verifyConformance(module, ownerFilter, expected, actual);
  }

  /**
   * Đối chiếu đối tượng {@link OpenAPI} thực tế với hợp đồng chuẩn của mô-đun theo phạm vi {@code
   * x-mission.owner}.
   */
  public static ConformanceReport verifyConformance(
      ContractModule module, String ownerFilter, OpenAPI expectedSpec, OpenAPI actualSpec) {
    Objects.requireNonNull(module, "module must not be null");
    Objects.requireNonNull(expectedSpec, "expectedSpec must not be null");
    Objects.requireNonNull(actualSpec, "actualSpec must not be null");

    String normalizedOwner =
        (ownerFilter == null || ownerFilter.isBlank()) ? "*" : ownerFilter.trim();
    normalizeOpenApi31Types(expectedSpec);
    normalizeOpenApi31Types(actualSpec);
    OpenAPI filteredExpected = filterByOwner(expectedSpec, normalizedOwner);
    OpenAPI normalizedActual = normalizeActualPaths(actualSpec);

    List<ContractViolation> violations = new ArrayList<>();
    violations.addAll(validateBaselineConventions(filteredExpected, normalizedOwner));

    prepareForDiff(filteredExpected, normalizedActual);
    ChangedOpenApi diff = OpenApiCompare.fromSpecifications(filteredExpected, normalizedActual);
    for (Endpoint missing : diff.getMissingEndpoints()) {
      violations.add(
          ContractViolation.missingEndpoint(missing.getMethod().name(), missing.getPathUrl()));
    }
    for (Endpoint extra : diff.getNewEndpoints()) {
      violations.add(ContractViolation.extraEndpoint(extra.getMethod().name(), extra.getPathUrl()));
    }
    for (ChangedOperation changedOp : diff.getChangedOperations()) {
      String loc = changedOp.getHttpMethod().name() + " " + changedOp.getPathUrl();
      if (changedOp.getParameters() != null && changedOp.getParameters().getMissing() != null) {
        for (Parameter missingParam : changedOp.getParameters().getMissing()) {
          violations.add(
              ContractViolation.incompatibleChange(
                  loc,
                  "Thiếu tham số '"
                      + missingParam.getName()
                      + "' ("
                      + missingParam.getIn()
                      + ") theo hợp đồng."));
        }
      }
      inspectOperationSchemaAndResponses(
          loc,
          changedOp.getOldOperation(),
          changedOp.getNewOperation(),
          filteredExpected,
          normalizedActual,
          violations);
    }

    return new ConformanceReport(module, normalizedOwner, violations);
  }

  /**
   * Kiểm các quy ước mục 13 baseline trên các thao tác thuộc phạm vi {@code ownerFilter}: đường dẫn
   * gốc {@code /api/v1}, thao tác ghi có {@code Idempotency-Key}, cập nhật có {@code If-Match} kèm
   * {@code 409}, phân trang có {@code limit <= 200} và {@code cursor}, phản hồi lỗi dùng {@code
   * application/problem+json} (RFC 9457).
   */
  public static List<ContractViolation> validateBaselineConventions(
      OpenAPI spec, String ownerFilter) {
    Objects.requireNonNull(spec, "spec must not be null");
    List<ContractViolation> violations = new ArrayList<>();

    boolean hasApiV1Server =
        spec.getServers() != null
            && spec.getServers().stream()
                .map(Server::getUrl)
                .filter(Objects::nonNull)
                .anyMatch(url -> url.startsWith(API_V1_PREFIX));
    if (!hasApiV1Server) {
      violations.add(
          ContractViolation.baselineConvention(
              "servers", "Hợp đồng phải khai báo server bắt đầu bằng /api/v1."));
    }

    if (spec.getPaths() == null) {
      return violations;
    }

    for (Map.Entry<String, PathItem> pathEntry : spec.getPaths().entrySet()) {
      String path = pathEntry.getKey();
      PathItem pathItem = pathEntry.getValue();
      List<Parameter> pathParams =
          pathItem.getParameters() != null ? pathItem.getParameters() : List.of();

      for (Map.Entry<PathItem.HttpMethod, Operation> opEntry :
          pathItem.readOperationsMap().entrySet()) {
        PathItem.HttpMethod method = opEntry.getKey();
        Operation op = opEntry.getValue();
        if (!matchesOwner(op, ownerFilter)) {
          continue;
        }
        String loc = method.name() + " " + path;
        List<Parameter> allParams = mergeParameters(spec, pathParams, op.getParameters());

        if (isWriteMethod(method) && !isIdempotencyExempt(path, op)) {
          boolean hasIdempotencyKey =
              allParams.stream()
                  .anyMatch(
                      p ->
                          "header".equalsIgnoreCase(p.getIn())
                              && "Idempotency-Key".equalsIgnoreCase(p.getName()));
          if (!hasIdempotencyKey) {
            violations.add(
                ContractViolation.baselineConvention(
                    loc, "Thao tác ghi bắt buộc khai báo header Idempotency-Key."));
          }
        }

        boolean hasIfMatch =
            allParams.stream()
                .anyMatch(
                    p ->
                        "header".equalsIgnoreCase(p.getIn())
                            && "If-Match".equalsIgnoreCase(p.getName()));
        if (hasIfMatch && (op.getResponses() == null || !op.getResponses().containsKey("409"))) {
          violations.add(
              ContractViolation.baselineConvention(
                  loc, "Thao tác có header If-Match bắt buộc khai báo mã phản hồi 409."));
        }

        checkPaginationConvention(loc, allParams, violations);
        checkProblemJsonResponses(spec, loc, op, violations);
      }
    }
    return violations;
  }

  /**
   * Đối chiếu một chuỗi phản hồi JSON thực tế (ví dụ từ {@code MockMvc}) với schema phản hồi trong
   * hợp đồng OpenAPI.
   */
  public static List<ContractViolation> validateResponseJson(
      OpenAPI spec, String path, PathItem.HttpMethod method, int statusCode, String responseJson) {
    Objects.requireNonNull(spec, "spec must not be null");
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(method, "method must not be null");
    Objects.requireNonNull(responseJson, "responseJson must not be null");

    String normalizedPath =
        path.startsWith(API_V1_PREFIX + "/") ? path.substring(API_V1_PREFIX.length()) : path;
    String loc = method.name() + " " + normalizedPath + " -> " + statusCode;

    PathItem pathItem = spec.getPaths() != null ? spec.getPaths().get(normalizedPath) : null;
    if (pathItem == null) {
      return List.of(
          ContractViolation.responseSchema(loc, "Không tìm thấy đường dẫn trong hợp đồng."));
    }
    Operation op = pathItem.readOperationsMap().get(method);
    if (op == null || op.getResponses() == null) {
      return List.of(
          ContractViolation.responseSchema(loc, "Không tìm thấy thao tác trong hợp đồng."));
    }

    ApiResponse response = op.getResponses().get(String.valueOf(statusCode));
    if (response == null) {
      response = op.getResponses().get("default");
    }
    response = resolveApiResponse(spec, response);
    if (response == null) {
      return List.of(
          ContractViolation.responseSchema(
              loc, "Mã trạng thái " + statusCode + " không được khai báo trong hợp đồng."));
    }
    if (response.getContent() == null || response.getContent().isEmpty()) {
      return List.of();
    }

    MediaType mediaType = response.getContent().get(JSON_MEDIA_TYPE);
    if (mediaType == null) {
      mediaType = response.getContent().get(PROBLEM_MEDIA_TYPE);
    }
    if (mediaType == null || mediaType.getSchema() == null) {
      return List.of();
    }

    final JsonNode rootNode;
    try {
      rootNode = OBJECT_MAPPER.readTree(responseJson);
    } catch (IOException ex) {
      return List.of(
          ContractViolation.responseSchema(
              loc, "Phản hồi không phải JSON hợp lệ: " + ex.getMessage()));
    }

    List<ContractViolation> violations = new ArrayList<>();
    validateJsonNodeAgainstSchema(spec, mediaType.getSchema(), rootNode, loc, violations);
    return violations;
  }

  /**
   * Kiểm tra toàn bộ {@code examples} phản hồi 2xx trong hợp đồng của mô-đun có khớp với chính
   * schema đã khai báo hay không.
   */
  public static List<ContractViolation> validateContractExamples(OpenAPI spec, String ownerFilter) {
    Objects.requireNonNull(spec, "spec must not be null");
    List<ContractViolation> violations = new ArrayList<>();
    if (spec.getPaths() == null) {
      return violations;
    }
    for (Map.Entry<String, PathItem> pathEntry : spec.getPaths().entrySet()) {
      String path = pathEntry.getKey();
      for (Map.Entry<PathItem.HttpMethod, Operation> opEntry :
          pathEntry.getValue().readOperationsMap().entrySet()) {
        Operation op = opEntry.getValue();
        if (!matchesOwner(op, ownerFilter) || op.getResponses() == null) {
          continue;
        }
        for (Map.Entry<String, ApiResponse> respEntry : op.getResponses().entrySet()) {
          String status = respEntry.getKey();
          if (!status.startsWith("2")) {
            continue;
          }
          ApiResponse resolved = resolveApiResponse(spec, respEntry.getValue());
          if (resolved == null || resolved.getContent() == null) {
            continue;
          }
          MediaType jsonMedia = resolved.getContent().get(JSON_MEDIA_TYPE);
          if (jsonMedia == null
              || jsonMedia.getSchema() == null
              || jsonMedia.getExamples() == null) {
            continue;
          }
          for (Map.Entry<String, Example> exEntry : jsonMedia.getExamples().entrySet()) {
            Object val = exEntry.getValue().getValue();
            if (val != null) {
              JsonNode node = OBJECT_MAPPER.valueToTree(val);
              String loc =
                  opEntry.getKey().name()
                      + " "
                      + path
                      + " -> "
                      + status
                      + " (example="
                      + exEntry.getKey()
                      + ")";
              validateJsonNodeAgainstSchema(spec, jsonMedia.getSchema(), node, loc, violations);
            }
          }
        }
      }
    }
    return violations;
  }

  private static void inspectOperationSchemaAndResponses(
      String loc,
      Operation expectedOp,
      Operation actualOp,
      OpenAPI expectedSpec,
      OpenAPI actualSpec,
      List<ContractViolation> violations) {
    if (expectedOp == null || actualOp == null) {
      return;
    }
    if (expectedOp.getResponses() != null) {
      for (Map.Entry<String, ApiResponse> entry : expectedOp.getResponses().entrySet()) {
        String code = entry.getKey();
        if (actualOp.getResponses() == null || !actualOp.getResponses().containsKey(code)) {
          violations.add(
              ContractViolation.incompatibleChange(
                  loc, "Thiếu mã phản hồi bắt buộc '" + code + "' theo hợp đồng."));
          continue;
        }
        ApiResponse expResp = resolveApiResponse(expectedSpec, entry.getValue());
        ApiResponse actResp = resolveApiResponse(actualSpec, actualOp.getResponses().get(code));
        compareResponseSchemas(
            loc + " -> " + code, expResp, actResp, expectedSpec, actualSpec, violations);
      }
    }
  }

  @SuppressWarnings("rawtypes")
  private static void compareResponseSchemas(
      String loc,
      ApiResponse expResp,
      ApiResponse actResp,
      OpenAPI expectedSpec,
      OpenAPI actualSpec,
      List<ContractViolation> violations) {
    if (expResp == null
        || expResp.getContent() == null
        || !expResp.getContent().containsKey(JSON_MEDIA_TYPE)) {
      return;
    }
    Schema expSchema =
        resolveSchema(expectedSpec, expResp.getContent().get(JSON_MEDIA_TYPE).getSchema());
    if (expSchema == null || expSchema.getProperties() == null) {
      return;
    }
    if (actResp == null || actResp.getContent() == null) {
      violations.add(
          ContractViolation.incompatibleChange(loc, "Thiếu nội dung phản hồi application/json."));
      return;
    }
    MediaType actMedia = actResp.getContent().get(JSON_MEDIA_TYPE);
    if (actMedia == null) {
      actMedia = actResp.getContent().values().stream().findFirst().orElse(null);
    }
    Schema actSchema = actMedia != null ? resolveSchema(actualSpec, actMedia.getSchema()) : null;
    if (actSchema == null || actSchema.getProperties() == null) {
      return;
    }

    Map<String, Schema> expProps = expSchema.getProperties();
    Map<String, Schema> actProps = actSchema.getProperties();
    for (Map.Entry<String, Schema> propEntry : expProps.entrySet()) {
      String propName = propEntry.getKey();
      if (!actProps.containsKey(propName)) {
        violations.add(
            ContractViolation.incompatibleChange(
                loc + "." + propName,
                "Thiếu hoặc bị đổi tên trường phản hồi '" + propName + "' so với hợp đồng."));
        continue;
      }
      Schema expProp = resolveSchema(expectedSpec, propEntry.getValue());
      Schema actProp = resolveSchema(actualSpec, actProps.get(propName));
      String expType = resolvePrimaryType(expProp);
      String actType = resolvePrimaryType(actProp);
      if (expType != null && actType != null && !expType.equals(actType)) {
        violations.add(
            ContractViolation.incompatibleChange(
                loc + "." + propName,
                "Kiểu dữ liệu trường '"
                    + propName
                    + "' lệch hợp đồng: mong đợi '"
                    + expType
                    + "' nhưng thực tế là '"
                    + actType
                    + "'."));
      }
    }
    for (String actPropName : actProps.keySet()) {
      if (!expProps.containsKey(actPropName)) {
        violations.add(
            ContractViolation.incompatibleChange(
                loc + "." + actPropName,
                "Phát hiện trường phản hồi ngoài hợp đồng: '" + actPropName + "'."));
      }
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void validateJsonNodeAgainstSchema(
      OpenAPI spec,
      Schema rawSchema,
      JsonNode node,
      String location,
      List<ContractViolation> violations) {
    Schema schema = resolveSchema(spec, rawSchema);
    if (schema == null || node == null || node.isNull()) {
      return;
    }

    String type = resolvePrimaryType(schema);
    if ("object".equals(type) || schema.getProperties() != null) {
      if (!node.isObject()) {
        violations.add(
            ContractViolation.responseSchema(
                location, "Mong đợi kiểu object nhưng nhận được " + node.getNodeType()));
        return;
      }
      Map<String, Schema> props =
          schema.getProperties() != null ? schema.getProperties() : Map.of();
      List<String> required = schema.getRequired() != null ? schema.getRequired() : List.of();
      for (String reqField : required) {
        Schema reqPropSchema = resolveSchema(spec, props.get(reqField));
        if ((!node.has(reqField) || node.get(reqField).isNull())
            && !isNullableSchema(reqPropSchema)) {
          violations.add(
              ContractViolation.responseSchema(
                  location + "." + reqField, "Thiếu trường bắt buộc '" + reqField + "'."));
        }
      }
      Iterator<String> fieldNames = node.fieldNames();
      while (fieldNames.hasNext()) {
        String field = fieldNames.next();
        if (!props.containsKey(field) && schema.getAdditionalProperties() == null) {
          violations.add(
              ContractViolation.responseSchema(
                  location + "." + field,
                  "Trường '" + field + "' không tồn tại trong schema hợp đồng."));
        } else if (props.containsKey(field)) {
          validateJsonNodeAgainstSchema(
              spec, props.get(field), node.get(field), location + "." + field, violations);
        }
      }
    } else if ("array".equals(type)) {
      if (!node.isArray()) {
        violations.add(
            ContractViolation.responseSchema(
                location, "Mong đợi kiểu array nhưng nhận được " + node.getNodeType()));
        return;
      }
      if (schema.getItems() != null) {
        for (int i = 0; i < node.size(); i++) {
          validateJsonNodeAgainstSchema(
              spec, schema.getItems(), node.get(i), location + "[" + i + "]", violations);
        }
      }
    } else if ("string".equals(type)) {
      if (!node.isTextual()) {
        violations.add(
            ContractViolation.responseSchema(
                location, "Mong đợi kiểu string nhưng nhận được " + node.getNodeType()));
        return;
      }
      if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
        List<String> allowed = schema.getEnum().stream().map(String::valueOf).toList();
        if (!allowed.contains(node.asText())) {
          violations.add(
              ContractViolation.responseSchema(
                  location,
                  "Giá trị enum '" + node.asText() + "' không nằm trong tập hợp lệ " + allowed));
        }
      }
    } else if ("integer".equals(type)) {
      if (!node.isIntegralNumber()) {
        violations.add(
            ContractViolation.responseSchema(
                location, "Mong đợi kiểu integer nhưng nhận được " + node.getNodeType()));
        return;
      }
      checkNumericBounds(schema, node.decimalValue(), location, violations);
    } else if ("number".equals(type)) {
      if (!node.isNumber()) {
        violations.add(
            ContractViolation.responseSchema(
                location, "Mong đợi kiểu number nhưng nhận được " + node.getNodeType()));
        return;
      }
      checkNumericBounds(schema, node.decimalValue(), location, violations);
    } else if ("boolean".equals(type) && !node.isBoolean()) {
      violations.add(
          ContractViolation.responseSchema(
              location, "Mong đợi kiểu boolean nhưng nhận được " + node.getNodeType()));
    }
  }

  @SuppressWarnings("rawtypes")
  private static void checkNumericBounds(
      Schema schema, BigDecimal value, String location, List<ContractViolation> violations) {
    if (schema.getMinimum() != null && value.compareTo(schema.getMinimum()) < 0) {
      violations.add(
          ContractViolation.responseSchema(
              location, "Giá trị " + value + " nhỏ hơn minimum " + schema.getMinimum()));
    }
    if (schema.getMaximum() != null && value.compareTo(schema.getMaximum()) > 0) {
      violations.add(
          ContractViolation.responseSchema(
              location, "Giá trị " + value + " lớn hơn maximum " + schema.getMaximum()));
    }
  }

  private static OpenAPI filterByOwner(OpenAPI source, String ownerFilter) {
    if (source.getPaths() == null) {
      return source;
    }
    OpenAPI copy = new OpenAPI();
    copy.setOpenapi(source.getOpenapi());
    copy.setInfo(source.getInfo());
    copy.setServers(source.getServers());
    copy.setComponents(source.getComponents());
    copy.setSecurity(source.getSecurity());

    Paths filteredPaths = new Paths();
    for (Map.Entry<String, PathItem> entry : source.getPaths().entrySet()) {
      PathItem item = entry.getValue();
      PathItem filteredItem = new PathItem();
      filteredItem.setParameters(item.getParameters());
      boolean hasAny = false;
      for (Map.Entry<PathItem.HttpMethod, Operation> opEntry :
          item.readOperationsMap().entrySet()) {
        if (matchesOwner(opEntry.getValue(), ownerFilter)) {
          filteredItem.operation(opEntry.getKey(), opEntry.getValue());
          hasAny = true;
        }
      }
      if (hasAny) {
        filteredPaths.addPathItem(entry.getKey(), filteredItem);
      }
    }
    copy.setPaths(filteredPaths);
    return copy;
  }

  private static OpenAPI normalizeActualPaths(OpenAPI actual) {
    if (actual.getPaths() == null) {
      return actual;
    }
    Paths normalized = new Paths();
    for (Map.Entry<String, PathItem> entry : actual.getPaths().entrySet()) {
      String path = entry.getKey();
      if (path.startsWith(API_V1_PREFIX + "/")) {
        path = path.substring(API_V1_PREFIX.length());
      }
      normalized.addPathItem(path, entry.getValue());
    }
    actual.setPaths(normalized);
    return actual;
  }

  private static void prepareForDiff(OpenAPI expected, OpenAPI actual) {
    ensureComponentsInitialized(expected);
    ensureComponentsInitialized(actual);
    expected
        .getComponents()
        .getSecuritySchemes()
        .forEach(actual.getComponents().getSecuritySchemes()::putIfAbsent);
    expected.getComponents().getHeaders().forEach(actual.getComponents().getHeaders()::putIfAbsent);
    expected
        .getComponents()
        .getParameters()
        .forEach(actual.getComponents().getParameters()::putIfAbsent);
    expected
        .getComponents()
        .getResponses()
        .forEach(actual.getComponents().getResponses()::putIfAbsent);
    normalizeOperationsForDiff(expected);
    normalizeOperationsForDiff(actual);
    alignResponseHeadersIgnoredByCodegen(expected, actual);
  }

  private static void ensureComponentsInitialized(OpenAPI spec) {
    if (spec.getComponents() == null) {
      spec.setComponents(new Components());
    }
    Components c = spec.getComponents();
    if (c.getSchemas() == null) {
      c.setSchemas(new LinkedHashMap<>());
    }
    if (c.getResponses() == null) {
      c.setResponses(new LinkedHashMap<>());
    }
    if (c.getParameters() == null) {
      c.setParameters(new LinkedHashMap<>());
    }
    if (c.getHeaders() == null) {
      c.setHeaders(new LinkedHashMap<>());
    }
    if (c.getSecuritySchemes() == null) {
      c.setSecuritySchemes(new LinkedHashMap<>());
    }
  }

  private static void normalizeOperationsForDiff(OpenAPI spec) {
    if (spec.getPaths() == null) {
      return;
    }
    for (PathItem pathItem : spec.getPaths().values()) {
      List<Parameter> pathParams = pathItem.getParameters();
      for (Operation op : pathItem.readOperations()) {
        if ((op.getSecurity() == null || op.getSecurity().isEmpty())
            && spec.getSecurity() != null) {
          op.setSecurity(spec.getSecurity());
        }
        List<Parameter> mergedParams = mergeParameters(spec, pathParams, op.getParameters());
        op.setParameters(new ArrayList<>(mergedParams));
        if (op.getResponses() != null) {
          ApiResponses resolvedResponses = new ApiResponses();
          for (Map.Entry<String, ApiResponse> respEntry : op.getResponses().entrySet()) {
            resolvedResponses.addApiResponse(
                respEntry.getKey(), resolveApiResponse(spec, respEntry.getValue()));
          }
          op.setResponses(resolvedResponses);
        }
      }
      pathItem.setParameters(null);
    }
  }

  private static void alignResponseHeadersIgnoredByCodegen(OpenAPI expected, OpenAPI actual) {
    if (expected.getPaths() == null || actual.getPaths() == null) {
      return;
    }
    for (Map.Entry<String, PathItem> pathEntry : expected.getPaths().entrySet()) {
      PathItem actPath = actual.getPaths().get(pathEntry.getKey());
      if (actPath == null) {
        continue;
      }
      Map<PathItem.HttpMethod, Operation> actOps = actPath.readOperationsMap();
      for (Map.Entry<PathItem.HttpMethod, Operation> opEntry :
          pathEntry.getValue().readOperationsMap().entrySet()) {
        Operation expOp = opEntry.getValue();
        Operation actOp = actOps.get(opEntry.getKey());
        if (expOp == null
            || actOp == null
            || expOp.getResponses() == null
            || actOp.getResponses() == null) {
          continue;
        }
        for (Map.Entry<String, ApiResponse> respEntry : expOp.getResponses().entrySet()) {
          ApiResponse expResp = respEntry.getValue();
          ApiResponse actResp = actOp.getResponses().get(respEntry.getKey());
          if (expResp != null
              && actResp != null
              && expResp.getHeaders() != null
              && actResp.getHeaders() == null) {
            actResp.setHeaders(new LinkedHashMap<>(expResp.getHeaders()));
          }
        }
      }
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static OpenAPI normalizeOpenApi31Types(OpenAPI spec) {
    if (spec == null) {
      return null;
    }
    Set<Schema> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    if (spec.getComponents() != null) {
      Components c = spec.getComponents();
      if (c.getSchemas() != null) {
        c.getSchemas().values().forEach(s -> normalizeSchemaType(s, visited));
      }
      if (c.getParameters() != null) {
        c.getParameters()
            .values()
            .forEach(p -> normalizeSchemaType(p != null ? p.getSchema() : null, visited));
      }
      if (c.getHeaders() != null) {
        c.getHeaders()
            .values()
            .forEach(h -> normalizeSchemaType(h != null ? h.getSchema() : null, visited));
      }
      if (c.getResponses() != null) {
        c.getResponses().values().forEach(r -> normalizeApiResponseTypes(r, visited));
      }
    }
    if (spec.getPaths() != null) {
      for (PathItem item : spec.getPaths().values()) {
        if (item.getParameters() != null) {
          item.getParameters()
              .forEach(p -> normalizeSchemaType(p != null ? p.getSchema() : null, visited));
        }
        for (Operation op : item.readOperations()) {
          if (op.getParameters() != null) {
            op.getParameters()
                .forEach(p -> normalizeSchemaType(p != null ? p.getSchema() : null, visited));
          }
          if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
            op.getRequestBody()
                .getContent()
                .values()
                .forEach(m -> normalizeSchemaType(m != null ? m.getSchema() : null, visited));
          }
          if (op.getResponses() != null) {
            op.getResponses().values().forEach(r -> normalizeApiResponseTypes(r, visited));
          }
        }
      }
    }
    return spec;
  }

  @SuppressWarnings("rawtypes")
  private static void normalizeApiResponseTypes(ApiResponse r, Set<Schema> visited) {
    if (r == null) {
      return;
    }
    if (r.getContent() != null) {
      r.getContent()
          .values()
          .forEach(m -> normalizeSchemaType(m != null ? m.getSchema() : null, visited));
    }
    if (r.getHeaders() != null) {
      for (Header h : r.getHeaders().values()) {
        if (h != null) {
          normalizeSchemaType(h.getSchema(), visited);
        }
      }
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void normalizeSchemaType(Schema schema, Set<Schema> visited) {
    if (schema == null || !visited.add(schema)) {
      return;
    }
    String primary = resolvePrimaryType(schema);
    if (primary != null && schema.getType() == null) {
      schema.setType(primary);
    }
    if (schema.getProperties() != null) {
      Map<String, Schema> props = schema.getProperties();
      props.values().forEach(s -> normalizeSchemaType(s, visited));
    }
    if (schema.getItems() != null) {
      normalizeSchemaType(schema.getItems(), visited);
    }
    if (schema.getAdditionalProperties() instanceof Schema addPropSchema) {
      normalizeSchemaType(addPropSchema, visited);
    }
    if (schema.getAllOf() != null) {
      schema.getAllOf().forEach(s -> normalizeSchemaType((Schema) s, visited));
    }
    if (schema.getOneOf() != null) {
      schema.getOneOf().forEach(s -> normalizeSchemaType((Schema) s, visited));
    }
    if (schema.getAnyOf() != null) {
      schema.getAnyOf().forEach(s -> normalizeSchemaType((Schema) s, visited));
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String resolvePrimaryType(Schema schema) {
    if (schema == null) {
      return null;
    }
    if (schema.getType() != null && !schema.getType().isBlank()) {
      return schema.getType();
    }
    Set<String> types = schema.getTypes();
    if (types != null && !types.isEmpty()) {
      return types.stream()
          .filter(t -> !"null".equals(t))
          .findFirst()
          .orElse(types.iterator().next());
    }
    if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
      return "object";
    }
    return null;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static boolean isNullableSchema(Schema schema) {
    if (schema == null) {
      return false;
    }
    Set<String> types = schema.getTypes();
    return Boolean.TRUE.equals(schema.getNullable()) || (types != null && types.contains("null"));
  }

  private static boolean matchesOwner(Operation op, String ownerFilter) {
    if ("*".equals(ownerFilter) || ownerFilter == null || ownerFilter.isBlank()) {
      return true;
    }
    if (op.getExtensions() == null) {
      return false;
    }
    Object xMission = op.getExtensions().get("x-mission");
    if (xMission instanceof Map<?, ?> map) {
      Object owner = map.get("owner");
      return owner != null && ownerFilter.trim().equalsIgnoreCase(String.valueOf(owner).trim());
    }
    return false;
  }

  private static boolean isWriteMethod(PathItem.HttpMethod method) {
    return Set.of(
            PathItem.HttpMethod.POST,
            PathItem.HttpMethod.PUT,
            PathItem.HttpMethod.PATCH,
            PathItem.HttpMethod.DELETE)
        .contains(method);
  }

  private static boolean isIdempotencyExempt(String path, Operation op) {
    if (op.getExtensions() != null && op.getExtensions().get("x-mission") instanceof Map<?, ?> m) {
      Object idempotent = m.get("idempotentRead");
      if (Boolean.TRUE.equals(idempotent)) {
        return true;
      }
    }
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.endsWith("/preview")
        || lower.endsWith("/simulate")
        || lower.endsWith("/check")
        || lower.endsWith("/verify")
        || lower.endsWith("/search");
  }

  private static List<Parameter> mergeParameters(
      OpenAPI spec, List<Parameter> pathParams, List<Parameter> opParams) {
    Map<String, Parameter> merged = new LinkedHashMap<>();
    if (pathParams != null) {
      for (Parameter p : pathParams) {
        Parameter resolved = resolveParameter(spec, p);
        if (resolved != null && resolved.getName() != null) {
          merged.put(resolved.getIn() + ":" + resolved.getName(), resolved);
        }
      }
    }
    if (opParams != null) {
      for (Parameter p : opParams) {
        Parameter resolved = resolveParameter(spec, p);
        if (resolved != null && resolved.getName() != null) {
          merged.put(resolved.getIn() + ":" + resolved.getName(), resolved);
        }
      }
    }
    return List.copyOf(merged.values());
  }

  @SuppressWarnings("rawtypes")
  private static void checkPaginationConvention(
      String loc, List<Parameter> params, List<ContractViolation> violations) {
    Parameter limitParam =
        params.stream()
            .filter(p -> "query".equalsIgnoreCase(p.getIn()) && "limit".equals(p.getName()))
            .findFirst()
            .orElse(null);
    if (limitParam == null) {
      return;
    }
    Schema schema = limitParam.getSchema();
    if (schema != null
        && schema.getMaximum() != null
        && schema.getMaximum().compareTo(BigDecimal.valueOf(200)) > 0) {
      violations.add(
          ContractViolation.baselineConvention(
              loc, "Tham số phân trang limit vượt ngưỡng tối đa 200: " + schema.getMaximum()));
    }
  }

  private static void checkProblemJsonResponses(
      OpenAPI spec, String loc, Operation op, List<ContractViolation> violations) {
    if (op.getResponses() == null) {
      return;
    }
    for (Map.Entry<String, ApiResponse> entry : op.getResponses().entrySet()) {
      String code = entry.getKey();
      if (!code.startsWith("4") && !"default".equals(code)) {
        continue;
      }
      ApiResponse resolved = resolveApiResponse(spec, entry.getValue());
      if (resolved == null) {
        continue;
      }
      Content content = resolved.getContent();
      if (content == null || !content.containsKey(PROBLEM_MEDIA_TYPE)) {
        violations.add(
            ContractViolation.baselineConvention(
                loc + " -> " + code,
                "Phản hồi lỗi '"
                    + code
                    + "' bắt buộc dùng kiểu nội dung application/problem+json (RFC 9457)."));
      }
    }
  }

  private static Parameter resolveParameter(OpenAPI spec, Parameter param) {
    if (param == null || param.get$ref() == null) {
      return param;
    }
    String ref = param.get$ref();
    String prefix = "#/components/parameters/";
    if (ref.startsWith(prefix)
        && spec.getComponents() != null
        && spec.getComponents().getParameters() != null) {
      return spec.getComponents().getParameters().get(ref.substring(prefix.length()));
    }
    return param;
  }

  private static ApiResponse resolveApiResponse(OpenAPI spec, ApiResponse response) {
    if (response == null || response.get$ref() == null) {
      return response;
    }
    String ref = response.get$ref();
    String prefix = "#/components/responses/";
    if (ref.startsWith(prefix)
        && spec.getComponents() != null
        && spec.getComponents().getResponses() != null) {
      return spec.getComponents().getResponses().get(ref.substring(prefix.length()));
    }
    return response;
  }

  @SuppressWarnings("rawtypes")
  private static Schema resolveSchema(OpenAPI spec, Schema schema) {
    if (schema == null || schema.get$ref() == null) {
      return schema;
    }
    String ref = schema.get$ref();
    String prefix = "#/components/schemas/";
    if (ref.startsWith(prefix)
        && spec.getComponents() != null
        && spec.getComponents().getSchemas() != null) {
      Schema resolved = spec.getComponents().getSchemas().get(ref.substring(prefix.length()));
      return resolved != null ? resolved : schema;
    }
    return schema;
  }
}
