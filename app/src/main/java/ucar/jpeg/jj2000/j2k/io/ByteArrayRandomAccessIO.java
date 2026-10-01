/*
 * JJ2000 COPYRIGHT:
 *
 * This software module was originally developed by Raphaël Grosbois and
 * Diego Santa Cruz (Swiss Federal Institute of Technology-EPFL); Joel
 * Askelöf (Ericsson Radio Systems AB); and Bertrand Berthelot, David
 * Bouchard, Félix Henry, Gerard Mozelle and Patrice Onno (Canon Research
 * Centre France S.A) in the course of development of the JPEG2000
 * standard as specified by ISO/IEC 15444 (JPEG 2000 Standard).
 *
 * 说明：本文件由 EdocReader 项目编写，把 jj2000 的随机访问输入从「文件」
 * 改为「内存字节数组」—— Android 上不需要落盘，且证件照片只有几十 KB，
 * 全部驻留内存更快，也避免了临时文件与权限问题。
 */

package ucar.jpeg.jj2000.j2k.io;

import java.io.EOFException;
import java.io.IOException;

/**
 * 基于内存字节数组的 {@link RandomAccessIO} 实现。
 *
 * jj2000 只自带文件版实现（BufferedRandomAccessFile），这里补上内存版。
 * 按 JPEG 2000 的规定使用**大端序**。
 */
public class ByteArrayRandomAccessIO implements RandomAccessIO {

    private final byte[] data;

    /** 当前读指针。 */
    private int pos = 0;

    public ByteArrayRandomAccessIO(byte[] data) {
        this.data = data;
    }

    // ------------------------------------------------------------ 定位与长度

    @Override
    public void close() throws IOException {
        // 内存数组无需释放
    }

    @Override
    public int getPos() throws IOException {
        return pos;
    }

    @Override
    public int length() throws IOException {
        return data.length;
    }

    @Override
    public void seek(int off) throws IOException {
        if (off < 0) {
            throw new IOException("Attempting to seek to a negative position (" + off + ")");
        }
        pos = off;
    }

    @Override
    public int skipBytes(int n) throws EOFException, IOException {
        if (n < 0) {
            throw new IllegalArgumentException("skipBytes: n must be >= 0");
        }
        int skipped = Math.min(n, data.length - pos);
        pos += skipped;
        return skipped;
    }

    // ---------------------------------------------------------------- 读字节

    @Override
    public int read() throws EOFException, IOException {
        if (pos >= data.length) {
            throw new EOFException("End of data reached");
        }
        return data[pos++] & 0xFF;
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || off + len > b.length) {
            throw new IndexOutOfBoundsException("Invalid offset/length");
        }
        if (pos + len > data.length) {
            throw new EOFException("End of data reached");
        }
        System.arraycopy(data, pos, b, off, len);
        pos += len;
    }

    @Override
    public byte readByte() throws EOFException, IOException {
        return (byte) read();
    }

    @Override
    public int readUnsignedByte() throws EOFException, IOException {
        return read();
    }

    @Override
    public short readShort() throws EOFException, IOException {
        return (short) readUnsignedShort();
    }

    @Override
    public int readUnsignedShort() throws EOFException, IOException {
        int hi = read();
        int lo = read();
        return (hi << 8) | lo;
    }

    @Override
    public int readInt() throws EOFException, IOException {
        int b0 = read();
        int b1 = read();
        int b2 = read();
        int b3 = read();
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    @Override
    public long readUnsignedInt() throws EOFException, IOException {
        return readInt() & 0xFFFFFFFFL;
    }

    @Override
    public long readLong() throws EOFException, IOException {
        long hi = readUnsignedInt();
        long lo = readUnsignedInt();
        return (hi << 32) | lo;
    }

    @Override
    public float readFloat() throws EOFException, IOException {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public double readDouble() throws EOFException, IOException {
        return Double.longBitsToDouble(readLong());
    }

    @Override
    public int getByteOrdering() {
        return EndianType.BIG_ENDIAN;
    }

    // -------------------------------------------------------- 写：只读实现

    @Override
    public void write(int b) throws IOException {
        throw new IOException("ByteArrayRandomAccessIO is read-only");
    }

    @Override
    public void writeByte(int v) throws IOException {
        throw new IOException("ByteArrayRandomAccessIO is read-only");
    }

    @Override
    public void writeShort(int v) throws IOException {
        throw new IOException("ByteArrayRandomAccessIO is read-only");
    }

    @Override
    public void writeInt(int v) throws IOException {
        throw new IOException("ByteArrayRandomAccessIO is read-only");
    }

    @Override
    public void writeLong(long v) throws IOException {
        throw new IOException("ByteArrayRandomAccessIO is read-only");
    }

    @Override
    public void writeFloat(float v) throws IOException {
        throw new IOException("ByteArrayRandomAccessIO is read-only");
    }

    @Override
    public void writeDouble(double v) throws IOException {
        throw new IOException("ByteArrayRandomAccessIO is read-only");
    }

    @Override
    public void flush() throws IOException {
        // 无需实现
    }
}
