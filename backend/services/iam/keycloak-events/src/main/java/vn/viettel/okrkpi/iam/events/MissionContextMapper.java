package vn.viettel.okrkpi.iam.events;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

/**
 * DV-02-02 · Protocol mapper {@code mission-context}: phát claim tenant và ngữ cảnh làm việc từ thuộc tính người dùng
 * do org-service ghi qua Admin API ({@code setMyContext}); không đọc gì do client gửi (tham số, header, claim yêu
 * cầu). Claim phát ra:
 * <ul>
 *   <li>{@code tenant_id}, {@code tenant} (cùng giá trị, cho thư viện nền và web), {@code cell} từ thuộc tính realm
 *       {@code mission.cell};
 *   <li>khi có ngữ cảnh còn hiệu lực: {@code unit_id}, {@code active_assignment_id}, {@code flows}, {@code roles} và
 *       claim gộp {@code org} ({@code tenant}, {@code cell}, {@code unit}, {@code assignment}, {@code flows},
 *       {@code roles}) theo mô tả {@code setMyContext} của {@code org.yaml}.
 * </ul>
 * Ngữ cảnh có {@code mission_context_valid_to} trước hôm nay (giờ Việt Nam) bị bỏ: token không mang đơn vị, vai trò
 * của phân công đã hết hiệu lực, người dùng phải chọn lại.
 */
public class MissionContextMapper extends AbstractOIDCProtocolMapper
    implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

  public static final String PROVIDER_ID = "mission-context-mapper";
  static final String TENANT = "tenant_id";
  static final String UNIT = "mission_context_unit";
  static final String ASSIGNMENT = "mission_context_assignment";
  static final String ROLES = "mission_context_roles";
  static final String FLOWS = "mission_context_flows";
  static final String VALID_TO = "mission_context_valid_to";
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final List<ProviderConfigProperty> CONFIG = new java.util.ArrayList<>();

  static {
    OIDCAttributeMapperHelper.addIncludeInTokensConfig(CONFIG, MissionContextMapper.class);
  }

  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  @Override
  public String getDisplayType() {
    return "Mission: ngữ cảnh làm việc";
  }

  @Override
  public String getDisplayCategory() {
    return TOKEN_MAPPER_CATEGORY;
  }

  @Override
  public String getHelpText() {
    return "Phát tenant, đơn vị, phân công, luồng và vai trò của ngữ cảnh làm việc đã chọn.";
  }

  @Override
  public List<ProviderConfigProperty> getConfigProperties() {
    return CONFIG;
  }

  @Override
  protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
      KeycloakSession session, ClientSessionContext clientSessionCtx) {
    UserModel user = userSession.getUser();
    Map<String, Object> claims = token.getOtherClaims();
    String tenant = user.getFirstAttribute(TENANT);
    String cell = userSession.getRealm().getAttribute("mission.cell");
    if (tenant != null) {
      claims.put("tenant_id", tenant);
      claims.put("tenant", tenant);
    }
    if (cell != null) {
      claims.put("cell", cell);
    }
    String unit = user.getFirstAttribute(UNIT);
    String assignment = user.getFirstAttribute(ASSIGNMENT);
    if (unit == null || assignment == null || !valid(user.getFirstAttribute(VALID_TO))) {
      return;
    }
    List<String> flows = user.getAttributeStream(FLOWS).collect(Collectors.toList());
    List<String> roles = user.getAttributeStream(ROLES).collect(Collectors.toList());
    claims.put("unit_id", unit);
    claims.put("active_assignment_id", assignment);
    claims.put("flows", flows);
    claims.put("roles", roles);
    Map<String, Object> org = new LinkedHashMap<>();
    org.put("tenant", tenant);
    org.put("cell", cell);
    org.put("unit", unit);
    org.put("assignment", assignment);
    org.put("flows", flows);
    org.put("roles", roles);
    claims.put("org", org);
  }

  static boolean valid(String validTo) {
    if (validTo == null || validTo.isBlank()) {
      return true;
    }
    try {
      return !LocalDate.parse(validTo).isBefore(LocalDate.now(BUSINESS_ZONE));
    } catch (DateTimeParseException e) {
      return false;
    }
  }
}
