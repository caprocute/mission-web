package vn.viettel.okrkpi.org.application.scim;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.org.application.AssignmentCommandService;
import vn.viettel.okrkpi.org.application.AssignmentCommandService.AssignCommand;
import vn.viettel.okrkpi.org.application.AssignmentCommandService.TransferCommand;
import vn.viettel.okrkpi.org.application.port.AssignmentStore;
import vn.viettel.okrkpi.org.application.port.FieldEncryptor;
import vn.viettel.okrkpi.org.application.port.OrgUnitStore;
import vn.viettel.okrkpi.org.application.port.ScimStore;
import vn.viettel.okrkpi.org.application.port.ScimStore.LogEntry;
import vn.viettel.okrkpi.org.application.port.ScimStore.Resource;
import vn.viettel.okrkpi.org.application.port.SyncPersonStore;
import vn.viettel.okrkpi.org.application.scim.ScimModels.Client;
import vn.viettel.okrkpi.org.application.scim.ScimModels.Created;
import vn.viettel.okrkpi.org.application.scim.ScimModels.GroupInput;
import vn.viettel.okrkpi.org.application.scim.ScimModels.GroupView;
import vn.viettel.okrkpi.org.application.scim.ScimModels.ListPage;
import vn.viettel.okrkpi.org.application.scim.ScimModels.UserInput;
import vn.viettel.okrkpi.org.application.scim.ScimModels.UserView;
import vn.viettel.okrkpi.org.application.sync.SourceRecord;
import vn.viettel.okrkpi.org.application.sync.SyncIngestService;
import vn.viettel.okrkpi.org.application.sync.SyncIngestService.PersonMatch;
import vn.viettel.okrkpi.org.domain.AssignmentVersion;
import vn.viettel.okrkpi.org.domain.OrgConflictException;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.viettel.okrkpi.org.domain.VersionConflictException;

/**
 * DV-04-06 · QT-01 tác vụ nền · SVC-04 SCIM 2.0 cho HRM và IdP của tenant (RFC 7643, RFC 7644).
 * <ul>
 *   <li>User ánh xạ sang {@code org.person}: khớp bằng chỉ mục mù số định danh và ngày sinh qua bộ so khớp của
 *       DV-04-07; không khớp thì tạo người mới, hoặc đẩy vào bảng lệch khi nguồn được cấu hình đối soát trước
 *       ({@code okrkpi.org.scim.review-unmatched-sources}). Trùng một phần (cùng số định danh khác ngày sinh, cùng họ
 *       tên) luôn vào bảng lệch.
 *   <li>{@code active}, DELETE: vô hiệu hoá là trạng thái công tác {@code TAM_NGHI} từ hôm nay, không xoá vật lý.
 *   <li>Group ánh xạ sang đơn vị đã có theo mã đơn vị ({@code externalId}, thiếu thì {@code displayName}); thành viên
 *       là phân công chính nhánh chính quyền tại đơn vị từ hôm nay, chức danh theo thuộc tính {@code title} của User.
 *   <li>Mọi lệnh ghi một dòng {@code org.scim_sync_log}; lệnh lặp lại đúng nội dung lệnh thành công gần nhất trên cùng
 *       tài nguyên được ghi {@code BO_QUA} và trả trạng thái hiện hành.
 * </ul>
 * Thay đổi người, phân công đi qua dịch vụ miền (phát EVT-16, EVT-15 qua outbox trong cùng giao dịch).
 */
@Service
public class ScimService {

  private static final Logger LOG = LoggerFactory.getLogger(ScimService.class);

  public static final String USER = "User";
  public static final String GROUP = "Group";
  /** Mã nguồn của lần chạy, dòng lệch cho mọi client SCIM ({@code ck_sync_run__connector}). */
  static final String CONNECTOR = "SCIM";
  static final String BRANCH = "CHINH_QUYEN";
  static final int MAX_COUNT = 200;

  private final ScimStore store;
  private final SyncIngestService ingest;
  private final SyncPersonStore persons;
  private final AssignmentStore assignments;
  private final AssignmentCommandService assignmentCommands;
  private final OrgUnitStore units;
  private final Clock clock;
  private final Set<String> reviewUnmatched;
  private final TransactionTemplate tx;
  private final FieldEncryptor crypto;

  public ScimService(FieldEncryptor crypto, ScimStore store, SyncIngestService ingest, SyncPersonStore persons, AssignmentStore assignments,
      AssignmentCommandService assignmentCommands, OrgUnitStore units, Clock clock, PlatformTransactionManager tm,
      @Value("${okrkpi.org.scim.review-unmatched-sources:}") List<String> reviewUnmatched) {
    this.crypto = crypto;
    this.store = store;
    this.ingest = ingest;
    this.persons = persons;
    this.assignments = assignments;
    this.assignmentCommands = assignmentCommands;
    this.units = units;
    this.clock = clock;
    this.tx = new TransactionTemplate(tm);
    this.reviewUnmatched = Set.copyOf(reviewUnmatched.stream().filter(s -> !s.isBlank()).toList());
  }

  /**
   * Băm khử trùng của một lệnh ({@code request_hash}): HMAC theo tenant của phương thức, đường dẫn và thân đã chuẩn
   * hoá, không dùng SHA-256 trần vì thân có số định danh (dò ngược được do không gian giá trị nhỏ).
   */
  public byte[] requestHash(Client c, String canonical) {
    return crypto.blindIndex(c.actor().tenantId(), "org.scim_sync_log.request_hash", canonical);
  }

  // ---------------------------------------------------------------- Users

  public UserView getUser(Client c, UUID id) {
    return read(() -> userView(c, live(c, USER, id)));
  }

  public ListPage<UserView> listUsers(Client c, String attribute, String value, int startIndex, int count) {
    return read(() -> page(c, USER, attribute, value, startIndex, count, r -> userView(c, r)));
  }

  public Created<UserView> createUser(Client c, UserInput in, byte[] hash) {
    return command(c, "CREATE", USER, rawExternalId(in.externalId(), in.userName()), hash, cmd -> {
      requireText(in.userName(), "userName");
      String ext = externalId(in.externalId(), in.userName());
      Optional<Resource> existing = store.findByExternalId(c.source(), USER, ext);
      if (existing.isPresent() && !existing.get().deleted()) {
        if (replay(c, USER, ext, hash)) {
          cmd.skip(existing.get().internalId());
          return new Created<>(userView(c, existing.get()), true);
        }
        throw ScimException.uniqueness("Người dùng đã tồn tại.");
      }
      uniqueUserName(c, in.userName(), null);
      String title = titleCode(in.title());
      String status = in.active() ? "DANG_CONG_TAC" : "TAM_NGHI";
      String fullName = in.displayName() == null || in.displayName().isBlank() ? in.userName() : in.displayName();
      SourceRecord record = new SourceRecord(ext, existing.map(Resource::internalId).orElse(null), fullName,
          in.nationalId(), in.birthDate(), status, Boolean.TRUE.equals(in.partyMember()), List.of(), today());
      PersonMatch match = ingest.matchPerson(c.actor(), record);
      UUID personId;
      if (match.mismatch() || (match.personId() == null && reviewUnmatched.contains(c.source()))) {
        ingest.recordUnmatched(c.actor(), CONNECTOR, record, match.matchedBy());
        return cmd.defer(new ScimException(409, "uniqueness",
            "Hồ sơ chưa khớp chắc chắn với cán bộ trên nền tảng, đã chuyển quản trị đối soát."));
      } else if (match.personId() == null) {
        personId = ingest.createPerson(c.actor(), CONNECTOR, record);
      } else {
        personId = match.personId();
        ingest.applyPersonState(c.actor(), personId, status, in.partyMember());
      }
      Resource r;
      if (existing.isPresent()) {
        r = withUser(existing.get(), personId, in, title, false);
        store.update(r, c.actor());
      } else {
        r = new Resource(UuidV7.next(clock), c.source(), USER, ext, personId, in.userName(), in.displayName(), title,
            in.active(), false, null, null, 0);
        store.insert(r, c.actor());
      }
      cmd.done(personId);
      return new Created<>(userView(c, live(c, USER, r.id())), false);
    });
  }

  /** PUT hoặc PATCH (đã gộp ở tầng web thành bộ thuộc tính đầy đủ); {@code version} là If-Match (null bỏ qua). */
  public UserView replaceUser(Client c, UUID id, UserInput in, Integer version, String operation, byte[] hash) {
    return command(c, operation, USER, null, hash, cmd -> {
      Resource current = live(c, USER, id);
      cmd.target(current.externalId(), current.internalId());
      if (replay(c, USER, current.externalId(), hash)) {
        cmd.skip(current.internalId());
        return userView(c, current);
      }
      checkVersion(current, version);
      requireText(in.userName(), "userName");
      uniqueUserName(c, in.userName(), current.id());
      String title = titleCode(in.title());
      ingest.applyPersonState(c.actor(), current.internalId(), in.active() ? "DANG_CONG_TAC" : "TAM_NGHI",
          in.partyMember());
      store.update(withUser(current, current.internalId(), in, title, false), c.actor());
      cmd.done(current.internalId());
      return userView(c, live(c, USER, id));
    });
  }

  public void deleteUser(Client c, UUID id, byte[] hash) {
    command(c, "DELETE", USER, null, hash, cmd -> {
      Resource current = live(c, USER, id);
      cmd.target(current.externalId(), current.internalId());
      ingest.applyPersonState(c.actor(), current.internalId(), "TAM_NGHI", null);
      store.update(new Resource(current.id(), current.source(), USER, current.externalId(), current.internalId(),
          current.userName(), current.displayName(), current.titleCode(), false, true, null, null, 0), c.actor());
      cmd.done(current.internalId());
      return null;
    });
  }

  // ---------------------------------------------------------------- Groups

  public GroupView getGroup(Client c, UUID id) {
    return read(() -> groupView(c, live(c, GROUP, id)));
  }

  public ListPage<GroupView> listGroups(Client c, String attribute, String value, int startIndex, int count) {
    return read(() -> page(c, GROUP, attribute, value, startIndex, count, r -> groupView(c, r)));
  }

  public Created<GroupView> createGroup(Client c, GroupInput in, byte[] hash) {
    return command(c, "CREATE", GROUP, rawExternalId(in.externalId(), in.displayName()), hash, cmd -> {
      String ext = externalId(in.externalId(), in.displayName());
      Optional<Resource> existing = store.findByExternalId(c.source(), GROUP, ext);
      if (existing.isPresent() && !existing.get().deleted()) {
        if (replay(c, GROUP, ext, hash)) {
          cmd.skip(existing.get().internalId());
          return new Created<>(groupView(c, existing.get()), true);
        }
        throw ScimException.uniqueness("Nhóm đã tồn tại.");
      }
      UUID unit = units.findIdByCode(ext, today())
          .orElseThrow(() -> ScimException.invalidValue("Không tìm thấy đơn vị có mã trùng với nhóm."));
      Resource r;
      if (existing.isPresent()) {
        r = new Resource(existing.get().id(), c.source(), GROUP, ext, unit, null, in.displayName(), null, true, false,
            null, null, 0);
        store.update(r, c.actor());
      } else {
        r = new Resource(UuidV7.next(clock), c.source(), GROUP, ext, unit, null, in.displayName(), null, true, false,
            null, null, 0);
        store.insert(r, c.actor());
      }
      setMembers(c, unit, in.members());
      cmd.done(unit);
      return new Created<>(groupView(c, live(c, GROUP, r.id())), false);
    });
  }

  public GroupView replaceGroup(Client c, UUID id, GroupInput in, Integer version, String operation, byte[] hash) {
    return command(c, operation, GROUP, null, hash, cmd -> {
      Resource current = live(c, GROUP, id);
      cmd.target(current.externalId(), current.internalId());
      if (replay(c, GROUP, current.externalId(), hash)) {
        cmd.skip(current.internalId());
        return groupView(c, current);
      }
      checkVersion(current, version);
      setMembers(c, current.internalId(), in.members());
      store.update(new Resource(current.id(), current.source(), GROUP, current.externalId(), current.internalId(),
          null, in.displayName() == null ? current.displayName() : in.displayName(), null, true, false, null, null, 0),
          c.actor());
      cmd.done(current.internalId());
      return groupView(c, live(c, GROUP, id));
    });
  }

  /** Xoá nhóm: kết thúc phân công của thành viên do nguồn này quản lý tại đơn vị, giữ đơn vị. */
  public void deleteGroup(Client c, UUID id, byte[] hash) {
    command(c, "DELETE", GROUP, null, hash, cmd -> {
      Resource current = live(c, GROUP, id);
      cmd.target(current.externalId(), current.internalId());
      setMembers(c, current.internalId(), List.of());
      store.update(new Resource(current.id(), current.source(), GROUP, current.externalId(), current.internalId(),
          null, current.displayName(), null, false, true, null, null, 0), c.actor());
      cmd.done(current.internalId());
      return null;
    });
  }

  // ---------------------------------------------------------------- thành viên nhóm

  private void setMembers(Client c, UUID unit, List<UUID> wanted) {
    LocalDate today = today();
    Set<UUID> target = new LinkedHashSet<>(wanted);
    List<UUID> current = store.members(c.source(), unit, BRANCH, today);
    for (UUID userId : current) {
      if (!target.contains(userId)) {
        Resource user = store.find(c.source(), USER, userId).orElseThrow();
        primaryAt(user.internalId(), today).filter(a -> a.orgUnitId().equals(unit))
            .ifPresent(a -> assignmentCommands.endAssignment(c.actor(), a.id(), today, null));
      }
    }
    for (UUID userId : target) {
      if (current.contains(userId)) {
        continue;
      }
      Resource user = store.find(c.source(), USER, userId).filter(r -> !r.deleted())
          .orElseThrow(() -> ScimException.invalidValue("Thành viên không phải người dùng của nguồn này."));
      if (user.titleCode() == null) {
        throw ScimException.invalidValue("Người dùng chưa có chức danh (title), chưa thể xếp vào đơn vị.");
      }
      UUID title = store.jobTitleIdByCode(user.titleCode())
          .orElseThrow(() -> ScimException.invalidValue("Chức danh của người dùng không có trong danh mục."));
      Optional<AssignmentVersion> primary = primaryAt(user.internalId(), today);
      if (primary.isPresent() && primary.get().validPeriod().from().isBefore(today)) {
        assignmentCommands.transferPrimaryAssignment(c.actor(), new TransferCommand(user.internalId(), unit, null,
            title, BRANCH, false, today, null, null, null, null, null, null));
        continue;
      }
      // Phân công chính bắt đầu đúng hôm nay (vừa xếp qua SCIM) được thay hẳn, không để khoảng hiệu lực rỗng.
      primary.ifPresent(a -> assignmentCommands.endAssignment(c.actor(), a.id(), today, null));
      assignmentCommands.recordAssignment(c.actor(), new AssignCommand(user.internalId(), unit, null, title, "CHINH",
          BRANCH, false, today, null, false, null, null, null, null, null));
    }
  }

  private Optional<AssignmentVersion> primaryAt(UUID personId, LocalDate day) {
    return assignments.findCurrentByPersonAndBranch(personId, BRANCH).stream()
        .filter(a -> "CHINH".equals(a.kind()) && a.validPeriod().contains(day)).findFirst();
  }

  // ---------------------------------------------------------------- khung lệnh

  /** Ngữ cảnh một lệnh ghi: tài nguyên đích, kết quả ghi nhật ký, lỗi trả sau khi đã cam kết. */
  private static final class Command {
    private String externalId;
    private UUID internalId;
    private String status = "LOI";
    private ScimException deferred;

    void target(String ext, UUID internal) {
      externalId = ext;
      internalId = internal;
    }

    void done(UUID internal) {
      internalId = internal;
      status = "THANH_CONG";
    }

    void skip(UUID internal) {
      internalId = internal;
      status = "BO_QUA";
    }

    <T> T defer(ScimException e) {
      deferred = e;
      return null;
    }
  }

  private <T> T command(Client c, String operation, String type, String externalId, byte[] hash,
      Function<Command, T> work) {
    Command cmd = new Command();
    cmd.externalId = externalId;
    try {
      T out = tx.execute(s -> {
        T value = work.apply(cmd);
        log(c, operation, type, cmd, hash, cmd.deferred == null ? cmd.status : "LOI",
            cmd.deferred == null ? null : cmd.deferred.getMessage());
        return value;
      });
      if (cmd.deferred != null) {
        throw cmd.deferred;
      }
      return out;
    } catch (RuntimeException e) {
      if (e == cmd.deferred) {
        throw e;
      }
      ScimException scim = translate(e);
      try {
        tx.executeWithoutResult(s -> log(c, operation, type, cmd, hash, "LOI", scim.getMessage()));
      } catch (RuntimeException logError) {
        LOG.warn("Không ghi được nhật ký SCIM ({}): {}", operation, logError.getClass().getSimpleName());
      }
      throw scim;
    }
  }

  private void log(Client c, String operation, String type, Command cmd, byte[] hash, String status, String error) {
    String ext = cmd.externalId == null || cmd.externalId.isBlank() ? "-" : cmd.externalId;
    store.log(new LogEntry(UuidV7.next(clock), c.source(), operation, type, ext.length() > 128 ? ext.substring(0, 128)
        : ext, cmd.internalId, hash, status, error), c.actor());
  }

  private <T> T read(java.util.function.Supplier<T> work) {
    try {
      return tx.execute(s -> work.get());
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  private static ScimException translate(RuntimeException e) {
    if (e instanceof ScimException s) {
      return s;
    }
    if (e instanceof OrgNotFoundException) {
      return ScimException.notFound();
    }
    if (e instanceof VersionConflictException) {
      return new ScimException(412, null, "Tài nguyên đã thay đổi, cần đọc lại trước khi sửa.");
    }
    if (e instanceof OrgRuleViolation || e instanceof OrgConflictException) {
      return new ScimException(409, null, e.getMessage());
    }
    if (e instanceof IllegalArgumentException) {
      return ScimException.invalidValue("Dữ liệu không hợp lệ.");
    }
    LOG.warn("Lệnh SCIM lỗi: {}", e.getClass().getSimpleName());
    return new ScimException(500, null, "Lỗi hệ thống, vui lòng thử lại sau.");
  }

  // ---------------------------------------------------------------- tiện ích

  private <T> ListPage<T> page(Client c, String type, String attribute, String value, int startIndex, int count,
      Function<Resource, T> view) {
    int start = Math.max(1, startIndex);
    int size = Math.max(0, Math.min(MAX_COUNT, count));
    int total = store.count(c.source(), type, attribute, value);
    List<T> items = new ArrayList<>();
    if (size > 0) {
      for (Resource r : store.list(c.source(), type, attribute, value, start - 1, size)) {
        items.add(view.apply(r));
      }
    }
    return new ListPage<>(total, start, items);
  }

  private Resource live(Client c, String type, UUID id) {
    return store.find(c.source(), type, id).filter(r -> !r.deleted()).orElseThrow(ScimException::notFound);
  }

  private UserView userView(Client c, Resource r) {
    SyncPersonStore.PersonRow p = persons.findById(r.internalId(), today()).orElse(null);
    return new UserView(r, p == null ? null : p.employmentStatus(), p != null && p.partyMember());
  }

  private GroupView groupView(Client c, Resource r) {
    return new GroupView(r, store.members(c.source(), r.internalId(), BRANCH, today()));
  }

  private boolean replay(Client c, String type, String ext, byte[] hash) {
    return hash != null && store.lastSuccessfulHash(c.source(), type, ext).map(h -> Arrays.equals(h, hash))
        .orElse(false);
  }

  private static void checkVersion(Resource current, Integer version) {
    if (version != null && version != current.version()) {
      throw new ScimException(412, null, "Tài nguyên đã thay đổi, cần đọc lại trước khi sửa.");
    }
  }

  private void uniqueUserName(Client c, String userName, UUID self) {
    for (Resource r : store.list(c.source(), USER, "userName", userName, 0, 2)) {
      if (!r.id().equals(self)) {
        throw ScimException.uniqueness("userName đã được dùng cho người dùng khác.");
      }
    }
  }

  private String titleCode(String title) {
    if (title == null || title.isBlank()) {
      return null;
    }
    String code = title.trim();
    if (code.length() > 32 || store.jobTitleIdByCode(code).isEmpty()) {
      throw ScimException.invalidValue("Chức danh (title) không có trong danh mục.");
    }
    return code;
  }

  private static Resource withUser(Resource r, UUID personId, UserInput in, String title, boolean deleted) {
    return new Resource(r.id(), r.source(), USER, r.externalId(), personId, in.userName(), in.displayName(), title,
        in.active(), deleted, null, null, r.version());
  }

  private static String rawExternalId(String externalId, String fallback) {
    return externalId == null || externalId.isBlank() ? fallback : externalId;
  }

  private static String externalId(String externalId, String fallback) {
    String ext = rawExternalId(externalId, fallback);
    if (ext == null || ext.isBlank()) {
      throw ScimException.invalidValue("Thiếu externalId.");
    }
    if (ext.length() > 128) {
      throw ScimException.invalidValue("externalId dài quá 128 ký tự.");
    }
    return ext;
  }

  private static void requireText(String value, String attribute) {
    if (value == null || value.isBlank()) {
      throw ScimException.invalidValue("Thiếu thuộc tính bắt buộc " + attribute + ".");
    }
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }
}
