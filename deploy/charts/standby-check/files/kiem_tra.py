"""Kiểm sẵn sàng của bản sao ấm (INF-28, OPS-18): hỏi Prometheus theo danh sách kiểm, in một dòng JSON cho mỗi kiểm và
một dòng tổng; mã thoát 1 khi có kiểm không đạt hoặc không có số liệu (thiếu số liệu là đỏ, không phải xanh).

Danh sách kiểm (JSON, biến KIEM_TRA_FILE): [{"ten": ..., "promql": ..., "so": "<"|"<="|">"|">="|"==", "nguong": số}].
Mỗi PromQL phải trả đúng một hoặc nhiều mẫu; mọi mẫu phải đạt điều kiện.
"""
import json
import os
import sys
import urllib.parse
import urllib.request

PHEP_SO = {
    "<": lambda a, b: a < b,
    "<=": lambda a, b: a <= b,
    ">": lambda a, b: a > b,
    ">=": lambda a, b: a >= b,
    "==": lambda a, b: a == b,
}


def hoi(url, promql, timeout=10):
    q = urllib.parse.urlencode({"query": promql})
    with urllib.request.urlopen(f"{url.rstrip('/')}/api/v1/query?{q}", timeout=timeout) as r:
        d = json.load(r)
    if d.get("status") != "success":
        raise RuntimeError(d.get("error", "Prometheus trả lỗi"))
    return [float(m["value"][1]) for m in d["data"]["result"]]


def kiem(url, ds):
    kq = []
    for k in ds:
        dong = {"ten": k["ten"], "dieuKien": f"{k['so']} {k['nguong']}"}
        try:
            gt = hoi(url, k["promql"])
            dong["giaTri"] = gt
            dong["dat"] = bool(gt) and all(PHEP_SO[k["so"]](v, float(k["nguong"])) for v in gt)
            if not gt:
                dong["lyDo"] = "không có số liệu"
        except Exception as e:  # mạng, Prometheus, biểu thức sai đều là đỏ
            dong["dat"], dong["lyDo"] = False, str(e)
        kq.append(dong)
    return kq


def main():
    url = os.environ["PROMETHEUS_URL"]
    ds = json.load(open(os.environ.get("KIEM_TRA_FILE", "/etc/kiem-tra/kiem-tra.json"), encoding="utf-8"))
    kq = kiem(url, ds)
    for d in kq:
        print(json.dumps(d, ensure_ascii=False))
    hong = [d["ten"] for d in kq if not d["dat"]]
    print(json.dumps({"tong": len(kq), "khongDat": hong, "sanSang": not hong, "cell": os.environ.get("CELL", "")},
                     ensure_ascii=False))
    return 1 if hong else 0


if __name__ == "__main__":
    sys.exit(main())
