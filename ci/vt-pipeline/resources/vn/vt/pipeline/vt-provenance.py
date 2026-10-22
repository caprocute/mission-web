#!/usr/bin/env python3
"""Sinh vị từ (predicate) SLSA Provenance v1 cho một ảnh (INF-05, SEC-37).

Đây là bản tham chiếu của công cụ `vt-provenance` mà TL-04 mục 9.6 gọi trong stage "Ký và chứng thực".
Ở cụm thật, công cụ nằm trong ảnh container `provenance` của pod dựng (`vt-build-ephemeral`), do đội
nền tảng dựng từ tệp này; mã của dự án không sửa được container đó, và khoá ký trong OpenBao chỉ cấp
quyền `sign` cho tài khoản dịch vụ của bước này (yêu cầu cốt lõi của SLSA Build mức 3).

Kết quả là phần `predicate` (không phải cả in-toto Statement): `cosign attest --type slsaprovenance1`
tự gói thành Statement với subject là digest của ảnh.

    vt-provenance --builder-id URL --repo URL --commit SHA --digest sha256:... \
        [--ref refs/heads/main] [--build-url URL] [--invocation-id ID] [--base-image REF@sha256:...] \
        [--build-type URI] [--started-on RFC3339] > provenance.json

Chỉ dùng thư viện chuẩn của Python 3.
"""
import argparse
import datetime as dt
import json
import re
import sys

BUILD_TYPE = "https://jenkins.vt.local/vt-pipeline/java/v1"  # (suy luận) định danh kiểu dựng của vt-pipeline


def bay_gio() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Sinh predicate SLSA Provenance v1")
    p.add_argument("--builder-id", required=True)
    p.add_argument("--repo", required=True, help="URL kho mã nguồn")
    p.add_argument("--commit", required=True, help="SHA commit nguồn (40 ký tự hex)")
    p.add_argument("--digest", required=True, help="digest ảnh sha256:...")
    p.add_argument("--ref", default="", help="nhánh hoặc thẻ đã dựng")
    p.add_argument("--build-url", default="")
    p.add_argument("--invocation-id", default="")
    p.add_argument("--base-image", default="", help="ảnh nền REF@sha256:...")
    p.add_argument("--build-type", default=BUILD_TYPE)
    p.add_argument("--started-on", default="")
    a = p.parse_args(argv)

    if not re.fullmatch(r"[0-9a-f]{40}", a.commit):
        p.error("--commit phải là SHA-1 đầy đủ 40 ký tự hex của commit git")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", a.digest):
        p.error("--digest phải có dạng sha256:<64 hex>")

    phu_thuoc = [{
        "uri": f"git+{a.repo}" + (f"@{a.ref}" if a.ref else ""),
        "digest": {"gitCommit": a.commit},
    }]
    if a.base_image:
        m = re.fullmatch(r"(.+)@sha256:([0-9a-f]{64})", a.base_image)
        if not m:
            p.error("--base-image phải ghim theo digest @sha256 (SEC-55)")
        phu_thuoc.append({"uri": f"oci://{m.group(1)}", "digest": {"sha256": m.group(2)}})

    predicate = {
        "buildDefinition": {
            "buildType": a.build_type,
            "externalParameters": {
                "repository": a.repo,
                "ref": a.ref,
                "commit": a.commit,
            },
            "internalParameters": {
                "image": {"digest": a.digest},
            },
            "resolvedDependencies": phu_thuoc,
        },
        "runDetails": {
            "builder": {"id": a.builder_id},
            "metadata": {
                "invocationId": a.invocation_id or a.build_url or "",
                "startedOn": a.started_on or bay_gio(),
                "finishedOn": bay_gio(),
            },
        },
    }
    if a.build_url:
        predicate["runDetails"]["byproducts"] = [{"name": "jenkins-build", "uri": a.build_url}]
    json.dump(predicate, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
