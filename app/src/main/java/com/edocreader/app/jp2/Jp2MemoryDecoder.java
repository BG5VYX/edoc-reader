package com.edocreader.app.jp2;

import java.io.IOException;

import ucar.jpeg.jj2000.j2k.codestream.HeaderInfo;
import ucar.jpeg.jj2000.j2k.codestream.reader.BitstreamReaderAgent;
import ucar.jpeg.jj2000.j2k.codestream.reader.HeaderDecoder;
import ucar.jpeg.jj2000.j2k.decoder.DecoderSpecs;
import ucar.jpeg.jj2000.j2k.entropy.decoder.EntropyDecoder;
import ucar.jpeg.jj2000.j2k.image.BlkImgDataSrc;
import ucar.jpeg.jj2000.j2k.image.DataBlkInt;
import ucar.jpeg.jj2000.j2k.image.ImgDataConverter;
import ucar.jpeg.jj2000.j2k.image.invcomptransf.InvCompTransf;
import ucar.jpeg.jj2000.j2k.io.ByteArrayRandomAccessIO;
import ucar.jpeg.jj2000.j2k.io.RandomAccessIO;
import ucar.jpeg.jj2000.j2k.quantization.dequantizer.Dequantizer;
import ucar.jpeg.jj2000.j2k.roi.ROIDeScaler;
import ucar.jpeg.jj2000.j2k.util.ParameterList;
import ucar.jpeg.jj2000.j2k.wavelet.synthesis.InverseWT;

/**
 * 在内存中解码 JPEG 2000 码流，输出 RGB 像素。
 *
 * 为什么不直接用 jj2000 自带的 Decoder：那个类把结果写成文件、
 * 且依赖 java.awt / javax.imageio —— Android 上没有这些类。
 * 这里改用 jj2000 的**核心解码链路**（这些包本身不依赖 AWT），
 * 自己把结果取成 int[]。
 *
 * 解码链路（与 jj2000 的 Decoder 一致）：
 *   BitstreamReaderAgent → EntropyDecoder → ROIDeScaler → Dequantizer
 *   → InverseWT → ImgDataConverter → InvCompTransf → 像素
 */
public final class Jp2MemoryDecoder {

    private Jp2MemoryDecoder() {
    }

    /** 解码结果。 */
    public static final class Result {
        public final int width;
        public final int height;
        /** 长度 = width * height，每像素 0xRRGGBB。 */
        public final int[] argb;

        Result(int width, int height, int[] argb) {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }
    }

    /**
     * 解码一段 JPEG 2000 码流（裸码流，非 JP2 文件格式）。
     *
     * @param codestream DG2 中的图像数据
     * @return 解码后的 RGB 像素；失败时抛出异常
     */
    public static Result decode(byte[] codestream) throws Exception {
        ParameterList pl = defaultParameters();
        RandomAccessIO in = new ByteArrayRandomAccessIO(codestream);

        // ---- 1. 主头 ----
        HeaderInfo hi = new HeaderInfo();
        HeaderDecoder hd = new HeaderDecoder(in, pl, hi);
        DecoderSpecs decSpec = hd.getDecoderSpecs();

        int nComp = hd.getNumComps();
        int[] depth = new int[nComp];
        for (int i = 0; i < nComp; i++) {
            depth[i] = hd.getOriginalBitDepth(i);
        }

        // ---- 2. 码流读取（解析包头 / 分包）----
        BitstreamReaderAgent breader = BitstreamReaderAgent.createInstance(
                in, hd, pl, decSpec, false, hi);

        // ---- 3. 熵解码（Tier-2 + Tier-1）----
        EntropyDecoder entdec = hd.createEntropyDecoder(breader, pl);

        // ---- 4. ROI 反缩放 ----
        ROIDeScaler roids = hd.createROIDeScaler(entdec, pl, decSpec);

        // ---- 5. 反量化 ----
        Dequantizer deq = hd.createDequantizer(roids, depth, decSpec);

        // ---- 6. 逆小波变换 ----
        InverseWT invWT = InverseWT.createInstance(deq, decSpec);
        invWT.setImgResLevel(breader.getImgRes());

        // ---- 7. 数据转换 ----
        ImgDataConverter converter = new ImgDataConverter(invWT, 0);

        // ---- 8. 逆分量变换（RCT / ICT）----
        InvCompTransf ictransf = new InvCompTransf(converter, decSpec, depth, pl);

        // ---- 9. 设定当前 tile ----
        // jj2000 的 subband 树是「按当前 tile」保存的，setTile 会沿链路向下传播。
        // 不调用它的话，第一次取像素就会因为 subbTrees 为 null 而抛 NPE。
        // eMRTD 的照片是单 tile，这里直接取 (0,0)；多 tile 时按需扩展。
        if (breader.getNumTiles() > 1) {
            throw new IOException("暂不支持多 tile 的 JPEG 2000 图像（tiles="
                    + breader.getNumTiles() + "）");
        }
        ictransf.setTile(0, 0);

        // ---- 10. 取像素 ----
        return extractPixels(ictransf);
    }

    /** 按分量取数据并合成 0xRRGGBB。 */
    private static Result extractPixels(BlkImgDataSrc src) throws Exception {
        int nComp = src.getNumComps();
        int w = src.getCompImgWidth(0);
        int h = src.getCompImgHeight(0);

        int[][] comps = new int[nComp][];
        int[] shifts = new int[nComp];
        for (int c = 0; c < nComp; c++) {
            shifts[c] = 1 << (src.getNomRangeBits(c) - 1);
            DataBlkInt db = new DataBlkInt();
            db.ulx = 0;
            db.uly = 0;
            db.w = src.getCompImgWidth(c);
            db.h = src.getCompImgHeight(c);
            db = (DataBlkInt) src.getInternCompData(db, c);
            comps[c] = db.getDataInt();
        }

        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r, g, b;
                if (nComp >= 3) {
                    r = sample(comps[0], shifts[0], w, h, x, y);
                    g = sample(comps[1], shifts[1], w, h, x, y);
                    b = sample(comps[2], shifts[2], w, h, x, y);
                } else {
                    // 单分量按灰度展开
                    r = g = b = sample(comps[0], shifts[0], w, h, x, y);
                }
                out[y * w + x] = (r << 16) | (g << 8) | b;
            }
        }
        return new Result(w, h, out);
    }

    /** 取一个分量样本，做电平搬移与钳位。 */
    private static int sample(int[] data, int shift, int w, int h, int x, int y) {
        if (data == null) return 0;
        int v = data[y * w + x] + shift;
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        return v;
    }

    /**
     * 解码所需的参数。
     *
     * jj2000 的 {@link ParameterList} 继承自 {@code java.util.Properties}，
     * 其 defaults 字段要靠构造函数注入；解码器内部会用
     * {@code pl.getDefaultParameterList()} 判断某个选项是否被显式指定，
     * 若 defaults 为 null 会直接抛 NPE，所以这里必须构造一个 defaults。
     *
     * 取值与 jj2000 自带 Decoder 的 pinfo 默认值一致：
     * `rate` / `nbytes` 置 -1 表示不限码率，把整个码流全部解出来
     * （证件照片只有几十 KB，无需按码率截断）。
     */
    private static ParameterList defaultParameters() {
        ParameterList defaults = new ParameterList();
        // 解码范围：-1 表示不限码率，把整个码流全部解出来
        defaults.put("rate", "-1");
        defaults.put("nbytes", "-1");
        defaults.put("parsing", "on");
        // 退出条件（都不启用）
        defaults.put("ncb_quit", "-1");
        defaults.put("l_quit", "-1");
        defaults.put("m_quit", "-1");
        defaults.put("poc_quit", "off");
        defaults.put("one_tp", "off");
        // 变换与熵解码
        defaults.put("comp_transf", "on");
        defaults.put("Cer", "on");
        defaults.put("Cverber", "off");
        // 其它
        defaults.put("verbose", "off");
        defaults.put("debug", "off");
        defaults.put("cdstr_info", "off");
        defaults.put("nocolorspace", "off");
        defaults.put("colorspace_debug", "off");
        return new ParameterList(defaults);
    }
}
