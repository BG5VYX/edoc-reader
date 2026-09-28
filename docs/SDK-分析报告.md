# 出入境证件电子信息识读认证 SDK 分析报告

> 分析对象：`niamrtd-android-nia-v1.1.0u1`（Android SDK v1.1.0.2122）与
> `niamrtd-windows-nia-v1.1.0u1`（Windows SDK）
> 发布单位：国家移民管理局 出入境管理信息技术研究所（2019-12 / 2020-01 构建）
> 分析方式：AAR 解包 + `javap` 反查公开接口 + ELF/PE 导入导出表解析 + 官方接口说明 PDF 交叉核对

---

## 一、总体定位

两个 SDK 都是《关于推动出入境证件便利化应用的工作方案》的落地产物，目标是让第三方系统
（酒店、银行、租车、政务窗口等）能够合规读取**电子护照**、**往来港澳通行证**、**往来台湾
通行证**等出入境证件的资料页与芯片信息。

它们的实现机制完全一致，只是承载平台不同：

| | Android SDK | Windows SDK |
|---|---|---|
| 采集方式 | 手机摄像头 | 扫描仪 / 摄像头 |
| 芯片通道 | 手机 NFC（`IsoDep` / ISO 14443-4） | PC/SC 读卡器（`WinSCard.dll`） |
| 对外形态 | `aar` 库 + Java 接口 | `DLL` + `lib` + C 头文件 |
| 开发语言 | Java（JNI 调 native） | C / C++ |
| 授权方式 | `assets/*.lic` 授权文件 | 与 DLL 同目录的 `.LIC` 授权文件 |

共同的技术内核：**OCR 识别 MRZ → 用 MRZ 三要素做 BAC 认证 → 按 ICAO 9303 读取并解析芯片数据 → 验证**。

---

## 二、目录结构

### 2.1 Android SDK（`niamrtd-android-nia-v1.1.0u1`）

```
niamrtd-android-nia-v1.1.0u1/
├── libs/
│   └── niamrtdsdk-nia-v1.1.0.aar          # 主 SDK（3.8 MB）
├── doc/
│   └── 出入境实证认证（Android SDK）接口说明.pdf   # 10 页
└── demo/
    ├── demosrc.zip                        # Demo 工程源码
    └── niamrtd-demo-debug.apk             # 已编译的 Demo（11.7 MB）
```

`niamrtdsdk-nia-v1.1.0.aar` 解包后：

```
AndroidManifest.xml          # 声明 CameraActivity / NFCActivity、权限、vmSafeMode
R.txt                        # 资源 ID 表（84 KB，说明内置了大量 UI 资源）
classes.jar                  # Java 层（107 KB）
libs/eppjars.jar             # 空 jar（占位，仅含 MANIFEST）
jni/
├── arm64-v8a/
│   ├── libniamrtd.so        # 1.19 MB —— Java 层 native 实现 + MRZ/OCR 调度
│   └── libnjaeppinsp.so     # 2.53 MB —— 芯片读取与密码学核心
└── armeabi-v7a/             # 同上两份，32 位
assets/
├── niamrtd.properties       # server.url=<Base64 密文>（联网校验/回传服务端地址）
├── config.properties        # SQLite 连接串、账号口令（明文！见 §6 风险）
├── db_csca.db3              # CSCA 证书库（SQLite，10 条记录）
├── pscp.lar                 # PK 压缩包（内含 pscp/ 目录，OCR 相关资源）
└── pst.traineddata          # Tesseract 护照专用训练数据（325 KB）
res/
├── layout/nja_nfc.xml
├── layout/nja_ocr_activity_camera.xml
├── layout/nja_ocr_take_picture.xml
└── drawable/                # OCR 取景定位框：护照、港澳背面、台湾背面、身份证正反面…
```

关键观察：`res/drawable` 中同时存在 `ocr_passport_locator.png`、
`ocr_passport_locator_back_hk.png`、`ocr_passport_locator_back_tw.png`、
`ocr_id_card_locator_front.png` 等，说明 SDK **内置了针对中国出入境证件的版面模板**，
并非通用 MRZ 识别。

### 2.2 Windows SDK（`niamrtd-windows-nia-v1.1.0u1`）

```
niamrtd-windows-nia-v1.1.0u1/
├── bin/                         # 运行时全部文件（需整体分发）
│   ├── NJAEIDSDK.dll            3.75 MB  主 SDK（MFC + GDI+ 图形界面框架）
│   ├── niamrtd.dll              859 KB   对外 C 接口层
│   ├── xd_passport.dll          257 KB   OCR 引擎（依赖 OpenCV）
│   ├── opencv_world320.dll      35.3 MB  OpenCV 3.2.0 主库
│   ├── opencv_xfeatures2d320.dll 2.8 MB  OpenCV 特征点模块
│   ├── mrz.model                3.9 MB   OpenCV XML 格式的 MRZ 检测模型
│   ├── pscp.lar                 10 KB    资源包（PK 压缩，含 pscp/ 目录）
│   ├── VerifySOD.mdb            384 KB   SOD 验签规则库
│   ├── CVCA/                    （空目录）中国 CVCA 证书目录
│   ├── NJAEIDSDK.LIC            899 B    授权文件（二进制/加密）
│   ├── niamrtd.conf             配置文件，内含 server.url=<Base64 密文>
│   ├── demo.exe                 36 KB    MFC42 编写的 Demo
│   ├── msvcp100.dll / msvcr100.dll / vcomp100.dll   MSVC 2010 运行时 + OpenMP
│   └── msvcr100 依赖的 CRT
├── lib/
│   └── niamrtd.lib              3.6 KB   导入库
├── demosrc/demosrc.zip          MFC Demo 源码（demo.cpp / demoDlg.cpp / DlgMrz.cpp …）
└── doc/
    └── 出入境实证认证（Windows SDK）接口说明.pdf  # 13 页
```

---

## 三、主要接口

### 3.1 Android SDK

对外门面类只有两个包：`cn.gov.nia.mrtd`（官方封装）与 `com.bjnja.nmrtd`（实现）。

```java
// ---- 门面 ----
public final class cn.gov.nia.mrtd.NiaMRTDSdk {
    public static NiaMRTDSdk create(Activity activity);        // 初始化，失败返回 null
    public void readMRZ(NiaCallback cb);                       // 拉起摄像头识别 MRZ
    public void readChipData(NiaCallback cb);                  // 用 SDK 内部缓存的 MRZ 读芯片
    public void readChipData(String docNo, String dob,
                             String doe, NiaCallback cb);       // 直接用三要素读芯片
    // 结果 Map 的 Key 常量
    public static final String Key_DocumentNumber, Key_DateOfBirth, Key_DateOfExpiry,
        Key_CertName, Key_Issuer, Key_FullName, Key_Gender, Key_Nationality,
        Key_FamilyName, Key_GivenName, Key_FacialImage, Key_NativeName, Key_Status;
}

// ---- 回调 ----
public interface NiaCallback {
    void onResult(Map<String, Object> result);
    void onError(String errmsg);
    void onCancle();                       // 注意：官方拼写为 Cancle
}
public abstract class NiaBaseCallback implements NiaCallback { /* 三个空实现 */ }
```

底层实现类（`com.bjnja.nmrtd`）：

```java
public class com.bjnja.nmrtd.NjaMRTDSdk {
    public static boolean initSdk(Context);                          // 触发 native 初始化
    public static boolean readMRZ(Activity, NjaCallback);
    public static boolean readChipData(Activity, NjaCallback);
    public static boolean readChipData(Activity, String, String, String, NjaCallback);
    public static native String[] checkMrzInformation(String[]);      // native MRZ 校验/纠错
    public static String getPositionInfo();                          // 定位信息（授权校验用）
    private static native boolean _init(String[]);                    // 传入 IMEI / 手机号
    private static native boolean _setapp(Object);
}

// 芯片读取（NFC）核心
public class com.bjnja.nmrtd.common.MRTDReader implements IMrtdReader {
    public IsoDep channel;
    public Map<String, Object> doRead();                                        // 用内部 MRZ
    public Map<String, Object> doRead(String docNo, String dob, String doe);     // 用传入三要素
    public byte[] IccCommand(byte[]) throws IOException;                         // 透传 APDU
    public byte[] IccReset() throws IOException;
    private static native int AddCscaToStore(int, byte[]);                       // 灌入 CSCA 证书
    private native long EppReadChipData(String, String, String);                 // native 读卡
    private native String getTagStr(long, int);                                  // 取信息项
    private native byte[] getFacialImage(long);                                  // 取人脸 JPEG
    private native void releaseCtx(long);
}
```

内置 Activity（AAR 的 Manifest 已声明，宿主无需再声明）：

| Activity | 作用 |
|---|---|
| `com.bjnja.nmrtd.camera.CameraActivity` | OCR 取景（全屏、竖屏、`Theme.Holo.NoActionBar.Fullscreen`） |
| `com.bjnja.nmrtd.nfc.NFCActivity` | 读卡引导（`dialogstyle`） |

native 符号（从 `.so` 导出表读出）揭示了内部分层：

```
libniamrtd.so   → Java_com_bjnja_nmrtd_NjaMRTDSdk__1init / __1setapp / checkMrzInformation
                  Java_com_bjnja_nmrtd_common_MRTDReader_AddCscaToStore / EppReadChipData /
                      getFacialImage / getTagStr / releaseCtx
libnjaeppinsp.so → Java_com_nja_epp_Reader_EppInit / EppGetLic / EppGetTlv /
                      EppReadChipData / AddCscaToStore / SetLogFile
```

### 3.2 Windows SDK

头文件 `niamrtd.h` 给出全部 10 个导出函数：

```c
// ---- 初始化 ----
int  NIACALL NM_Init(const char* terminalID, void* reserved1);   // 程序启动调用一次
void NIACALL NM_DeInit(void);                                    // 程序结束调用

// ---- 业务 ----
int  NM_ParseMRZ(const char *file);                              // 识别 MRZ 图片并缓存在 SDK 内
int  NM_IsParsedMRZ(void);                                       // 是否已有可用的 MRZ
NiaEppContext* NIACALL NM_ReadEpp(const char *readerName);       // 用缓存 MRZ 读芯片
NiaEppContext* NIACALL NM_ReadEppEx(const char *readerName,      // 用传入三要素读芯片
        const char *pDocumentNumber, const char *pDateOfBirth, const char *pDateOfExpiry);
const void* NIACALL NMC_GetEppInfo(NiaEppContext *ctx, int tag, int *size);  // 取信息项
void NMC_FreeContext(NiaEppContext *ctx);

// ---- 读卡器 ----
int NIACALL NM_ListReaders(int size, char *readerNames);         // 枚举 PC/SC 读卡器
int NIACALL NMH_WaitCard(const char* reader, int timeout);       // 等待放卡
int NIACALL NMH_WaitCancel();                                    // 取消等待（需另起线程）
```

返回值约定：

```c
#define NME_OK        0    // 成功
#define NME_FAIL     -1    // 执行失败
#define NME_PARAM    -2    // 参数不合法
#define NME_MEMORY   -3    // 内存不足
#define NME_TIMEOUT  -4    // 超时
#define NME_SYS      -5    // 系统错误（读卡器通讯异常等）
#define NME_NETWORK  -6    // 网络错误
#define NME_INIT    -16    // SDK 未初始化或初始化失败
```

信息项 TAG（`enum ItemInfoTag`，与 Android 端 `Key_*` 一一对应）：

| Tag | 含义 | Tag | 含义 |
|---|---|---|---|
| `0x0101` | 证件类型中文名 | `0x0122` | 国籍（ISO3166 3 字符） |
| `0x0102` | 颁发国（ISO3166 3 字符） | `0x0123` | 姓 |
| `0x0103` | 全名（英文） | `0x0143` | 名 |
| `0x0104` | 证件号码 | `0x0201` | 芯片照片（JPEG 二进制） |
| `0x0105` | 出生日期（YYYY-MM-DD） | `0x0B01` | 母语全名（中国证件即中文姓名） |
| `0x0106` | 有效期（YYYY-MM-DD） | `0x4001` | 查验结果（`"1"` 成功 / `"-1"` 未通过） |
| `0x0107` | 性别（F/M） | | |

---

## 四、调用方式

### 4.1 Android

```java
// 1) 申请权限：CAMERA、NFC、READ_PHONE_STATE、读写外部存储、ACCESS_COARSE_LOCATION
// 2) 把 aar 放进 libs，abiFilters 至少包含 armeabi-v7a / arm64-v8a
// 3) 授权文件放进 src/main/assets/

NiaMRTDSdk sdk = NiaMRTDSdk.create(MainActivity.this);   // 授权失败返回 null
if (sdk == null) { /* 初始化查验库失败 */ }

// 第一步：OCR 识别 MRZ
sdk.readMRZ(new NiaBaseCallback() {
    @Override public void onResult(Map<String, Object> r) {
        documentNumber = r.get(NiaMRTDSdk.Key_DocumentNumber).toString();
        dateOfBirth    = r.get(NiaMRTDSdk.Key_DateOfBirth).toString();
        dateOfExpiry   = r.get(NiaMRTDSdk.Key_DateOfExpiry).toString();
        // 第二步：读芯片（SDK 内部已缓存这三个值）
        sdk.readChipData(new NiaBaseCallback() {
            @Override public void onResult(Map<String, Object> chip) {
                Bitmap face = (Bitmap) chip.get(NiaMRTDSdk.Key_FacialImage);
                // 其余字段见 §3.1 的 Key_* 常量
            }
            @Override public void onError(String m) { }
            @Override public void onCancle() { }
        });
    }
    @Override public void onError(String msg) { }
    @Override public void onCancle() { }
});
```

`readMRZ` 的回调**至少**返回证件号、出生日期、有效期三个字段，SDK 会缓存它们供
`readChipData` 使用；也可以跳过 OCR，直接调 `readChipData(docNo, dob, doe, cb)`。

### 4.2 Windows

```c
NM_Init("myclient", NULL);                       // 启动时一次

// 情形 A：需要 OCR 识别证件照片
NM_ParseMRZ("page.jpg");                         // 图片 400dpi，护照 2019×1535 像素
if (NM_IsParsedMRZ() != NME_OK) { /* 请先识别或输入 MRZ */ }
ctx = NM_ReadEpp(readerName);                    // 完整查验流程（含联网）

// 情形 B：不需要照片，直接给三要素
ctx = NM_ReadEppEx(readerName, docNo, "690806", "940623");  // 日期格式 YYMMDD

// 取结果
const char *name = (const char*)NMC_GetEppInfo(ctx, NEPP_Tag_FullName, NULL);
int len; LPBYTE jpeg = (LPBYTE)NMC_GetEppInfo(ctx, NEPP_Tag_FacialImage, &len);
NMC_FreeContext(ctx);

NM_DeInit();
```

Demo（`demoDlg.cpp`）的读卡流程：`NM_ListReaders` 枚举读卡器 → `NM_IsParsedMRZ`
判断是否需要先识别 → `NM_ReadEpp` / `NM_ReadEppEx` → 用 `DisplayState/DisplayString/
DisplayFace/DisplayMrz` 逐项取出 → `NMC_FreeContext`。官方注释明确提醒
`NM_ReadEpp` 耗时较长，**不要放在 UI 线程**。

---

## 五、第三方依赖

### Android

| 依赖 | 用途 | 来源 |
|---|---|---|
| `com.rmtheis:tess-two:6.0.0` | Tesseract OCR + Leptonica（`pst.traineddata` 为护照专用训练集） | Demo `build.gradle` |
| `com.alibaba:fastjson:1.2.47` | Demo 中 JSON 序列化 | Demo `build.gradle` |
| `com.android.support:appcompat-v7:28.0.0`、`design:28.0.0`、`constraint-layout:1.1.3` | UI | Demo `build.gradle` |
| OpenSSL（静态编译进 `libnjaeppinsp.so`） | 证书解析、CMS 验签（符号含 `OPENSSL_*`） | `.so` 符号表 |
| libjpeg-turbo + OpenJPEG | DG2 人脸图像（JPEG / JPEG2000）解码 | `.so` 字符串 |
| libcurl | 与服务端通信（符号含 `CURL_SSL_BACKEND` 等） | `.so` 字符串 |
| SQLite（Android 系统内置） | 读写 `db_csca.db3` | `config.properties` |

### Windows

| 依赖 | 用途 |
|---|---|
| `opencv_world320.dll` + `opencv_xfeatures2d320.dll` + `vcomp100.dll` | OpenCV 3.2.0：图像预处理、特征点匹配、MRZ 区域定位 |
| `xd_passport.dll` | OCR 引擎（依赖 OpenCV） |
| `mrz.model`（3.9 MB，`opencv_storage` XML） | MRZ 检测模型 |
| `WinSCard.dll` | PC/SC 读卡器通信 |
| `gdiplus.dll` / `MSIMG32.dll` / `AVICAP32.dll` | 图像与摄像头采集 |
| `CRYPT32.dll` | 证书与签名校验 |
| `WS2_32.dll` | 网络（联网查验） |
| `msvcp100.dll` / `msvcr100.dll` | MSVC 2010 运行时（**不是** VC2015+ 通用运行时，部署时须一并分发） |
| `MFC42.DLL` | `demo.exe` 使用的 MFC 4.2（仅 Demo） |
| `VerifySOD.mdb` | SOD 验签规则库 |
| `CVCA/` | 中国 CVCA 证书目录（随包为空，需自行补充） |

---

## 六、读取证件信息的实现原理

两个 SDK 的算法路径一致，可拆成四段：

### 6.1 第一段：图像采集 + MRZ OCR

1. 摄像头 / 扫描仪采集证件**资料页**图像。Windows 端明确要求 400 dpi、无反光，
   护照 2019×1535 像素、卡式证件 1461×957 像素。
2. 用 OpenCV 做灰度化、二值化、透视校正，并以 `mrz.model`（OpenCV 格式的检测模型）
   结合内置版面模板（`ocr_passport_locator*.png` 等）定位 MRZ 区域。
3. 用 OCR 引擎识别 MRZ 字符。Android 端用的是 **Tesseract + 护照专用训练集
   `pst.traineddata`**；Windows 端用的是自研 `xd_passport.dll`。
4. 按 ICAO 9303 的 TD1（3×30）/ TD2（2×36）/ TD3（2×44）格式切分字段，计算校验位
   （加权因子 7-3-1），并对 OCR 的字母/数字混淆做纠错。
   Android 侧这个纠错逻辑由 `NjaMRTDSdk.checkMrzInformation(String[])` 这个 native
   方法承担，输入输出都是字符串数组。

识别结果中最关键的只有三个字段：**证件号、出生日期、有效期**——它们构成 BAC 口令。

### 6.2 第二段：BAC 基本访问控制（ICAO 9303 Part 11 §4.3）

芯片里的数据是加密的，读取前必须证明"读写器确实看到过证件表面"，这就是 BAC 的意义。
口令不是密码，而是 MRZ 上公开印刷的内容：

```
MRZ_information = 证件号 ‖ 证件号校验位 ‖ 出生日期(YYMMDD) ‖ 校验位 ‖ 有效期(YYMMDD) ‖ 校验位
                  （固定 24 个字符，例：L898902C<369080619406236）

Kseed = SHA-1(MRZ_information)[0..15]
Kenc  = 奇偶调整( SHA-1(Kseed ‖ 0x00000001)[0..15] )
Kmac  = 奇偶调整( SHA-1(Kseed ‖ 0x00000002)[0..15] )
```

握手过程：

```
1. 读写器 → 芯片   GET CHALLENGE                      → RND.ICC (8 字节)
2. 读写器生成 RND.IFD (8) 与 K.IFD (16)
3. S     = RND.IFD ‖ RND.ICC ‖ K.IFD                  (32 字节)
4. E.IFD = 3DES-CBC(Kenc, IV=0, S)                    (32 字节)
5. M.IFD = RetailMAC(Kmac, Pad(E.IFD))                (8 字节)
6. 读写器 → 芯片   MUTUAL AUTHENTICATE(E.IFD ‖ M.IFD)  (40 字节)
7. 芯片   → 读写器 E.ICC (32) ‖ M.ICC (8)              (40 字节)
8. 校验 M.ICC，解密 E.ICC 得到 RND.ICC' ‖ RND.IFD' ‖ K.ICC
9. 校验两个随机数回显，Kseed' = K.IFD ⊕ K.ICC
   KsEnc = KDF(Kseed', 1)，KsMac = KDF(Kseed', 2)
10. SSC = RND.ICC[4..8] ‖ RND.IFD[4..8]
```

**这几步走完，就建立了 3DES 安全报文通道。**

### 6.3 第三段：安全报文 + 数据组读取

安全报文按 ISO 7816-4 / ICAO 9303-11 §9.8 封装：

* 命令：`CLA=0x0C`，数据放入 DO'87'（INS 偶数）或 DO'85'（INS 奇数），
  明文先加 `0x01` 前缀再按 ISO 9797-1 Method 2 填充，用 KsEnc 做 3DES-CBC（IV=0）加密；
* MAC 放在 DO'8E'，计算对象是 `SSC ‖ Pad(命令头, CLA 置 0x0C) ‖ DO'87'/'85' ‖ DO'97'`；
* 响应：`[DO'87'/'85'] DO'99' DO'8E'`，先验 MAC，再解 DO'87'，状态字从 DO'99' 取。

随后按以下顺序读取文件：

```
SELECT AID A0000002471001        → 选择 eMRTD 应用
SELECT EF.COM (011E) 并读取       → 芯片声明了哪些数据组
READ BINARY EF.DG1  (0101)       → MRZ（可与 OCR 结果交叉比对）
READ BINARY EF.DG2  (0102)       → 面部图像（JPEG 或 JPEG2000）
READ BINARY EF.DG11 (010B)       → 附加个人资料（含持证人母语姓名）
READ BINARY EF.DG14 (010E)       → 安全选项（PACE / 芯片认证 / 主动认证参数）
READ BINARY EF.DG15 (010F)       → 主动认证公钥
READ BINARY EF.SOD  (011D)       → LDS Security Object（CMS SignedData）
```

读取时先读 4 字节文件头，从中解析 TLV 长度得到文件总长，再分块读取；安全报文下单条
响应 APDU 上限 256 字节，扣除 DO'87'/'99'/'8E' 的开销后，单次明文块以 224 字节为宜。

### 6.4 第四段：验证

* **被动认证（Passive Authentication）**：EF.SOD 是 CMS SignedData，其 eContent 内的
  LDS Security Object 保存了各数据组的摘要。重新计算本地读到的 DG1/DG2/… 摘要并比对，
  可发现数据被篡改；再用内嵌的文档签名证书（DSC）验证 CMS 签名，确认 SOD 本身未被伪造。
  完整的信任链还需用签发国 CSCA 证书主列表验证 DSC——Android 端把它放在
  `db_csca.db3`（SQLite，表 `tb_csca`，含 `t_subject/t_serial_no/t_key_id/t_before/
  t_noafter/t_cert/t_cn/t_c/t_verify` 字段），Windows 端放在 `CVCA/` 目录与
  `VerifySOD.mdb`。
* **主动认证（Active Authentication）**：用 DG15 中的公钥向芯片发起挑战，芯片用私钥签名，
  证明芯片不是克隆的。
* **芯片认证（Chip Authentication）**：用 DG14 参数协商会话密钥，防止芯片被替换。

---

## 七、授权与合规机制（为什么"需要授权文件"）

SDK 的发布形态中明确包含"授权文件"，Android 端放 `src/main/assets/`，Windows 端与 DLL
同目录。从代码与数据看，授权链路由三部分组成：

1. **本地授权文件**：`nmrtdinp.lic`（Demo）/ `NJAEIDSDK.LIC`。
   `NJAEIDSDK.LIC` 中出现明文片段 `123456`、`112233445566778899`、`demo.exe` 等，
   说明它把"终端标识 + 应用名"等信息打包后加密。
2. **设备指纹**：`NjaMRTDSdk._init(String[])` 接收的字符串数组来自
   `readImes()` / `readImei2()` / `readPhone2()` / `getPositionInfo()`，
   即 **IMEI、手机号、定位信息**；`READ_PHONE_STATE`、`READ_PHONE_NUMBERS`、
   `ACCESS_COARSE_LOCATION` 权限正是为此申请。
3. **服务端校验**：`assets/niamrtd.properties` 与 `bin/niamrtd.conf` 中的
   `server.url=<Base64 密文>`，配合 `libcurl`，说明初始化时会联网校验授权并可能回传
   使用情况。Windows 文档也写明 `NM_ReadEpp` "需要连接出入境管理信息技术研究所服务器，
   请保证网络畅通，否则可能无法正常完成查验"。

**因此，不使用授权文件就无法调用该 SDK 的任何读证能力。**

---

## 八、绕过 SDK 的合法替代路径（本工程采用的方案）

值得注意的是：**"读取证件芯片"这件事本身完全由公开国际标准定义，并不需要专有 SDK。**
ICAO Doc 9303 是公开标准，BAC / 安全报文 / 数据组结构都有完整的公开规范与工作样例。
因此可以完全独立地实现同一套流程，且不涉及任何授权问题：

| 环节 | SDK 的做法 | 本工程的做法 |
|---|---|---|
| MRZ 识别 | Tesseract + 护照专用训练集 / `xd_passport.dll` | Google ML Kit 文本识别（Latin，模型随 APK 打包，离线） + 自研 MRZ 行组提取与校验位纠错 |
| 芯片通道 | NFC / PC-SC | Android `IsoDep`（NFC Reader Mode） |
| BAC | 原生实现 | 自研（已用 ICAO 9303-11 附录 D 官方向量逐字节验证） |
| 安全报文 | 原生实现 | 自研（同上） |
| 数据组解析 | 原生实现 | 自研 BER-TLV 解析 |
| 被动认证 | OpenSSL + CSCA 库 | 自研 DER/CMS 解析 + Android JCE 验签（不内置 CSCA 主列表，如实标注） |
| 联网 | 必须 | 完全不需要 |

这一路径的代价是：**无法获得 SDK 提供的"官方查验结论"**（`Key_Status` / `NEPP_Tag_Status`
那个 `"1"` / `"-1"`），因为它依赖服务端与官方 CSCA 主列表。但对"读取并保存证件信息"
这一目标而言，本地实现已经完备且可验证。

---

## 九、安全性观察

在分析过程中注意到几处值得指出的问题（仅作技术说明）：

1. **`assets/config.properties` 明文保存数据库账号口令**：
   `DB_USERNAME=bjnjaca` / `DB_PASSWORD=www.bjnja.ca`，且连接串指向
   `/sdcard/njaepp/db/db_csca.db3`（可被具备存储权限的应用读写）。
2. **CSCA 证书库存放在外部存储路径**，存在被替换导致被动认证结论失真的风险。
3. **`libs/eppjars.jar` 为空占位**，若宿主项目按名称排除依赖容易踩坑。
4. **授权文件与 Demo APK 一并分发**，Demo 的 `.lic` 不能用于其他 `applicationId`。
5. **依赖 fastjson 1.2.47**（Demo 中），该版本历史上存在多个反序列化漏洞，
   生产项目不应沿用。
6. **`libnjaeppinsp.so` 静态链接了 OpenSSL 且版本未知**，无法通过系统补丁更新，
   存在长期维护风险。

---

## 十、结论

* 两个 SDK 是同一技术内核在不同平台上的落地：**OCR 读 MRZ → BAC 认证 → ICAO 9303 读芯片 → 被动/主动认证**。
* 对外接口都很收敛（Android 5 个方法 + 13 个 Key 常量；Windows 10 个导出函数 + 14 个 TAG），
  真正的复杂度全部在 native 层与内置的版面模板、训练数据、模型文件里。
* **读证能力本身依赖公开国际标准，而非专有算法**；SDK 的授权机制约束的是"使用它的实现"，
  并不约束"按公开标准自行实现"。
* 本工程因此选择完全自研的实现路径，既不需要授权文件，也不联网，且所有关键密码学步骤
  都能用 ICAO 官方测试向量复现验证。

---

### 参考

* ICAO Doc 9303, *Machine Readable Travel Documents*, 8th Edition, 2021
  （Part 3 机读区规格、Part 10 逻辑数据结构、Part 11 非接触式芯片安全机制）
* ISO/IEC 7816-4：APDU 与安全报文
* ISO/IEC 9797-1：MAC Algorithm 3（Retail MAC）与 Padding Method 2
* 《中华人民共和国个人信息保护法》
