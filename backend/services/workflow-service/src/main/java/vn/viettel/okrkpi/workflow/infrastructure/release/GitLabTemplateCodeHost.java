package vn.viettel.okrkpi.workflow.infrastructure.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import vn.viettel.okrkpi.workflow.application.port.TemplateCodeHost;

/**
 * Bộ chuyển HTTP của {@link TemplateCodeHost} cho hệ quản lý mã có API kiểu GitLab v4 (BS-01 "Đồng
 * bộ với kho mã"): tạo nhánh, commit gói mẫu, mở merge request; khi phê duyệt thì duyệt và đặt gộp
 * khi pipeline đạt (pipeline chạy lại linter, test và ký cosign rồi gọi lại dịch vụ). Gọi lại an
 * toàn: nhánh, commit hay merge request đã có thì dùng lại.
 *
 * <p>Token đọc từ tệp do hạ tầng gắn (không đặt bí mật trong cấu hình); địa chỉ và mã dự án lấy từ
 * cấu hình.
 */
public class GitLabTemplateCodeHost implements TemplateCodeHost {

  private static final Logger log = LoggerFactory.getLogger(GitLabTemplateCodeHost.class);

  private final RestClient http;
  private final String project;
  private final String targetBranch;
  private final String tokenFile;

  public GitLabTemplateCodeHost(
      RestClient.Builder builder,
      String apiBaseUrl,
      String project,
      String targetBranch,
      String tokenFile) {
    this.http = builder.baseUrl(apiBaseUrl).build();
    this.project = project;
    this.targetBranch = targetBranch == null || targetBranch.isBlank() ? "main" : targetBranch;
    this.tokenFile = tokenFile;
  }

  @Override
  public MergeRequest open(ReleasePackage pkg, UUID actor) {
    Map<String, Object> existing = findOpen(pkg.branch());
    if (existing != null) {
      return toMergeRequest(existing);
    }
    createBranch(pkg.branch());
    commit(pkg);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("source_branch", pkg.branch());
    body.put("target_branch", targetBranch);
    body.put("title", pkg.title());
    body.put("remove_source_branch", true);
    Map<String, Object> created =
        http.post()
            .uri("/projects/{p}/merge_requests", project)
            .headers(this::auth)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(MAP);
    return toMergeRequest(created);
  }

  @Override
  public void approve(UUID templateId, String mergeRequestRef, UUID approver) {
    try {
      http.post()
          .uri("/projects/{p}/merge_requests/{iid}/approve", project, mergeRequestRef)
          .headers(this::auth)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      // 401 khi tài khoản dịch vụ đã duyệt trước đó: coi như đã duyệt.
      if (e.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
        throw e;
      }
    }
    try {
      http.put()
          .uri(
              "/projects/{p}/merge_requests/{iid}/merge?merge_when_pipeline_succeeds=true",
              project,
              mergeRequestRef)
          .headers(this::auth)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      // 405/406/409: đã gộp hoặc đã đặt gộp khi pipeline đạt.
      int status = e.getStatusCode().value();
      if (status != 405 && status != 406 && status != 409) {
        throw e;
      }
      log.info("Merge request {} đã được đặt gộp trước đó ({})", mergeRequestRef, status);
    }
  }

  private Map<String, Object> findOpen(String branch) {
    List<Map<String, Object>> found =
        http.get()
            .uri("/projects/{p}/merge_requests?state=opened&source_branch={b}", project, branch)
            .headers(this::auth)
            .retrieve()
            .body(LIST);
    return found == null || found.isEmpty() ? null : found.get(0);
  }

  private void createBranch(String branch) {
    try {
      http.post()
          .uri(
              "/projects/{p}/repository/branches?branch={b}&ref={r}", project, branch, targetBranch)
          .headers(this::auth)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() != HttpStatus.BAD_REQUEST.value()) {
        throw e;
      }
      log.info("Nhánh {} đã có, dùng lại", branch);
    }
  }

  private void commit(ReleasePackage pkg) {
    List<Map<String, Object>> actions = new ArrayList<>();
    pkg.files().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            f -> {
              Map<String, Object> action = new LinkedHashMap<>();
              action.put("action", "create");
              action.put("file_path", f.getKey());
              action.put("content", f.getValue());
              actions.add(action);
            });
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("branch", pkg.branch());
    body.put("commit_message", pkg.title());
    body.put("actions", actions);
    try {
      http.post()
          .uri("/projects/{p}/repository/commits", project)
          .headers(this::auth)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      // 400 "A file with this name already exists": commit của lần gọi trước đã vào nhánh.
      if (e.getStatusCode().value() != HttpStatus.BAD_REQUEST.value()) {
        throw e;
      }
      log.info("Gói mẫu {} đã được commit vào nhánh {}", pkg.templateCode(), pkg.branch());
    }
  }

  private static MergeRequest toMergeRequest(Map<String, Object> mr) {
    Object iid = mr.get("iid");
    Object url = mr.get("web_url");
    return new MergeRequest(String.valueOf(iid), url == null ? null : String.valueOf(url));
  }

  private void auth(HttpHeaders headers) {
    String token = token();
    if (token != null) {
      headers.set("PRIVATE-TOKEN", token);
    }
  }

  private String token() {
    if (tokenFile == null || tokenFile.isBlank()) {
      return null;
    }
    try {
      return Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      log.warn("Không đọc được tệp token của hệ quản lý mã");
      return null;
    }
  }

  private static final org.springframework.core.ParameterizedTypeReference<Map<String, Object>>
      MAP = new org.springframework.core.ParameterizedTypeReference<>() {};
  private static final org.springframework.core.ParameterizedTypeReference<
          List<Map<String, Object>>>
      LIST = new org.springframework.core.ParameterizedTypeReference<>() {};
}
