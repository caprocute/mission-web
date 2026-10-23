package vn.viettel.okrkpi.workflow.infrastructure.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.workflow.application.port.TemplateCodeHost;

/** Bộ chuyển HTTP kiểu GitLab v4 của hệ quản lý mã repo mẫu luồng (BS-01 "Đồng bộ với kho mã"). */
class GitLabTemplateCodeHostTest {

  static final String API = "https://ma-nguon.example.invalid/api/v4";

  @TempDir Path dir;
  MockRestServiceServer server;
  GitLabTemplateCodeHost host;
  TemplateCodeHost.ReleasePackage pkg;

  @BeforeEach
  void setUp() throws IOException {
    Path token = dir.resolve("token");
    Files.writeString(token, "gia-lap\n", StandardCharsets.UTF_8);
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    host = new GitLabTemplateCodeHost(builder, API, "42", "main", token.toString());
    pkg =
        new TemplateCodeHost.ReleasePackage(
            UUID.randomUUID(),
            "mau-a",
            2,
            Map.of("mau/mau-a/v2/mau-a.bpmn20.xml", "<definitions/>"),
            "Phát hành mẫu luồng mau-a phiên bản 2");
  }

  @Test
  void moMergeRequestMoi() {
    server
        .expect(
            requestTo(
                API
                    + "/projects/42/merge_requests?state=opened&source_branch=mau-luong%2Fmau-a-v2"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("PRIVATE-TOKEN", "gia-lap"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                API + "/projects/42/repository/branches?branch=mau-luong%2Fmau-a-v2&ref=main"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST));
    server
        .expect(requestTo(API + "/projects/42/repository/commits"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("mau/mau-a/v2/mau-a.bpmn20.xml")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(API + "/projects/42/merge_requests"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("\"target_branch\":\"main\"")))
        .andRespond(
            withSuccess(
                "{\"iid\":128,\"web_url\":\"https://ma-nguon.example.invalid/mr/128\"}",
                MediaType.APPLICATION_JSON));
    TemplateCodeHost.MergeRequest mr = host.open(pkg, UUID.randomUUID());
    assertThat(mr.ref()).isEqualTo("128");
    assertThat(mr.url()).endsWith("/mr/128");
    server.verify();
  }

  @Test
  void mergeRequestDaCoThiDungLai() {
    server
        .expect(
            requestTo(
                API
                    + "/projects/42/merge_requests?state=opened&source_branch=mau-luong%2Fmau-a-v2"))
        .andRespond(withSuccess("[{\"iid\":7,\"web_url\":null}]", MediaType.APPLICATION_JSON));
    TemplateCodeHost.MergeRequest mr = host.open(pkg, UUID.randomUUID());
    assertThat(mr.ref()).isEqualTo("7");
    assertThat(mr.url()).isNull();
    server.verify();
  }

  @Test
  void commitDaCoThiBoQua() {
    server
        .expect(
            requestTo(
                API
                    + "/projects/42/merge_requests?state=opened&source_branch=mau-luong%2Fmau-a-v2"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                API + "/projects/42/repository/branches?branch=mau-luong%2Fmau-a-v2&ref=main"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(API + "/projects/42/repository/commits"))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST));
    server
        .expect(requestTo(API + "/projects/42/merge_requests"))
        .andRespond(withSuccess("{\"iid\":9}", MediaType.APPLICATION_JSON));
    assertThat(host.open(pkg, UUID.randomUUID()).ref()).isEqualTo("9");
    server.verify();
  }

  @Test
  void duyetVaDatGopKhiPipelineDat() {
    server
        .expect(requestTo(API + "/projects/42/merge_requests/128/approve"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    server
        .expect(
            requestTo(
                API + "/projects/42/merge_requests/128/merge?merge_when_pipeline_succeeds=true"))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withStatus(HttpStatus.METHOD_NOT_ALLOWED));
    host.approve(UUID.randomUUID(), "128", UUID.randomUUID());
    server.verify();
  }

  @Test
  void loiMayChuThiNemDeSagaThuLai() {
    server
        .expect(requestTo(API + "/projects/42/merge_requests/128/approve"))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
    assertThatThrownBy(() -> host.approve(UUID.randomUUID(), "128", UUID.randomUUID()))
        .isInstanceOf(HttpServerErrorException.class);
  }

  @Test
  void khongCoTepTokenThiGoiKhongKemToken() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
    GitLabTemplateCodeHost noToken =
        new GitLabTemplateCodeHost(builder, API, "42", null, dir.resolve("khong-co").toString());
    s.expect(requestTo(API + "/projects/42/merge_requests/1/approve"))
        .andExpect(r -> assertThat(r.getHeaders().containsKey("PRIVATE-TOKEN")).isFalse())
        .andRespond(withSuccess());
    s.expect(
            requestTo(
                API + "/projects/42/merge_requests/1/merge?merge_when_pipeline_succeeds=true"))
        .andRespond(withSuccess());
    noToken.approve(UUID.randomUUID(), "1", UUID.randomUUID());
    s.verify();
  }
}
