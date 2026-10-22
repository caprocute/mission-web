// vtGitopsBump: cập nhật digest ảnh vào kho GitOps bằng một commit có chữ ký GPG (INF-05).
//
// Truy vết: TL-04 mục 9.6 (Jenkins mở commit cập nhật digest trong kho GitOps; Argo CD chỉ đồng bộ commit
// có chữ ký, `AppProject.spec.signatureKeys`), OPS-01, SEC-37, SEC-55.
//
//   vtGitopsBump(service: 'mission-core', digest: 'sha256:...', env: 'staging',
//                image: 'harbor.vt.local/okr/mission-core', commit: '<sha nguồn>',
//                repoUrl: 'https://git.vt.local/okr/mission.git', branch: 'main',
//                credentialsId: 'gitops-push', signingKeyCredentialsId: 'gitops-signing-gpg')
//
// Tệp đích: deploy/environments/<env>/digests.yaml (cấu trúc ở chính tệp đó). Không có thay đổi thì
// không commit. Đẩy bị từ chối vì nhánh đã tiến thì rebase và thử lại tối đa 3 lần; rebase ký lại commit.
// Thông điệp commit có dấu `[vt-gitops]` để job dựng bỏ qua commit của chính bot (README mục "Vòng lặp").

import vn.vt.pipeline.DigestsFile

def call(Map a) {
  ['service', 'digest', 'repoUrl'].each { k ->
    if (!a[k]) {
      error("vtGitopsBump: thiếu tham số '${k}'")
    }
  }
  String moiTruong = a.env ?: 'staging'
  if (!(moiTruong ==~ /[a-z0-9-]+/)) {
    error("vtGitopsBump: môi trường không hợp lệ '${moiTruong}'")
  }
  String nhanh = a.branch ?: 'main'
  String tep = "deploy/environments/${moiTruong}/digests.yaml"
  String thuMuc = '.vt-gitops'
  String tenTacGia = a.authorName ?: 'vt-pipeline'
  String thuTacGia = a.authorEmail ?: 'vt-pipeline@jenkins.vt.local'

  Closure lam = {
    dir(thuMuc) {
      deleteDir()
      withEnv(["VT_GITOPS_REPO=${a.repoUrl}", "VT_GITOPS_BRANCH=${nhanh}"]) {
        sh 'git clone --quiet --branch "$VT_GITOPS_BRANCH" --depth 50 "$VT_GITOPS_REPO" .'
      }
      if (!fileExists(tep)) {
        error("vtGitopsBump: kho GitOps chưa có ${tep}")
      }
      String cu = readFile(tep)
      String moi = DigestsFile.capNhat(cu, a.service, [
        repository: a.image ?: '',
        digest    : a.digest,
        commit    : a.commit ?: '',
        build     : env.BUILD_URL ?: '',
      ])
      if (moi == cu) {
        echo "vtGitopsBump: ${tep} đã có digest ${a.digest}, không commit."
        return
      }
      writeFile file: tep, text: moi
      String thongDiep = "${moiTruong}: cập nhật digest ${a.service} [vt-gitops]\n\n" +
          "Ảnh: ${a.image ?: a.service}@${a.digest}\nNguồn: ${a.commit ?: 'không rõ'}\nBản dựng: ${env.BUILD_URL ?: 'không rõ'}\n"
      writeFile file: '../.vt-gitops-msg', text: thongDiep
      withEnv(["VT_TEP=${tep}", "VT_NHANH=${nhanh}", "GIT_AUTHOR_NAME=${tenTacGia}", "GIT_AUTHOR_EMAIL=${thuTacGia}",
               "GIT_COMMITTER_NAME=${tenTacGia}", "GIT_COMMITTER_EMAIL=${thuTacGia}"]) {
        kyVaDay(a)
      }
    }
  }

  if (a.credentialsId) {
    withCredentials([gitUsernamePassword(credentialsId: a.credentialsId, gitToolName: 'Default')]) { lam() }
  } else {
    lam()
  }
}

/** Nạp khoá GPG vào GNUPGHOME tạm, commit có chữ ký, tự kiểm chữ ký, đẩy (thử lại khi nhánh đã tiến). */
def kyVaDay(Map a) {
  Closure kyDay = {
    sh '''set -eu
      export GNUPGHOME="$(mktemp -d)"
      trap 'gpgconf --kill gpg-agent >/dev/null 2>&1 || true; rm -rf "$GNUPGHOME"' EXIT
      if [ -n "${VT_GPG_KEY_FILE:-}" ]; then
        gpg --batch --quiet --import "$VT_GPG_KEY_FILE"
      fi
      khoa="$(gpg --batch --list-secret-keys --with-colons | awk -F: '/^sec/ {print $5; exit}')"
      if [ -z "$khoa" ]; then
        echo "vtGitopsBump: không có khoá GPG để ký commit; Argo CD chỉ đồng bộ commit có chữ ký." >&2
        exit 1
      fi
      git add "$VT_TEP"
      git -c gpg.format=openpgp -c user.signingkey="$khoa" -c commit.gpgsign=true commit --quiet -S -F ../.vt-gitops-msg
      git -c gpg.format=openpgp verify-commit HEAD
      for lan in 1 2 3; do
        if git push --quiet origin "HEAD:$VT_NHANH"; then
          git log -1 --format='vtGitopsBump: đã đẩy %H (chữ ký %G?, khoá %GK)'
          exit 0
        fi
        echo "vtGitopsBump: đẩy bị từ chối, rebase lên origin/$VT_NHANH và thử lại ($lan/3)" >&2
        git -c gpg.format=openpgp -c user.signingkey="$khoa" -c commit.gpgsign=true pull --quiet --rebase origin "$VT_NHANH"
        git -c gpg.format=openpgp verify-commit HEAD
      done
      echo "vtGitopsBump: không đẩy được sau 3 lần" >&2
      exit 1'''
  }
  if (a.signingKeyCredentialsId) {
    withCredentials([file(credentialsId: a.signingKeyCredentialsId, variable: 'VT_GPG_KEY_FILE')]) { kyDay() }
  } else {
    kyDay()
  }
}
