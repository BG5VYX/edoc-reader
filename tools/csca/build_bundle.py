"""把提取出的 CSCA 证书打包成 Android 应用可直接加载的紧凑二进制信任库。

输出格式（csca_bundle.bin）：
    magic   : "CSCA"            4 字节
    version : 1                 1 字节
    reserved: 0x00 0x00 0x00    3 字节
    count   : uint32 大端        4 字节
    之后重复 count 次：
        length : uint32 大端     4 字节
        der    : length 字节     X.509 DER

同时输出 csca_meta.json，记录来源、生成时间与证书数量。
"""
import glob
import hashlib
import json
import os
import struct
import sys
from datetime import datetime, timezone

from cryptography import x509


def main():
    src = sys.argv[1]
    out_bin = sys.argv[2]
    out_meta = sys.argv[3]

    certs = []
    seen = set()
    bad = 0
    for f in sorted(glob.glob(os.path.join(src, "csca_*.cer"))):
        der = open(f, "rb").read()
        h = hashlib.sha256(der).digest()
        if h in seen:
            continue
        seen.add(h)
        try:
            cert = x509.load_der_x509_certificate(der)
            # 访问公钥可筛掉「使用显式 EC 参数（自定义曲线）」的证书——
            # 这类证书 Android/JDK 的 CertificateFactory 无法解析。
            cert.public_key()
        except Exception:
            bad += 1
            continue
        certs.append(der)

    certs.sort()

    with open(out_bin, "wb") as f:
        f.write(b"CSCA")
        f.write(bytes([1, 0, 0, 0]))
        f.write(struct.pack(">I", len(certs)))
        for der in certs:
            f.write(struct.pack(">I", len(der)))
            f.write(der)

    meta = {
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "count": len(certs),
        "skipped_invalid": bad,
        "size_bytes": os.path.getsize(out_bin),
        "sources": [
            {
                "name": "German Master List (BSI)",
                "url": "https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/ElekAusweise/CSCA/GermanMasterList.html",
                "file": "DE_ML_2026-08-19-10-28-54.ml",
            },
            {
                "name": "Netherlands Master List (NPKD)",
                "url": "https://www.npkd.nl/masterlist.html",
                "file": "NL_ML_2026-07-22.ml",
            },
        ],
        "note": (
            "证书为各国政府公开发布的 CSCA 公钥证书，不含任何私钥。"
            "输入已先用 JDK 的 CertificateFactory 预筛选，"
            "剔除了使用显式 EC 参数（自定义曲线）而无法被 Android/JDK 解析的证书。"
        ),
    }
    with open(out_meta, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)

    print("打包完成：%d 张唯一证书，%d 张解析失败已跳过" % (len(certs), bad))
    print("输出：%s  (%.1f KB)" % (out_bin, os.path.getsize(out_bin) / 1024.0))
    print("元数据：%s" % out_meta)


if __name__ == "__main__":
    main()
