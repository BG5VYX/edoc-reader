# 证件识读（eDoc Reader）

一个**完全离线、无需任何授权文件**的 Android 电子证件读取应用：
先用摄像头 OCR 识别证件机读区（MRZ），再通过 NFC 读取护照 / 往来港澳通行证 / 往来台湾通行证
的芯片数据，按 ICAO Doc 9303 解析并做被动认证，结果保存在本机并支持批量导出。

> 本项目的起点是对国家移民管理局《出入境证件电子信息识读认证 SDK》（Android / Windows 版）
> 的逆向分析。完整的原理与方案说明见
> [`docs/出入境证件识读技术报告.pdf`](docs/出入境证件识读技术报告.pdf)（26 页，含协议握手时序图），
> Markdown 版分析见 [`docs/SDK-分析报告.md`](docs/SDK-分析报告.md)。

---

## 它和官方 SDK 的关系

官方 SDK 的读证能力依赖**授权文件 + 设备指纹 + 服务端校验**，没有授权文件无法工作。
但读证流程本身完全由公开国际标准（ICAO Doc 9303）定义，因此本项目**从零实现**了同一套流程：

| 环节 | 官方 SDK | 本项目 |
|---|---|---|
| MRZ 识别 | Tesseract + 护照专用训练集 / 自研 OCR 引擎 | Google ML Kit 文本识别（模型随 APK 打包，**离线**）+ 自研 MRZ 行组提取与校验位纠错 |
| 芯片通道 | 手机 NFC / PC-SC | Android `IsoDep` + NFC Reader Mode |
| 认证 | BAC（原生实现） | **自研 BAC**，已用 ICAO 9303-11 附录 D 官方向量逐字节验证 |
| 传输 | 3DES 安全报文 | **自研安全报文**（DO'87'/'85'/'97'/'99'/'8E'） |
| 解析 | 原生实现 | **自研** BER-TLV / DG1 / DG2 / DG11 / DG14 / DG15 解析 |
| 验证 | OpenSSL + CSCA 库 + 服务端 | **自研** DER/CMS 解析 + Android JCE 验签 |
| 信任链 | 内置中国 CSCA 库 | **内置 513 张 CSCA 证书，覆盖 103 个国家/地区**（德国 BSI + 荷兰 NPKD 公开主列表） |
| 联网 | 必须 | **完全不需要** |
| 授权文件 | 必须 | **不需要** |

代价：无法给出官方 SDK 那种"服务端查验结论"（例如实时吊销状态、服务端黑名单比对）。
但完整信任链判定**已经实现**：内置 CSCA 主列表，可独立给出「签发国可信」的结论。

---

## 功能

- **OCR 识别 MRZ**：CameraX 实时预览 + ML Kit Latin 文本识别，自动提取 TD1 / TD2 / TD3 行组，
  计算并校验各校验位（含复合校验位），对 OCR 常见的字母/数字混淆做**有限度自动纠错**；
  识别失败时可手动输入。
- **NFC 读取芯片**：BAC 双向认证 → 3DES 安全报文 → 依次读取 EF.COM、DG1、DG2、DG11、DG14、
  DG15、EF.SOD。
- **被动认证**：本地重算各数据组摘要并与 EF.SOD 中的 LDS Security Object 比对；解析 CMS
  SignedData 并用内嵌的文档签名证书（DSC）验证签名。
- **CSCA 信任链验证**：内置 513 张 CSCA 公钥证书（覆盖 103 个国家/地区，含中国护照、
  中国香港、中国澳门及中国台湾地区），把文档签名证书沿签发关系逐级上溯到签发国根证书，
  给出「签发国可信」的完整结论——这是与官方 SDK 能力对齐的关键一环。
- **本地保存**：结构化记录 + 芯片人脸图像，存于应用私有目录（卸载即清除，且已排除系统备份）。
- **批量导出**：JSON / CSV / ZIP（含人脸图像），通过系统文件选择器保存，无需存储权限。

---

## 目录结构

```
EdocReader/
├── app/src/main/java/com/edocreader/app/
│   ├── App.kt                        应用级单例容器
│   ├── crypto/DesCrypto.kt           DES/3DES、Retail MAC、ISO 9797-1 填充、ICAO KDF
│   ├── mrz/
│   │   ├── MrzInfo.kt                MRZ 数据模型与日期换算
│   │   └── MrzParser.kt              行组提取、字段切分、校验位计算与纠错
│   ├── nfc/
│   │   ├── Apdu.kt                   APDU 编解码 + IsoDep 通道
│   │   ├── Tlv.kt                    BER-TLV 解析
│   │   ├── BacProtocol.kt            BAC 双向认证
│   │   ├── SecureMessaging.kt        3DES 安全报文封装/解封
│   │   ├── EmrtdFileSystem.kt        文件选择与分块读取
│   │   ├── PassportReader.kt         全流程编排
│   │   ├── dg/DgParsers.kt           DG1/DG2/DG11/DG14/DG15/EF.COM 解析
│   │   └── pa/
│   │       ├── Der.kt                最小 DER 解析器
│   │       └── PassiveAuth.kt        被动认证（摘要比对 + CMS 验签）
│   ├── ocr/MrzOcrAnalyzer.kt         ML Kit 实时/单帧识别
│   ├── data/
│   │   ├── DocRecord.kt              记录模型
│   │   ├── RecordRepository.kt       JSON 文件存储
│   │   └── ExportManager.kt          JSON / CSV / ZIP 导出
│   └── ui/                           MainActivity / ScanActivity / NfcReadActivity / DetailActivity
├── app/src/main/assets/csca/
│   ├── csca_bundle.bin               CSCA 信任库（513 张证书，825 KB）
│   └── csca_meta.json                信任库来源与生成信息
├── docs/
│   ├── 出入境证件识读技术报告.pdf      完整技术报告（26 页，图文并茂）
│   ├── report-source.html            报告的可编辑源文件
│   └── SDK-分析报告.md               官方 SDK 逆向分析报告（Markdown 版）
└── apk/                              已编译的 APK
```

---

## 编译

环境要求：JDK 17+、Android SDK（compileSdk 34、build-tools 34.0.0）。

```bash
# 1) 指定 SDK 路径
echo 'sdk.dir=/path/to/Android/sdk' > local.properties

# 2) 编译
./gradlew assembleDebug     # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # 产物：app/build/outputs/apk/release/app-release.apk
```

也可以直接用 Android Studio 打开工程目录。

### 依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| CameraX | 1.3.4 | 相机预览与帧分析 |
| ML Kit text-recognition | 16.0.1 | MRZ 文本识别（Latin，模型内置于 APK） |
| Material Components | 1.12.0 | Material 3 UI |
| Gson | 2.11.0 | 本地存储与导出的 JSON 序列化 |

密码学部分**不依赖任何第三方库**，只用 JDK / Android 自带的 JCE（DES、DESede、SHA-1、Signature、CertificateFactory）。

---

## 使用

1. 安装 APK，打开应用。
2. 点右下角「读取新证件」。
3. 把证件资料页下方的 MRZ 放进取景框（护照 2 行、通行证卡 3 行），等待识别成功。
   - 识别不出来可以点「手动输入 MRZ」。
4. 自动进入读卡页，把手机 NFC 感应区贴住证件芯片位置，保持不动直到提示"读取成功"。
5. 在详情页查看证件信息、人脸图像、DG11 附加资料、被动认证结果与原始 MRZ。
6. 首页右上角「批量导出」可选择 JSON / CSV / ZIP。

---

## 算法正确性

BAC 与安全报文的实现已用 **ICAO Doc 9303-11 附录 D 的官方工作样例**逐字节验证：

| 中间量 | 官方值 | 本项目 |
|---|---|---|
| `Kseed` | `239AB9CB282DAF66231DC5A4DF6BFBAE` | ✅ 一致 |
| `Kenc` | `AB94FDECF2674FDFB9B391F85D7F76F2` | ✅ 一致 |
| `Kmac` | `7962D9ECE03D1ACD4C76089DCE131543` | ✅ 一致 |
| `E.IFD` | `72C29C2371CC9BDB65B779B8E8D37B29ECC154AA56A8799FAE2F498F76ED92F2` | ✅ 一致 |
| `M.IFD` | `5F1448EEA8AD90A7` | ✅ 一致 |
| `K.ICC` | `0B4F80323EB3191CB04970CB4052790B` | ✅ 一致 |
| `KsEnc'` | `979EC13B1CBFE9DCD01AB0FED307EAE5` | ✅ 一致 |
| `KsMac'` | `F1CB1F1FB5ADF208806B89DC579DC1F8` | ✅ 一致 |
| `SSC` | `887022120C06C226` | ✅ 一致 |

输入为 `MRZ_information = L898902C<369080619406236`。

上述断言全部固化在单元测试中，共 **39 个用例全部通过**：

```bash
./gradlew testDebugUnitTest
# DesCryptoTest        9 个用例 —— KDF / Retail MAC / 填充 / E.IFD / M.IFD / 会话密钥 / SSC
# MrzParserTest       19 个用例 —— 校验位 / TD3 / TD1 / 行组提取 / 纠错 / 日期换算
# CscaTrustStoreTest  11 个用例 —— 信任库解析 / 覆盖范围 / 真实证书上的链式验证
```

关键实现细节（容易踩坑的地方）：

* BAC 密钥派生用的是 **ICAO KDF**：`KDF(K, c) = DESParityAdjust(SHA-1(K ‖ c_be32)[0..15])`，
  而不是早期文档里常见的"3DES 加密 D=1/2"写法；
* ISO 9797-1 Padding Method 2 **在数据已经对齐时仍会追加一整个块**，
  这直接影响 `M.IFD` 的计算；
* 安全报文中命令头必须参与 MAC 计算，且 CLA 固定为 `0x0C`；
* 3DES 模式下 CBC 的 IV 恒为全 0（AES 才需要 `IV = K(SSC)`）。

---

## CSCA 信任库

被动认证只能证明「数据没被改过」。要回答「这张证件是不是某国真签发的」，必须把文档签名证书（DSC）
沿签发关系逐级上溯，直到命中签发国的 CSCA 根证书。本应用内置了这份信任库。

| 项目 | 说明 |
|---|---|
| 证书数量 | 513 张（去重后） |
| 覆盖范围 | 103 个国家/地区 |
| 中国相关 | 33 张 —— 中国护照（外交部）16、中国澳门 8、中国台湾地区 7、中国香港 2 |
| 来源 | 德国 BSI German Master List、荷兰 NPKD Netherlands Master List（均为政府公开发布） |
| 体积 | 826 KB，随 APK 打包，**无需联网** |

> 为什么不用官方 SDK 里的 `db_csca.db3`？因为那份只有 **10 张证书、全部是中国签发**，
> 且其中 2 张已经过期（一张 2024-05-12 失效，一张 2026-02-18 失效）。
> 公开主列表覆盖 103 个国家/地区，且可随时更新，是更好的选择。

**更新信任库**：把新的主列表（ICAO PKD 的 LDIF、或德国/荷兰的 `.ml` 文件）用
`tools/csca/extract_csca.py` 提取证书，再用 `tools/csca/build_bundle.py` 打包，
替换 `app/src/main/assets/csca/csca_bundle.bin` 即可。两个脚本都放在 `tools/csca/` 下。

**信任链判定逻辑**（`nfc/pa/CscaChain.kt`）：从 DSC 出发，用「上一级证书的公钥」验证
「下一级证书的签名」，逐级回溯至自签名根，最大深度 4 层以兼容 CSCA 轮换产生的 link 证书。
找不到签发者、签名不匹配、出现环、超出深度，都会给出明确的失败原因而非静默失败。

---

## 已知限制

* 只实现 **BAC**。仅支持 PACE 的证件（部分新版电子护照）无法读取，应用会明确报错提示。
* 只实现**被动认证**，未实现主动认证（AA）与芯片认证（CA），因此无法判定芯片是否被克隆。
* CSCA 信任库覆盖 103 个国家/地区，但**不含证书吊销列表（CRL）**，无法反映已吊销的证书。
  信任库来源为德国 BSI 与荷兰 NPKD 公开发布的主列表，可通过替换 `assets/csca/csca_bundle.bin` 更新。
* 信任库中有 199 张证书使用了**显式 EC 参数（自定义曲线）**，JDK / Android 的
  `CertificateFactory` 无法解析，已在打包阶段剔除。若需覆盖这部分证书，需引入 BouncyCastle 等第三方密码学库。
* 人脸图像支持 JPEG；JPEG2000 格式会被识别并原样保存，但 Android 原生无法直接显示。
* 无网络、无云端，所有数据仅存本机。

---

## 合规与隐私

读取证件芯片涉及**敏感个人信息与生物特征信息**。

* 请仅对**本人证件**或**已取得持证人明确书面授权**的证件使用本应用；
* 遵守《中华人民共和国个人信息保护法》《中华人民共和国出境入境管理法》等法律法规；
* 应用不联网、不上传任何数据，读取结果保存在应用私有目录，卸载即清除，
  且已显式排除在系统备份/迁移之外（见 `res/xml/backup_rules.xml`）；
* 导出的文件包含完整证件信息与人脸图像，请自行妥善保管、按需删除；
* 本项目为技术演示，不构成任何形式的合规意见。

---

## 许可

MIT License，详见 [LICENSE](LICENSE)。

本项目为独立实现，未包含、未链接、未反编译任何官方 SDK 的代码或资源；
`docs/SDK-分析报告.md` 中的分析基于公开发布包的目录结构、公开头文件、公开接口文档
以及可执行文件的符号/导入导出表，仅用于技术研究。
