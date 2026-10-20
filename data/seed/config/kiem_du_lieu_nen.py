import csv, sys, datetime, glob, os, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from amlich import lunar_to_solar
root = sys.argv[1]; err = []
seen = {}; n = 0
for p in sorted(glob.glob(f"{root}/config/holiday_calendar_*.csv")):
    y = int(re.search(r"(\d{4})", os.path.basename(p)).group(1))
    with open(p, encoding="utf-8", newline="") as f:
        r = csv.DictReader(f)
        assert r.fieldnames == ["calendar_date","day_kind","name","scope","doi_chieu","nguon","nguon_url","ghi_chu"], r.fieldnames
        for i, row in enumerate(r, 2):
            n += 1; loc = f"{os.path.basename(p)}:{i}"
            try: d = datetime.date.fromisoformat(row["calendar_date"])
            except ValueError: err.append(f"{loc} ngày sai"); continue
            if d.year != y: err.append(f"{loc} sai năm tệp")
            if d in seen: err.append(f"{loc} trùng ngày với {seen[d]}")
            seen[d] = loc
            if row["day_kind"] not in ("NGHI_LE","NGHI_BU","LAM_BU"): err.append(f"{loc} day_kind")
            if row["scope"] != "QUOC_GIA": err.append(f"{loc} scope")
            if row["doi_chieu"] not in ("CO_DINH","DA_DOI_CHIEU","CHO_DOI_CHIEU"): err.append(f"{loc} doi_chieu")
            if not row["name"].strip() or len(row["name"]) > 100: err.append(f"{loc} name")
            if not row["nguon"].strip() or not row["nguon_url"].startswith("https://"): err.append(f"{loc} thiếu nguồn")
            wd = d.isoweekday()
            if row["day_kind"] == "LAM_BU" and wd < 6: err.append(f"{loc} LAM_BU không phải cuối tuần")
            if row["day_kind"] == "NGHI_BU" and wd > 5: err.append(f"{loc} NGHI_BU rơi cuối tuần")
            nm = row["name"]
            if "Giỗ Tổ Hùng Vương (10/3" in nm and d != lunar_to_solar(10,3,y): err.append(f"{loc} Giỗ Tổ lệch âm lịch")
            m = re.search(r"Mùng (\d)", nm)
            if m and d != lunar_to_solar(int(m.group(1)),1,y): err.append(f"{loc} Tết lệch âm lịch")
            m = re.search(r"(\d+) tháng Chạp", nm)
            if m and d != lunar_to_solar(int(m.group(1)),12,y-1): err.append(f"{loc} Chạp lệch âm lịch")
# kiểm số ngày lễ theo luật: 11 ngày NGHI_LE/năm (BLLĐ 2019: 1+5+1+1+2+1) + 24/11
with open(f"{root}/org/subject_group.csv", encoding="utf-8", newline="") as f:
    rows = list(csv.DictReader(f))
codes = [r["code"] for r in rows]
if codes != [f"K{i}" for i in range(1,10)]: err.append(f"subject_group mã {codes}")
for r in rows:
    if r["flow"] and r["flow"] not in ("DANG","NHA_NUOC","CA_HAI"): err.append(f"{r['code']} flow")
    if not r["name"] and "chờ PL 2A" not in r["ghi_chu"]: err.append(f"{r['code']} thiếu ghi chú chờ")
    if not r["nguon"]: err.append(f"{r['code']} thiếu nguồn")
with open(f"{root}/org/job_title.csv", encoding="utf-8", newline="") as f:
    r = csv.DictReader(f); jt = list(r)
    assert r.fieldnames == ["code","name","level","is_central_managed","subject_group_code","valid_from","valid_to","nguon","ghi_chu"]
from collections import Counter
print("lịch:", n, "dòng;", dict(Counter((d.year) for d in seen)))
print("khối:", len(rows), "; chức danh:", len(jt))
print("LỖI:" if err else "OK", *err, sep="\n")
sys.exit(1 if err else 0)
