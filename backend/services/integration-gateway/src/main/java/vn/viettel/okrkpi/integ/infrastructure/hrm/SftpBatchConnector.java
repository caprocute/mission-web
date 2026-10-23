package vn.viettel.okrkpi.integ.infrastructure.hrm;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.integ.infrastructure.hrm.HrmProperties.Batch;

/**
 * Bộ kết nối tệp lô SFTP (DV-13-08, TL-01 "Các bộ kết nối": tệp lô SFTP cho HRM, ERP, HIS): theo
 * lịch, liệt kê thư mục {@code sftp://tài-khoản@máy:cổng/thư-mục} của kết nối, lấy tệp {@code
 * .csv}/{@code .xlsx} mới hơn con trỏ stream {@code hrm-files} (thời điểm sửa và tên tệp), mỗi tệp
 * là một thông điệp lưu nguyên văn. Mã thông điệp là băm nội dung nên tệp gửi lại (kể cả đổi tên)
 * không xử lý hai lần. Mật khẩu lấy theo {@code secret_path}; khoá máy chủ kiểm theo tệp {@code
 * known_hosts} của hồ sơ. Dùng JSch, thư viện SFTP của camel-ftp, vì địa chỉ và bí mật đổi theo
 * tenant trong {@code integ.connector} nên không khai báo được thành endpoint Camel tĩnh.
 */
class SftpBatchConnector implements InboundConnector {

  static final String STREAM = "hrm-files";
  static final String INITIAL = "000000000000|";

  private final String code;
  private final ConnectorSecrets secrets;
  private final Batch batch;
  private final Duration period;
  private final Duration timeout;
  private final Translator translator;

  SftpBatchConnector(
      String code,
      ConnectorSecrets secrets,
      Batch batch,
      Duration period,
      Duration timeout,
      Translator translator) {
    this.code = Objects.requireNonNull(code, "code");
    this.secrets = Objects.requireNonNull(secrets, "secrets");
    this.batch = Objects.requireNonNull(batch, "batch");
    this.period = Objects.requireNonNull(period, "period");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.translator = Objects.requireNonNull(translator, "translator");
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public String stream() {
    return STREAM;
  }

  @Override
  public String initialCursor() {
    return INITIAL;
  }

  @Override
  public Duration period() {
    return period;
  }

  @Override
  public int maxConcurrentCalls() {
    return 2;
  }

  @Override
  public Translator translator() {
    return translator;
  }

  /** Khoá con trỏ của một tệp: thời điểm sửa (giây, đủ 12 chữ số) và tên tệp. */
  static String key(long mtime, String name) {
    return String.format(Locale.ROOT, "%012d|%s", mtime, name);
  }

  private record Remote(String name, long mtime, long size) {
    String key() {
      return SftpBatchConnector.key(mtime, name);
    }
  }

  @Override
  public Page fetch(Connector connector, String cursor) throws IOException {
    URI uri = uri(connector);
    Session session = null;
    ChannelSftp sftp = null;
    try {
      session = session(connector, uri);
      sftp = (ChannelSftp) session.openChannel("sftp");
      sftp.connect((int) timeout.toMillis());
      String dir = uri.getPath() == null || uri.getPath().isEmpty() ? "." : uri.getPath();
      List<Remote> files = new ArrayList<>();
      long settled = Instant.now().minus(batch.minAge()).getEpochSecond();
      for (ChannelSftp.LsEntry e : sftp.ls(dir)) {
        String name = e.getFilename();
        String lower = name.toLowerCase(Locale.ROOT);
        if (e.getAttrs().isReg() && (lower.endsWith(".csv") || lower.endsWith(".xlsx"))) {
          Remote r =
              new Remote(name, e.getAttrs().getMTime() & 0xffffffffL, e.getAttrs().getSize());
          if (r.key().compareTo(cursor) > 0 && r.mtime() <= settled) {
            files.add(r);
          }
        }
      }
      files.sort(Comparator.comparing(Remote::key));
      if (files.size() > batch.maxFiles()) {
        files = files.subList(0, batch.maxFiles());
      }
      List<InboundMessage> items = new ArrayList<>();
      for (Remote r : files) {
        items.add(message(sftp, dir, r));
      }
      if (files.isEmpty()) {
        return new Page(List.of(), cursor, null);
      }
      Remote last = files.get(files.size() - 1);
      return new Page(items, last.key(), Instant.ofEpochSecond(last.mtime()));
    } catch (JSchException | SftpException e) {
      throw new IOException(
          "Kết nối SFTP " + connector.code() + " lỗi: " + e.getClass().getSimpleName(), e);
    } finally {
      if (sftp != null) {
        sftp.disconnect();
      }
      if (session != null) {
        session.disconnect();
      }
    }
  }

  private InboundMessage message(ChannelSftp sftp, String dir, Remote r)
      throws SftpException, IOException {
    if (r.size() > batch.maxBytes()) {
      byte[] name = r.name().getBytes(StandardCharsets.UTF_8);
      return new InboundMessage(
          "qua-co:" + Digests.sha256((r.key() + "|" + r.size()).getBytes(StandardCharsets.UTF_8)),
          BatchFiles.TYPE_OVERSIZE,
          name);
    }
    ByteArrayOutputStream buf = new ByteArrayOutputStream((int) Math.max(r.size(), 16));
    try (InputStream in = sftp.get(dir + "/" + r.name())) {
      byte[] chunk = new byte[64 * 1024];
      int n;
      while ((n = in.read(chunk)) > 0) {
        buf.write(chunk, 0, n);
        if (buf.size() > batch.maxBytes()) {
          throw new IOException("Tệp " + r.name() + " lớn hơn dung lượng cho phép");
        }
      }
    }
    byte[] data = buf.toByteArray();
    String type =
        r.name().toLowerCase(Locale.ROOT).endsWith(".xlsx")
            ? BatchFiles.TYPE_XLSX
            : BatchFiles.TYPE_CSV;
    return new InboundMessage("tep:" + Digests.sha256(data), type, data);
  }

  private Session session(Connector connector, URI uri) throws JSchException, IOException {
    JSch jsch = new JSch();
    if (batch.knownHosts() != null && !batch.knownHosts().isBlank()) {
      jsch.setKnownHosts(batch.knownHosts());
    }
    String user = uri.getUserInfo();
    if (user == null || user.isBlank()) {
      throw new IOException("Địa chỉ SFTP của " + connector.code() + " thiếu tài khoản");
    }
    Session session = jsch.getSession(user, uri.getHost(), uri.getPort() < 0 ? 22 : uri.getPort());
    if (connector.secretPath() != null) {
      secrets.token(connector.secretPath()).ifPresent(session::setPassword);
    }
    session.setConfig("StrictHostKeyChecking", batch.strictHostKey() ? "yes" : "no");
    session.setConfig("PreferredAuthentications", "publickey,password");
    session.setTimeout((int) timeout.toMillis());
    session.connect((int) timeout.toMillis());
    return session;
  }

  private static URI uri(Connector connector) throws IOException {
    try {
      URI uri = new URI(connector.endpoint());
      if (!"sftp".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
        throw new IOException("Địa chỉ SFTP của " + connector.code() + " không hợp lệ");
      }
      return uri;
    } catch (URISyntaxException | NullPointerException e) {
      throw new IOException("Địa chỉ SFTP của " + connector.code() + " không hợp lệ", e);
    }
  }
}
