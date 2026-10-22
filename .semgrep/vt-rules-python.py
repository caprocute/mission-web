import hashlib
import pickle
import ssl

import requests
import yaml


def sql(cur, ten):
    # ruleid: vt-py-sql-string-format
    cur.execute(f"SELECT * FROM t WHERE ten = '{ten}'")
    # ruleid: vt-py-sql-string-format
    cur.execute("SELECT * FROM t WHERE ten = '%s'" % ten)
    # ok: vt-py-sql-string-format
    cur.execute("SELECT * FROM t WHERE ten = %s", (ten,))


def dong(s, b, f):
    # ruleid: vt-py-dynamic-code
    eval(s)
    # ruleid: vt-py-unsafe-deserialize
    pickle.loads(b)
    # ruleid: vt-py-unsafe-deserialize
    yaml.load(f)
    # ok: vt-py-unsafe-deserialize
    yaml.load(f, Loader=yaml.SafeLoader)


def mang(url, b):
    # ruleid: vt-py-tls-verify-off
    requests.get(url, verify=False)
    # ruleid: vt-py-tls-verify-off
    ssl._create_unverified_context()
    # ruleid: vt-py-weak-hash
    hashlib.md5(b)
    # ok: vt-py-weak-hash
    hashlib.md5(b, usedforsecurity=False)
    # ok: vt-py-weak-hash
    hashlib.sha256(b)
