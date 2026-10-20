package vn.viettel.okrkpi.org.architecture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import vn.viettel.okrkpi.org.support.IntegrationTestBase;

/** Khung service khởi động với svc_org: probe sẵn sàng, API yêu cầu xác thực và trả lỗi RFC 9457. */
@AutoConfigureMockMvc
class ServiceSmokeIT extends IntegrationTestBase {

  @Autowired
  MockMvc mvc;

  @Test
  void probeVaXacThuc() throws Exception {
    mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mvc.perform(get("/api/v1/org/units/tree")).andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }
}
