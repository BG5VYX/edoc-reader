import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * 用 JDK 自带的 X.509 解析器筛选 CSCA 证书。
 *
 * Android 应用运行时用的就是同一个 CertificateFactory 实现，
 * 因此这里能解析的证书，应用里也一定能解析——比用第三方库判断更准确。
 *
 * 用法：java FilterCsca <输入目录> <输出目录>
 */
public class FilterCsca {

    public static void main(String[] args) throws Exception {
        File inDir = new File(args[0]);
        File outDir = new File(args[1]);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IllegalStateException("无法创建输出目录：" + outDir);
        }

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        File[] files = inDir.listFiles((d, n) -> n.endsWith(".cer"));
        if (files == null) {
            throw new IllegalStateException("输入目录无 .cer 文件：" + inDir);
        }

        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();

        for (File f : files) {
            byte[] der = Files.readAllBytes(f.toPath());
            try {
                X509Certificate cert = (X509Certificate)
                        cf.generateCertificate(new java.io.ByteArrayInputStream(der));
                // 额外访问公钥，确保算法参数也能被解析
                cert.getPublicKey();
                try (FileOutputStream out = new FileOutputStream(new File(outDir, f.getName()))) {
                    out.write(der);
                }
                kept.add(f.getName());
            } catch (Exception e) {
                dropped.add(f.getName() + "  (" + e.getClass().getSimpleName()
                        + ": " + e.getMessage() + ")");
            }
        }

        System.out.println("可解析: " + kept.size() + " 张");
        System.out.println("被剔除: " + dropped.size() + " 张");
        if (!dropped.isEmpty() && dropped.size() <= 20) {
            for (String d : dropped) {
                System.out.println("   - " + d);
            }
        }
    }
}
