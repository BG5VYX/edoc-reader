"""从 ICAO/BSI 格式的 CSCA Master List (.ml, CMS SignedData) 中提取全部 CSCA 证书。

主列表来源（公开、无授权限制）：
  - 德国 BSI  German Master List
  - 荷兰 NPKD Netherlands Master List

内层结构：SEQUENCE { INTEGER version, SET OF SEQUENCE(certificate) }
"""
import os
import subprocess
import sys


def read_tlv(buf, pos):
    """读取一个 TLV，返回 (tag, content_start, content_length)。"""
    tag = buf[pos]
    pos += 1
    first = buf[pos]
    pos += 1
    if first & 0x80:
        n = first & 0x7F
        length = int.from_bytes(buf[pos:pos + n], "big")
        pos += n
    else:
        length = first
    return tag, pos, length


def extract_certs(der):
    """从主列表内层 DER 中提取所有证书的完整 TLV 字节。"""
    tag, p, l = read_tlv(der, 0)
    assert tag == 0x30, "外层应为 SEQUENCE，实际 0x%02X" % tag
    outer_end = p + l

    # INTEGER version
    tag, p, ln = read_tlv(der, p)
    assert tag == 0x02, "第二项应为 INTEGER，实际 0x%02X" % tag
    p += ln

    # SET OF certificates
    tag, p, ln = read_tlv(der, p)
    assert tag == 0x31, "第三项应为 SET，实际 0x%02X" % tag
    set_end = p + ln

    certs = []
    while p < set_end:
        tag, cp, cl = read_tlv(der, p)
        if tag != 0x30:
            p = cp + cl
            continue
        certs.append(der[p:cp + cl])
        p = cp + cl
    assert p == set_end, "解析未对齐：%d != %d" % (p, set_end)
    assert outer_end >= set_end
    return certs


def unwrap_cms(ml_path, tmp_der):
    """用 openssl 解出 CMS 的内层内容。"""
    r = subprocess.run(
        ["openssl", "cms", "-verify", "-inform", "DER", "-in", ml_path,
         "-noverify", "-out", tmp_der],
        capture_output=True, text=True,
    )
    if "Verification successful" not in (r.stdout + r.stderr):
        raise RuntimeError("CMS 解包失败: %s%s" % (r.stdout, r.stderr))
    return open(tmp_der, "rb").read()


def main():
    src = sys.argv[1]
    out = sys.argv[2]
    os.makedirs(out, exist_ok=True)
    tmp = os.path.join(out, "_tmp_content.der")

    seen = {}
    total = 0
    for fn in sorted(os.listdir(src)):
        if not fn.endswith(".ml"):
            continue
        path = os.path.join(src, fn)
        try:
            der = unwrap_cms(path, tmp)
            certs = extract_certs(der)
        except Exception as e:
            print("  跳过 %s：%s" % (fn, e))
            continue
        new = 0
        for c in certs:
            if c not in seen:
                seen[c] = fn
                new += 1
        total += len(certs)
        print("  %-34s 提取 %4d 张，新增 %4d 张" % (fn, len(certs), new))

    if os.path.exists(tmp):
        os.remove(tmp)

    # 去重后落盘
    for i, (c, srcfile) in enumerate(sorted(seen.items()), 1):
        with open(os.path.join(out, "csca_%04d.cer" % i), "wb") as f:
            f.write(c)

    print("\n合计提取 %d 张，去重后 %d 张唯一证书" % (total, len(seen)))
    print("输出目录：%s" % out)


if __name__ == "__main__":
    main()
