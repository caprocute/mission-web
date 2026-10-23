package vn.viettel.okrkpi.workflow.infrastructure.release;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.viettel.okrkpi.workflow.application.port.CellEngineGateway;

/**
 * Bộ chuyển mặc định của {@link CellEngineGateway} cho dev và kiểm thử: mọi cell trong cấu hình
 * dùng chung engine Flowable của chính dịch vụ (môi trường một cell). Triển khai đặt tên {@code
 * mau-luong/<mã>/v<phiên bản>}, lọc trùng nên gọi lại không tạo bản mới; đặt lại phiên bản trước
 * thì gỡ lần triển khai mới khi chưa có tiến trình chạy, không thì triển khai lại nội dung phiên
 * bản trước để nó thành mặc định. Sức khoẻ là engine trả lời được và cell không bị đánh dấu sự cố
 * ({@link #simulateOutage}, dùng cho thử bù trừ).
 */
public class LocalCellEngineGateway implements CellEngineGateway {

  private static final Logger log = LoggerFactory.getLogger(LocalCellEngineGateway.class);
  private static final Pattern NAME = Pattern.compile("^mau-luong/(.+)/v(\\d+)(/.*)?$");

  private final RepositoryService repository;
  private final ManagementService management;
  private final Set<String> outages = ConcurrentHashMap.newKeySet();

  public LocalCellEngineGateway(
      RepositoryService repository, ManagementService management, List<String> outageCells) {
    this.repository = repository;
    this.management = management;
    if (outageCells != null) {
      outageCells.stream().map(String::strip).filter(c -> !c.isEmpty()).forEach(outages::add);
    }
  }

  /** Đánh dấu hoặc gỡ đánh dấu sự cố của một cell (dev, kiểm thử bù trừ). */
  public void simulateOutage(String cellCode, boolean down) {
    if (down) {
      outages.add(cellCode);
    } else {
      outages.remove(cellCode);
    }
  }

  @Override
  public boolean healthy(String cellCode) {
    if (outages.contains(cellCode)) {
      return false;
    }
    try {
      management.getProperties();
      return true;
    } catch (RuntimeException e) {
      log.warn("Engine của cell {} không trả lời", cellCode);
      return false;
    }
  }

  @Override
  public Integer currentVersion(String cellCode, String templateCode) {
    return latest(templateCode).map(LocalCellEngineGateway::version).orElse(null);
  }

  @Override
  public String deploy(String cellCode, String templateCode, int versionNo, String bpmnXml) {
    return repository
        .createDeployment()
        .name(name(templateCode, versionNo))
        .category("mau-luong")
        .addString(templateCode + ".bpmn20.xml", bpmnXml)
        .enableDuplicateFiltering()
        .deploy()
        .getId();
  }

  @Override
  public void restorePrevious(
      String cellCode, String templateCode, Integer previousVersionNo, String previousBpmnXml) {
    Optional<Deployment> current = latest(templateCode);
    if (current.isEmpty()) {
      return;
    }
    Integer currentVersion = version(current.get());
    if (previousVersionNo != null && previousVersionNo.equals(currentVersion)) {
      return;
    }
    try {
      repository.deleteDeployment(current.get().getId(), false);
      return;
    } catch (RuntimeException e) {
      log.info(
          "Phiên bản mới của mẫu {} đã có tiến trình chạy, triển khai lại phiên bản trước",
          templateCode);
    }
    if (previousVersionNo != null && previousBpmnXml != null) {
      repository
          .createDeployment()
          .name(name(templateCode, previousVersionNo) + "/dat-lai")
          .category("mau-luong")
          .addString(templateCode + ".bpmn20.xml", previousBpmnXml)
          .deploy();
    }
  }

  private Optional<Deployment> latest(String templateCode) {
    return repository
        .createDeploymentQuery()
        .deploymentNameLike("mau-luong/" + templateCode + "/v%")
        .orderByDeploymentTime()
        .desc()
        .listPage(0, 1)
        .stream()
        .findFirst();
  }

  private static Integer version(Deployment d) {
    Matcher m = NAME.matcher(String.valueOf(d.getName()));
    return m.matches() ? Integer.valueOf(m.group(2)) : null;
  }

  static String name(String templateCode, int versionNo) {
    return "mau-luong/" + templateCode + "/v" + versionNo;
  }
}
