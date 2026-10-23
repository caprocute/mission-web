package vn.viettel.okrkpi.org.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgDiscrepancies200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgPersons200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListE2eeRecoveries200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgSyncRuns200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgUnitAssignments200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgUnitHistory200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.ListOrgUnits200Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgUnitRef;

/**
 * Bỏ trường null không bắt buộc (hợp đồng khai {@code type: string} không nhận null), nhưng giữ trường bắt buộc có
 * thể null như {@code nextCursor}, {@code OrgUnitRef.parentId}.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

  @Bean
  Jackson2ObjectMapperBuilderCustomizer orgJackson() {
    return b -> b.serializationInclusion(JsonInclude.Include.NON_NULL)
        .mixIn(ListOrgUnits200Response.class, NextCursorAlways.class)
        .mixIn(ListOrgUnitHistory200Response.class, NextCursorAlways.class)
        .mixIn(ListOrgPersons200Response.class, NextCursorAlways.class)
        .mixIn(ListOrgUnitAssignments200Response.class, NextCursorAlways.class)
        .mixIn(ListOrgDiscrepancies200Response.class, NextCursorAlways.class)
        .mixIn(ListOrgSyncRuns200Response.class, NextCursorAlways.class)
        .mixIn(ListE2eeRecoveries200Response.class, NextCursorAlways.class)
        .mixIn(OrgUnitRef.class, ParentIdAlways.class);
  }

  abstract static class NextCursorAlways {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    abstract String getNextCursor();
  }

  abstract static class ParentIdAlways {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    abstract UUID getParentId();
  }
}
