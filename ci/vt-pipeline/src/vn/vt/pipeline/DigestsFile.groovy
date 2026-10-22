package vn.vt.pipeline

import com.cloudbees.groovy.cps.NonCPS

/**
 * Cập nhật `deploy/environments/<môi trường>/digests.yaml` (INF-05, bước vtGitopsBump).
 *
 * Sửa theo dòng thay vì đọc rồi ghi lại YAML, để giữ nguyên chú thích đầu tệp và thứ tự khoá (diff trong
 * kho GitOps chỉ gồm đúng các dòng đổi). Cấu trúc được hỗ trợ là cấu trúc mà tệp mẫu khai báo:
 *
 * <pre>
 * images:
 *   &lt;service&gt;:
 *     repository: ...
 *     digest: ...
 *     commit: ...
 *     build: ...
 * </pre>
 */
class DigestsFile implements Serializable {

  static final List TRUONG = ['repository', 'digest', 'commit', 'build']

  /**
   * Trả nội dung mới; mục của service được thêm vào cuối khối `images:` nếu chưa có.
   * Ném lỗi khi digest không đúng dạng sha256 hoặc tệp không có khối `images:`.
   */
  @NonCPS
  static String capNhat(String noiDung, String service, Map giaTri) {
    if (!(service ==~ /[a-z][a-z0-9-]{1,62}/)) {
      throw new IllegalArgumentException("Tên service không hợp lệ: ${service}")
    }
    if (!(giaTri.digest ==~ /sha256:[0-9a-f]{64}/)) {
      throw new IllegalArgumentException("Digest không đúng dạng sha256: ${giaTri.digest}")
    }
    List<String> dong = noiDung.readLines()
    int images = dong.findIndexOf { it ==~ /images:\s*(#.*)?/ }
    if (images < 0) {
      throw new IllegalStateException('Tệp digest không có khối images:')
    }
    // Khối images kết thúc ở dòng đầu tiên không thụt lề (không tính dòng trống, chú thích)
    int hetKhoi = dong.size()
    for (int i = images + 1; i < dong.size(); i++) {
      String d = dong[i]
      if (d.trim() && !d.trim().startsWith('#') && !d.startsWith(' ')) {
        hetKhoi = i
        break
      }
    }
    int batDau = -1
    for (int i = images + 1; i < hetKhoi; i++) {
      if (dong[i] ==~ /  ${java.util.regex.Pattern.quote(service)}:\s*(#.*)?/) {
        batDau = i
        break
      }
    }
    List<String> moi = TRUONG.collect { "    ${it}: ${trichDan(giaTri[it] ?: '')}".toString() }
    if (batDau < 0) {
      List<String> ketQua = new ArrayList<String>(dong.subList(0, hetKhoi))
      while (ketQua && !ketQua[-1].trim()) {
        ketQua.remove(ketQua.size() - 1)
      }
      ketQua.add("  ${service}:".toString())
      ketQua.addAll(moi)
      ketQua.addAll(dong.subList(hetKhoi, dong.size()))
      return ketQua.join('\n') + '\n'
    }
    int ketThuc = batDau + 1
    while (ketThuc < hetKhoi && (dong[ketThuc].startsWith('    ') || !dong[ketThuc].trim())) {
      ketThuc++
    }
    // Giữ các khoá khác (nếu có) của mục, chỉ thay bốn trường quản lý bởi pipeline
    List<String> conLai = dong.subList(batDau + 1, ketThuc).findAll { d ->
      String t = d.trim()
      t && !TRUONG.any { t.startsWith("${it}:") }
    }
    List<String> ketQua = new ArrayList<String>(dong.subList(0, batDau + 1))
    ketQua.addAll(moi)
    ketQua.addAll(conLai)
    ketQua.addAll(dong.subList(ketThuc, dong.size()))
    return ketQua.join('\n') + '\n'
  }

  @NonCPS
  static String trichDan(Object v) {
    String s = v.toString()
    if (s.contains('"') || s.contains('\n')) {
      throw new IllegalArgumentException('Giá trị digest.yaml không được chứa dấu nháy kép hoặc xuống dòng')
    }
    return "\"${s}\"".toString()
  }
}
