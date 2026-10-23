package vn.viettel.okrkpi.integ.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/** Translator theo mã kết nối, dùng cho cả luồng nhận và phát lại thư chết. */
public class Translators {

  private final Map<String, Translator> byCode = new HashMap<>();

  public Translators(Collection<InboundConnector> connectors, Map<String, Translator> extra) {
    for (InboundConnector c : connectors) {
      register(c.code(), c.translator());
    }
    extra.forEach(this::register);
  }

  private void register(String code, Translator translator) {
    if (byCode.putIfAbsent(code, translator) != null) {
      throw new IllegalStateException("Trùng translator cho kết nối " + code);
    }
  }

  public Translator require(String connectorCode) {
    Translator t = byCode.get(connectorCode);
    if (t == null) {
      throw new NoSuchElementException("Chưa có translator cho kết nối " + connectorCode);
    }
    return t;
  }
}
