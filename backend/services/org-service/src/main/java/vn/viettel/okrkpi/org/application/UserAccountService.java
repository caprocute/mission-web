package vn.viettel.okrkpi.org.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.org.application.AssignmentCommandService.AssignCommand;
import vn.viettel.okrkpi.org.application.port.AccountDirectoryPort;
import vn.viettel.okrkpi.org.application.port.AccountDirectoryPort.DirectoryAccount;
import vn.viettel.okrkpi.org.application.port.AccountDirectoryPort.NewAccount;
import vn.viettel.okrkpi.org.application.port.IdempotencyPort;
import vn.viettel.okrkpi.org.application.port.OrgUnitStore;
import vn.viettel.okrkpi.org.application.port.PolicyPort;
import vn.viettel.okrkpi.org.application.port.SyncPersonStore;
import vn.viettel.okrkpi.org.application.port.SyncPersonStore.NewPerson;
import vn.viettel.okrkpi.org.application.port.TreeQueryPort;
import vn.viettel.okrkpi.org.application.port.TreeQueryPort.TreeRow;
import vn.viettel.okrkpi.org.application.port.UserAccountStore;
import vn.viettel.okrkpi.org.application.port.UserAccountStore.AccountRow;
import vn.viettel.okrkpi.org.application.port.UserAccountStore.JobTitleRow;
import vn.viettel.okrkpi.org.application.port.UserAccountStore.PersonRow;
import vn.viettel.okrkpi.org.application.sync.SyncSupport;
import vn.viettel.okrkpi.org.domain.OrgConflictException;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;
import vn.viettel.okrkpi.org.domain.OrgUnitVersion;
import vn.viettel.okrkpi.org.domain.TreeKind;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.viettel.okrkpi.org.domain.VersionConflictException;

/**
 * Quản trị tài khoản người dùng trong ứng dụng (DV-04-09, BS-04 ADR-34 mục 2; SCR-0108, SCR-0109; BR-01-19 đến
 * BR-01-22). Tài khoản gồm hồ sơ cán bộ {@code org.person}, phân công chính {@code org.assignment}, dòng
 * {@code org.user_account} và người dùng Keycloak cùng mã. Keycloak được gọi đồng bộ trong yêu cầu để khoá, mở khoá,
 * đặt lại mật khẩu có hiệu lực ngay; tạo tài khoản mà phần ghi CSDL bị huỷ thì xoá người dùng Keycloak vừa tạo.
 */
@Service
public class UserAccountService {

  private static final Logger LOG = LoggerFactory.getLogger(UserAccountService.class);
  static final String ACTIVE = "HOAT_DONG";
  static final String LOCKED = "KHOA";
  private static final Pattern USERNAME = Pattern.compile("^[a-z][a-z0-9._-]{2,63}$");
  private static final int BACKFILL_LIMIT = 200;
  private static final int SUBTREE_DEPTH = 12;
  // Bỏ ký tự dễ nhầm (0/O, 1/l/I) để người dùng chép mật khẩu tạm không sai.
  private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
  private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
  private static final String DIGIT = "23456789";
  private static final String SYMBOL = "@#$%&*+-=?";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserAccountStore accounts;
  private final AccountDirectoryPort directory;
  private final SyncPersonStore persons;
  private final OrgUnitStore units;
  private final TreeQueryPort trees;
  private final AssignmentCommandService assignments;
  private final SyncSupport sync;
  private final UnitSupport support;
  private final IdempotencyPort idempotency;
  private final PolicyPort policy;
  private final Clock clock;

  public UserAccountService(UserAccountStore accounts, AccountDirectoryPort directory, SyncPersonStore persons,
      OrgUnitStore units, TreeQueryPort trees, AssignmentCommandService assignments, SyncSupport sync,
      UnitSupport support, IdempotencyPort idempotency, PolicyPort policy, Clock clock) {
    this.accounts = accounts;
    this.directory = directory;
    this.persons = persons;
    this.units = units;
    this.trees = trees;
    this.assignments = assignments;
    this.sync = sync;
    this.support = support;
    this.idempotency = idempotency;
    this.policy = policy;
    this.clock = clock;
  }

  /** Tài khoản kèm hồ sơ để hiển thị; {@code email} chỉ có khi đọc một tài khoản (đọc từ Keycloak). */
  public record UserAccountView(UUID id, String username, String fullName, String email, String status,
      UUID primaryUnitId, String primaryUnitName, String jobTitleName, int concurrentCount, OffsetDateTime lockedAt,
      String lockReason, OffsetDateTime passwordResetAt, OffsetDateTime createdAt, String source, int rowVersion,
      Set<String> permittedActions) {
  }

  public record Counts(int total, int active, int locked) {
  }

  public record AccountPage(List<UserAccountView> items, String nextCursor, Counts counts) {
  }

  public record CreateCommand(String fullName, String username, String email, UUID orgUnitId, UUID jobTitleId,
      String gender, boolean partyMember, LocalDate validFrom) {
  }

  /** Kết quả tạo; mật khẩu tạm chỉ trả một lần, không lưu ở khoá chống ghi lặp hay nhật ký (BR-01-22). */
  public record Created(UserAccountView account, String temporaryPassword) {
  }

  /** Bản ghi khoá chống ghi lặp của lần tạo (không có mật khẩu). */
  public record CreatedRef(UUID id) {
  }

  // ------------------------------------------------------------------ đọc

  @Transactional
  public AccountPage list(Actor actor, UUID unitId, boolean includeSubunits, String q, String status, int limit,
      String cursor) {
    policy.authorize(actor, "listUserAccounts");
    LocalDate today = support.today();
    Set<UUID> scope = null;
    UUID target = unitId != null ? unitId : policy.hasTenantScope(actor) ? null : actor.unitId();
    if (target != null) {
      scope = new HashSet<>();
      scope.add(target);
      if (includeSubunits) {
        TemporalQuery query = support.resolve(today, null);
        for (TreeKind tree : TreeKind.values()) {
          for (TreeRow r : trees.subtree(tree, target, query, SUBTREE_DEPTH)) {
            scope.add(r.id());
          }
        }
      }
    }
    List<PersonRow> people = accounts.people(scope, today);
    Map<UUID, AccountRow> rows = withBackfill(people, actor);
    String folded = q == null || q.isBlank() ? null : TextFold.fold(q);
    List<UserAccountView> matched = new ArrayList<>();
    for (PersonRow p : people) {
      AccountRow a = rows.get(p.personId());
      if (a == null) {
        continue;
      }
      if (folded != null && !TextFold.fold(p.fullName()).contains(folded) && !a.username().contains(folded)) {
        continue;
      }
      matched.add(view(p, a, null, actor));
    }
    int active = (int) matched.stream().filter(v -> ACTIVE.equals(v.status())).count();
    Counts counts = new Counts(matched.size(), active, matched.size() - active);
    List<UserAccountView> filtered = matched.stream()
        .filter(v -> status == null || status.equals(v.status()))
        .sorted(Comparator.comparing((UserAccountView v) -> TextFold.fold(v.fullName()))
            .thenComparing(v -> v.id().toString()))
        .toList();
    int from = 0;
    if (cursor != null && !cursor.isBlank()) {
      List<String> keys = Cursors.decode(cursor, 2);
      while (from < filtered.size() && compare(filtered.get(from), keys) <= 0) {
        from++;
      }
    }
    int to = Math.min(filtered.size(), from + limit);
    String next = to < filtered.size()
        ? Cursors.encode(TextFold.fold(filtered.get(to - 1).fullName()), filtered.get(to - 1).id().toString())
        : null;
    return new AccountPage(filtered.subList(from, to), next, counts);
  }

  private static int compare(UserAccountView v, List<String> keys) {
    int c = TextFold.fold(v.fullName()).compareTo(keys.get(0));
    return c != 0 ? c : v.id().toString().compareTo(keys.get(1));
  }

  @Transactional
  public UserAccountView get(Actor actor, UUID id) {
    policy.authorize(actor, "getUserAccount");
    return load(actor, id, true);
  }

  public List<JobTitleRow> jobTitles(Actor actor, String q) {
    policy.authorize(actor, "listJobTitles");
    String folded = q == null || q.isBlank() ? null : TextFold.fold(q);
    return accounts.jobTitles(support.today()).stream()
        .filter(j -> folded == null || TextFold.fold(j.name()).contains(folded))
        .toList();
  }

  // ------------------------------------------------------------------ ghi

  @Transactional
  public Created create(Actor actor, IdempotencyRequest idem, CreateCommand cmd) {
    policy.authorize(actor, "createUserAccount");
    if (idempotency.begin(actor, idem, CreatedRef.class).isPresent()) {
      // Không trả lại mật khẩu tạm của lần trước (không lưu ở đâu); người quản trị đặt lại mật khẩu nếu cần.
      throw new OrgConflictException("ORG-409-ALREADY-CREATED",
          "Tài khoản đã được tạo bởi yêu cầu trước. Vui lòng đặt lại mật khẩu nếu chưa nhận được mật khẩu tạm.");
    }
    String username = cmd.username().strip().toLowerCase(java.util.Locale.ROOT);
    if (!USERNAME.matcher(username).matches()) {
      throw new OrgRuleViolation("ORG-422-USERNAME", "BR-01-19", null, "username",
          "Tên đăng nhập gồm 3 đến 64 ký tự chữ thường không dấu, số, dấu chấm, gạch dưới, gạch nối, bắt đầu bằng chữ.");
    }
    LocalDate from = cmd.validFrom() != null ? cmd.validFrom() : support.today();
    TemporalQuery at = support.resolve(from, null);
    OrgUnitVersion unit = units.findAt(cmd.orgUnitId(), at).orElseThrow(() -> new OrgRuleViolation(
        "ORG-422-NOT-VALID-AT-DATE", "BR-01-20", null, "orgUnitId", "Đơn vị chưa có hiệu lực tại ngày bắt đầu."));
    if (accounts.jobTitles(from).stream().noneMatch(j -> j.id().equals(cmd.jobTitleId()))) {
      throw new OrgRuleViolation("ORG-422-VALIDATION", "BR-01-20", null, "jobTitleId", "Chức danh không hợp lệ.");
    }
    String password = temporaryPassword();
    UUID id = directory.create(new NewAccount(username, cmd.fullName().strip(), blankToNull(cmd.email()),
        actor.tenantId(), cmd.orgUnitId(), password));
    compensateOnRollback(id);
    persons.insert(new NewPerson(UuidV7.next(clock), id, cmd.fullName().strip(), null, null, null, null,
        cmd.partyMember(), "DANG_CONG_TAC", from), cmd.gender(), actor);
    sync.emitPersonChanged(id, actor);
    TreeKind tree = support.treeOf(unit.id(), unit.content().unitType(), at);
    assignments.recordAssignment(actor, new AssignCommand(id, cmd.orgUnitId(), null, cmd.jobTitleId(), "CHINH",
        tree == TreeKind.DANG ? "DANG" : "CHINH_QUYEN", false, from, null, false, null, null, null, null, null));
    accounts.insert(id, username, ACTIVE, "QUAN_TRI", actor);
    idempotency.complete(actor, idem, 201, new CreatedRef(id), id.toString());
    LOG.info("Đã tạo tài khoản {} cho cán bộ {}", username, id);
    return new Created(load(actor, id, true), password);
  }

  @Transactional
  public UserAccountView update(Actor actor, UUID id, int expected, IdempotencyRequest idem, String fullName,
      String email) {
    policy.authorize(actor, "updateUserAccount");
    if (idempotency.begin(actor, idem, CreatedRef.class).isPresent()) {
      return load(actor, id, true);
    }
    AccountRow row = requireRow(actor, id);
    if (row.rowVersion() != expected) {
      throw new VersionConflictException(expected, row.rowVersion());
    }
    String name = fullName == null || fullName.isBlank() ? null : fullName.strip();
    directory.updateProfile(id, name, email == null ? null : email.strip());
    if (name != null && !name.equals(accounts.person(id, support.today()).map(PersonRow::fullName).orElse(null))) {
      persons.renameFrom(id, support.today(), name, actor);
      sync.emitPersonChanged(id, actor);
    }
    if (!accounts.touch(id, expected, actor)) {
      throw new VersionConflictException(expected, requireRow(actor, id).rowVersion());
    }
    idempotency.complete(actor, idem, 200, new CreatedRef(id), id.toString());
    return load(actor, id, true);
  }

  @Transactional
  public UserAccountView lock(Actor actor, UUID id, int expected, IdempotencyRequest idem, String reason) {
    policy.authorize(actor, "lockUserAccount");
    if (id.equals(actor.actorId())) {
      throw new OrgRuleViolation("ORG-422-SELF-LOCK", "BR-01-21", null, null,
          "Không thể tự khoá tài khoản của chính mình.");
    }
    return setStatus(actor, id, expected, idem, LOCKED, reason);
  }

  @Transactional
  public UserAccountView unlock(Actor actor, UUID id, int expected, IdempotencyRequest idem, String reason) {
    policy.authorize(actor, "unlockUserAccount");
    return setStatus(actor, id, expected, idem, ACTIVE, reason);
  }

  private UserAccountView setStatus(Actor actor, UUID id, int expected, IdempotencyRequest idem, String status,
      String reason) {
    if (idempotency.begin(actor, idem, CreatedRef.class).isPresent()) {
      return load(actor, id, true);
    }
    AccountRow row = requireRow(actor, id);
    if (row.rowVersion() != expected) {
      throw new VersionConflictException(expected, row.rowVersion());
    }
    if (!accounts.setStatus(id, status, reason.strip(), expected, actor)) {
      throw new VersionConflictException(expected, requireRow(actor, id).rowVersion());
    }
    // Gọi Keycloak sau khi ghi CSDL để lỗi Keycloak huỷ cả lần ghi.
    directory.setEnabled(id, ACTIVE.equals(status));
    idempotency.complete(actor, idem, 200, new CreatedRef(id), id.toString());
    LOG.info("Tài khoản {} chuyển sang {} bởi {}", id, status, actor.actorId());
    return load(actor, id, true);
  }

  @Transactional
  public String resetPassword(Actor actor, UUID id, IdempotencyRequest idem) {
    policy.authorize(actor, "resetUserPassword");
    if (idempotency.begin(actor, idem, CreatedRef.class).isPresent()) {
      throw new OrgConflictException("ORG-409-ALREADY-RESET",
          "Mật khẩu đã được đặt lại bởi yêu cầu trước. Vui lòng đặt lại lần nữa nếu chưa nhận được mật khẩu tạm.");
    }
    requireRow(actor, id);
    String password = temporaryPassword();
    accounts.markPasswordReset(id, actor);
    directory.resetPassword(id, password);
    idempotency.complete(actor, idem, 200, new CreatedRef(id), id.toString());
    LOG.info("Đã đặt lại mật khẩu tài khoản {} bởi {}", id, actor.actorId());
    return password;
  }

  // ------------------------------------------------------------------ nội bộ

  private UserAccountView load(Actor actor, UUID id, boolean withEmail) {
    PersonRow p = accounts.person(id, support.today())
        .orElseThrow(() -> new OrgNotFoundException("Không tìm thấy người dùng."));
    AccountRow a = requireRow(actor, id);
    String email = withEmail ? directory.find(id).map(DirectoryAccount::email).orElse(null) : null;
    return view(p, a, email, actor);
  }

  private AccountRow requireRow(Actor actor, UUID id) {
    Optional<AccountRow> row = accounts.find(id);
    if (row.isPresent()) {
      return row.get();
    }
    if (accounts.person(id, support.today()).isEmpty()) {
      throw new OrgNotFoundException("Không tìm thấy người dùng.");
    }
    DirectoryAccount d = directory.find(id)
        .orElseThrow(() -> new OrgNotFoundException("Cán bộ chưa có tài khoản đăng nhập."));
    accounts.insert(id, d.username(), d.enabled() ? ACTIVE : LOCKED, "NAP_SAN", actor);
    return accounts.find(id).orElseThrow();
  }

  /**
   * Dòng tài khoản của những người trong danh sách; người chưa có dòng mà có người dùng Keycloak cùng mã (dữ liệu nạp
   * sẵn) được ghi bù, tối đa {@value #BACKFILL_LIMIT} người mỗi lần để không kéo dài yêu cầu.
   */
  private Map<UUID, AccountRow> withBackfill(List<PersonRow> people, Actor actor) {
    Map<UUID, AccountRow> rows = new HashMap<>();
    for (AccountRow a : accounts.findAll(people.stream().map(PersonRow::personId).toList())) {
      rows.put(a.personId(), a);
    }
    int budget = BACKFILL_LIMIT;
    for (PersonRow p : people) {
      if (rows.containsKey(p.personId()) || budget-- <= 0) {
        continue;
      }
      try {
        Optional<DirectoryAccount> d = directory.find(p.personId());
        if (d.isPresent()) {
          accounts.insert(p.personId(), d.get().username(), d.get().enabled() ? ACTIVE : LOCKED, "NAP_SAN", actor);
          accounts.find(p.personId()).ifPresent(a -> rows.put(a.personId(), a));
        }
      } catch (RuntimeException e) {
        // Keycloak không phản hồi: hiện những tài khoản đã có, lần sau ghi bù tiếp.
        LOG.warn("Không ghi bù được tài khoản của cán bộ {}: {}", p.personId(), e.getMessage());
        break;
      }
    }
    return rows;
  }

  private UserAccountView view(PersonRow p, AccountRow a, String email, Actor actor) {
    Set<String> actions = new LinkedHashSet<>();
    actions.add("SCR-0108.xem");
    actions.add("SCR-0109.xem");
    if (policy.permits(actor, "updateUserAccount")) {
      actions.add("SCR-0109.sua");
    }
    if (policy.permits(actor, "createOrgAssignment")) {
      actions.add("SCR-0109.phan-cong");
    }
    if (policy.permits(actor, "resetUserPassword") && ACTIVE.equals(a.status())) {
      actions.add("SCR-0109.dat-lai-mat-khau");
    }
    boolean self = p.personId().equals(actor.actorId());
    if (!self && policy.permits(actor, LOCKED.equals(a.status()) ? "unlockUserAccount" : "lockUserAccount")) {
      actions.add("SCR-0108.khoa");
    }
    return new UserAccountView(p.personId(), a.username(), p.fullName(), email, a.status(), p.unitId(),
        p.unitName(), p.jobTitleName(), p.concurrentCount(), a.lockedAt(), a.lockReason(), a.passwordResetAt(),
        a.createdAt(), a.source(), a.rowVersion(), actions);
  }

  private void compensateOnRollback(UUID id) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCompletion(int status) {
        if (status != STATUS_COMMITTED) {
          try {
            directory.delete(id);
          } catch (RuntimeException e) {
            LOG.error("Không xoá được người dùng Keycloak {} sau khi huỷ tạo tài khoản", id, e);
          }
        }
      }
    });
  }

  /** Mật khẩu tạm 16 ký tự, đủ chữ thường, chữ hoa, số và ký hiệu. */
  static String temporaryPassword() {
    String all = LOWER + UPPER + DIGIT + SYMBOL;
    List<Character> chars = new ArrayList<>();
    for (String set : List.of(LOWER, UPPER, DIGIT, SYMBOL)) {
      chars.add(set.charAt(RANDOM.nextInt(set.length())));
    }
    while (chars.size() < 16) {
      chars.add(all.charAt(RANDOM.nextInt(all.length())));
    }
    java.util.Collections.shuffle(chars, RANDOM);
    StringBuilder sb = new StringBuilder();
    chars.forEach(sb::append);
    return sb.toString();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
