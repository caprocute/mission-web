package vn.viettel.okrkpi.docgen.infrastructure.render;

import jakarta.xml.bind.JAXBElement;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.docx4j.XmlUtils;
import org.docx4j.jaxb.Context;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.wml.BooleanDefaultTrue;
import org.docx4j.wml.CTLock;
import org.docx4j.wml.CTSdtCell;
import org.docx4j.wml.ContentAccessor;
import org.docx4j.wml.HpsMeasure;
import org.docx4j.wml.Jc;
import org.docx4j.wml.JcEnumeration;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.R;
import org.docx4j.wml.RPr;
import org.docx4j.wml.STLock;
import org.docx4j.wml.SdtBlock;
import org.docx4j.wml.SdtElement;
import org.docx4j.wml.SdtPr;
import org.docx4j.wml.SdtRun;
import org.docx4j.wml.Tag;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Tc;
import org.docx4j.wml.Text;
import org.docx4j.wml.Tr;
import org.docx4j.wml.TrPr;
import vn.viettel.okrkpi.docgen.application.render.MergeModel;

/**
 * Điền {@link MergeModel} vào content control của mẫu DOCX theo tag (DV-11-02, DV-11-03), trên thân
 * văn bản, đầu trang và chân trang.
 *
 * <ul>
 *   <li>Trường: thay nội dung chỗ giữ bằng chữ in, bỏ cờ {@code showingPlcHdr}, giữ định dạng chữ
 *       của chỗ giữ trừ in nghiêng; xuống dòng thành ngắt dòng (trong đoạn) hoặc đoạn mới (khối).
 *   <li>Bảng: content control bao một {@code w:tbl}; các dòng trước dòng mẫu (dòng đầu tiên có
 *       content control) là dòng tiêu đề, được đánh dấu lặp lại ở đầu mỗi trang ({@code
 *       tblHeader}); dòng mẫu được nhân cho từng dòng dữ liệu, không cho ngắt giữa dòng ({@code
 *       cantSplit}). Bảng không có dòng dữ liệu giữ một dòng trống.
 *   <li>Khoá: tag nằm trong {@link MergeModel#locked()} (bảng, hoặc {@code bang/cot}) được đặt
 *       {@code w:lock="sdtContentLocked"}: trình soạn thảo không cho sửa nội dung hay xoá vùng
 *       (BR-11-02).
 *   <li>Tag không có trong mô hình được giữ nguyên (mẫu có vùng mà bộ ánh xạ chưa điền).
 * </ul>
 */
final class ContentControlFiller {

  /** Cỡ chữ dấu bản nháp, nửa điểm (16 pt). */
  static final long WATERMARK_HALF_POINTS = 32;

  private final ObjectFactory factory = Context.getWmlObjectFactory();
  private final MergeModel model;
  private final Set<Object> done = Collections.newSetFromMap(new IdentityHashMap<>());

  ContentControlFiller(MergeModel model) {
    this.model = model;
  }

  void fill(WordprocessingMLPackage pkg) {
    walk(pkg.getMainDocumentPart().getContent());
    for (Part part : pkg.getParts().getParts().values()) {
      if (part != pkg.getMainDocumentPart() && part instanceof ContentAccessor accessor) {
        walk(accessor.getContent());
      }
    }
    if (model.watermark() != null && !model.watermark().isBlank()) {
      pkg.getMainDocumentPart().getContent().add(0, watermark(model.watermark()));
    }
  }

  private void walk(List<Object> content) {
    for (Object item : new ArrayList<>(content)) {
      Object node = XmlUtils.unwrap(item);
      if (node instanceof SdtElement sdt) {
        visit(sdt);
      } else if (node instanceof ContentAccessor accessor) {
        walk(accessor.getContent());
      }
    }
  }

  private void visit(SdtElement sdt) {
    if (done.contains(sdt)) {
      return;
    }
    String tag = tag(sdt);
    if (tag != null && model.tables().containsKey(tag)) {
      table(sdt, tag, model.tables().get(tag));
    } else if (tag != null && model.values().containsKey(tag)) {
      text(sdt, model.values().get(tag), model.locked().contains(tag));
    } else if (sdt.getSdtContent() != null) {
      walk(sdt.getSdtContent().getContent());
    }
  }

  private void table(SdtElement sdt, String tag, List<Map<String, String>> rows) {
    done.add(sdt);
    boolean lockTable = model.locked().contains(tag);
    if (lockTable) {
      lock(sdt.getSdtPr());
    }
    Tbl tbl = first(sdt.getSdtContent().getContent(), Tbl.class);
    if (tbl == null) {
      text(
          sdt,
          String.join("\n", rows.stream().map(r -> String.join(" · ", r.values())).toList()),
          lockTable);
      return;
    }
    List<Object> trs = tbl.getContent();
    int templateIndex = -1;
    for (int i = 0; i < trs.size(); i++) {
      if (XmlUtils.unwrap(trs.get(i)) instanceof Tr tr && !sdts(tr.getContent()).isEmpty()) {
        templateIndex = i;
        break;
      }
    }
    if (templateIndex < 0) {
      return;
    }
    for (int i = 0; i < templateIndex; i++) {
      if (XmlUtils.unwrap(trs.get(i)) instanceof Tr header) {
        rowFlag(header, true);
      }
    }
    Tr template = (Tr) XmlUtils.unwrap(trs.remove(templateIndex));
    List<Map<String, String>> data = rows.isEmpty() ? List.of(Map.of()) : rows;
    List<Tr> copies = new ArrayList<>(data.size());
    for (Map<String, String> row : data) {
      Tr copy = XmlUtils.deepCopy(template);
      rowFlag(copy, false);
      for (SdtElement cell : sdts(copy.getContent())) {
        String column = tag(cell);
        boolean locked = lockTable || model.locked().contains(tag + "/" + column);
        text(cell, column == null ? "" : row.getOrDefault(column, ""), locked);
      }
      copies.add(copy);
    }
    trs.addAll(templateIndex, copies);
  }

  /** Điền chữ vào một content control; giá trị rỗng để vùng trống thay vì chữ giữ chỗ. */
  private void text(SdtElement sdt, String value, boolean locked) {
    done.add(sdt);
    SdtPr pr = sdt.getSdtPr();
    if (pr != null) {
      pr.getRPrOrAliasOrLock()
          .removeIf(
              o ->
                  o instanceof JAXBElement<?> e
                      && "showingPlcHdr".equals(e.getName().getLocalPart()));
      if (locked) {
        lock(pr);
      }
    }
    List<Object> content = sdt.getSdtContent().getContent();
    RPr rpr = plainRunProperties(content);
    String[] lines = clean(value).split("\n", -1);
    if (sdt instanceof SdtRun) {
      content.clear();
      content.add(run(rpr, lines));
    } else if (sdt instanceof SdtBlock) {
      PPr ppr = paragraphProperties(content);
      content.clear();
      for (String line : lines) {
        content.add(paragraph(ppr, rpr, new String[] {line}));
      }
    } else if (sdt instanceof CTSdtCell) {
      Tc tc = first(content, Tc.class);
      if (tc != null) {
        PPr ppr = paragraphProperties(tc.getContent());
        tc.getContent().removeIf(o -> XmlUtils.unwrap(o) instanceof P);
        tc.getContent().add(paragraph(ppr, rpr, lines));
      }
    }
  }

  private void lock(SdtPr pr) {
    pr.getRPrOrAliasOrLock().removeIf(o -> XmlUtils.unwrap(o) instanceof CTLock);
    CTLock lock = factory.createCTLock();
    lock.setVal(STLock.SDT_CONTENT_LOCKED);
    pr.getRPrOrAliasOrLock().add(factory.createSdtPrLock(lock));
  }

  private void rowFlag(Tr tr, boolean header) {
    TrPr trPr = tr.getTrPr();
    if (trPr == null) {
      trPr = factory.createTrPr();
      tr.setTrPr(trPr);
    }
    String name = header ? "tblHeader" : "cantSplit";
    trPr.getCnfStyleOrDivIdOrGridBefore().removeIf(e -> name.equals(e.getName().getLocalPart()));
    BooleanDefaultTrue on = factory.createBooleanDefaultTrue();
    trPr.getCnfStyleOrDivIdOrGridBefore()
        .add(
            header ? factory.createCTTrPrBaseTblHeader(on) : factory.createCTTrPrBaseCantSplit(on));
  }

  private R run(RPr rpr, String[] lines) {
    R run = factory.createR();
    if (rpr != null) {
      run.setRPr(XmlUtils.deepCopy(rpr));
    }
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        run.getContent().add(factory.createBr());
      }
      Text t = factory.createText();
      t.setValue(lines[i]);
      t.setSpace("preserve");
      run.getContent().add(factory.createRT(t));
    }
    return run;
  }

  private P paragraph(PPr ppr, RPr rpr, String[] lines) {
    P p = factory.createP();
    if (ppr != null) {
      p.setPPr(XmlUtils.deepCopy(ppr));
    }
    p.getContent().add(run(rpr, lines));
    return p;
  }

  private P watermark(String mark) {
    P p = factory.createP();
    PPr ppr = factory.createPPr();
    Jc jc = factory.createJc();
    jc.setVal(JcEnumeration.CENTER);
    ppr.setJc(jc);
    p.setPPr(ppr);
    RPr rpr = factory.createRPr();
    rpr.setB(factory.createBooleanDefaultTrue());
    HpsMeasure size = factory.createHpsMeasure();
    size.setVal(BigInteger.valueOf(WATERMARK_HALF_POINTS));
    rpr.setSz(size);
    p.getContent().add(run(rpr, new String[] {mark}));
    return p;
  }

  /** Định dạng chữ của chỗ giữ, bỏ in nghiêng (chữ giữ chỗ in nghiêng, giá trị thật in thường). */
  private static RPr plainRunProperties(List<Object> content) {
    R first = first(content, R.class);
    if (first == null || first.getRPr() == null) {
      return null;
    }
    RPr copy = XmlUtils.deepCopy(first.getRPr());
    copy.setI(null);
    copy.setICs(null);
    return copy;
  }

  private static PPr paragraphProperties(List<Object> content) {
    P first = first(content, P.class);
    return first == null ? null : first.getPPr();
  }

  /**
   * Phần tử đầu tiên thuộc kiểu {@code type} theo thứ tự tài liệu, không đi vào content control.
   */
  static <T> T first(List<Object> content, Class<T> type) {
    for (Object item : content) {
      Object node = XmlUtils.unwrap(item);
      if (type.isInstance(node)) {
        return type.cast(node);
      }
      if (!(node instanceof SdtElement) && node instanceof ContentAccessor accessor) {
        T inner = first(accessor.getContent(), type);
        if (inner != null) {
          return inner;
        }
      }
    }
    return null;
  }

  /** Các content control trong một nhánh (không tính content control lồng bên trong chúng). */
  static List<SdtElement> sdts(List<Object> content) {
    List<SdtElement> out = new ArrayList<>();
    for (Object item : content) {
      Object node = XmlUtils.unwrap(item);
      if (node instanceof SdtElement sdt) {
        out.add(sdt);
      } else if (node instanceof ContentAccessor accessor) {
        out.addAll(sdts(accessor.getContent()));
      }
    }
    return out;
  }

  static String tag(SdtElement sdt) {
    SdtPr pr = sdt.getSdtPr();
    Tag tag = pr == null ? null : pr.getTag();
    return tag == null ? null : tag.getVal();
  }

  /** Bỏ ký tự điều khiển không hợp lệ trong XML, giữ xuống dòng và tab. */
  static String clean(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\r') {
        continue;
      }
      if (c >= 0x20 || c == '\n' || c == '\t') {
        out.append(c);
      }
    }
    return out.toString();
  }
}
