package com.alianga.jkit.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Class AnimatedGifEncoder - Encodes a GIF file consisting of one or
 * more frames.
 * <pre>
 * Example:
 * AnimatedGifEncoder e = new AnimatedGifEncoder();
 * e.start(outputFileName);
 * e.setDelay(1000); // 1 frame per sec
 * e.addFrame(image1);
 * e.addFrame(image2);
 * e.finish();
 * </pre>
 * No copyright asserted on the source code of this class. May be used
 * for any purpose, however, refer to the Unisys LZW patent for restrictions
 * on use of the associated Encoder class. Please forward any corrections
 * to questions at fmsware.com.
 *
 * @author wuhongjun
 * @version 1.03 November 2003
 */
public class GifEncoder {
    /** Width in pixels of the logical screen and of every written frame. */
    protected int width; // image size
    /** Height in pixels of the logical screen and of every written frame. */
    protected int height;
    /** Color to be treated as transparent, or {@code null} when no transparency is used. */
    protected Color transparent; // transparent color if given
    /** Index of the transparent color within the current color table. */
    protected int transIndex; // transparent index in color table
    /** Number of extra playbacks; {@code -1} means the Netscape loop extension is not written. */
    protected int repeat = -1; // no repeat
    /** Delay of the current frame, expressed in hundredths of a second. */
    protected int delay; // frame delay (hundredths)
    /** Whether the GIF header has been written and frames may be added. */
    protected boolean started; // ready to output frames
    /** Destination stream the GIF bytes are written to. */
    protected OutputStream out;
    /** Frame currently being encoded. */
    protected BufferedImage image; // current frame
    /** Raw BGR pixel data of the current frame, three bytes per pixel. */
    protected byte[] pixels; // BGR byte array from frame
    /** Current frame after quantization, one palette index per pixel. */
    protected byte[] indexedPixels; // converted frame indexed to palette
    /** Number of bit planes used by the encoder, always 8 here. */
    protected int colorDepth; // number of bit planes
    /** Quantized RGB palette, three bytes per entry. */
    protected byte[] colorTab; // RGB palette
    /** Flags telling which palette entries are actually referenced by the current frame. */
    protected boolean[] usedEntry = new boolean[256]; // active palette entries
    /** Color table size encoded as {@code bits - 1}. */
    protected int palSize = 7; // color table size (bits-1)
    /** Frame disposal code; {@code -1} means the default is derived from the transparency setting. */
    protected int dispose = -1; // disposal code (-1 = use default)
    /** Whether {@link #finish()} should close the output stream. */
    protected boolean closeStream; // close stream when finished
    /** Whether the next frame to write is the first one. */
    protected boolean firstFrame = true;
    /** Whether the frame size was set explicitly through {@link #setSize(int, int)}. */
    protected boolean sizeSet; // if false, get size from first frame
    /** Sample interval passed to the quantizer, lower values mean better colors. */
    protected int sample = 10; // default sample interval for quantizer

    /**
     * Sets the delay time between each frame, or changes it
     * for subsequent frames (applies to last frame added).
     *
     * @param ms int delay time in milliseconds
     */
    public void setDelay(int ms) {
        delay = Math.round(ms / 10.0f);
    }

    /**
     * Sets the GIF frame disposal code for the last added frame
     * and any subsequent frames. Default is 0 if no transparent
     * color has been set, otherwise 2.
     *
     * @param code int disposal code.
     */
    public void setDispose(int code) {
        if (code >= 0) {
            dispose = code;
        }
    }

    /**
     * Sets the number of times the set of GIF frames
     * should be played. Default is 1; 0 means play
     * indefinitely. Must be invoked before the first
     * image is added.
     *
     * @param iter int number of iterations.
     */
    public void setRepeat(int iter) {
        if (iter >= 0) {
            repeat = iter;
        }
    }

    /**
     * Sets the transparent color for the last added frame
     * and any subsequent frames.
     * Since all colors are subject to modification
     * in the quantization process, the color in the final
     * palette for each frame closest to the given color
     * becomes the transparent color for that frame.
     * May be set to null to indicate no transparent color.
     *
     * @param c Color to be treated as transparent on display.
     */
    public void setTransparent(Color c) {
        transparent = c;
    }

    /**
     * Adds next GIF frame. The frame is not written immediately, but is
     * actually deferred until the next frame is received so that timing
     * data can be inserted. Invoking <code>finish()</code> flushes all
     * frames. If <code>setSize</code> was not invoked, the size of the
     * first image is used for all subsequent frames.
     *
     * @param im BufferedImage containing frame to write.
     * @return true if successful.
     */
    public boolean addFrame(BufferedImage im) {
        if ((im == null) || !started) {
            return false;
        }
        boolean ok = true;
        try {
            if (!sizeSet) {
                // use first frame's size
                setSize(im.getWidth(), im.getHeight());
            }
            image = im;
            getImagePixels(); // convert to correct format if necessary
            analyzePixels(); // build color table & map pixels
            if (firstFrame) {
                writeLSD(); // logical screen descriptior
                writePalette(); // global color table
                if (repeat >= 0) {
                    // use NS app extension to indicate reps
                    writeNetscapeExt();
                }
            }
            writeGraphicCtrlExt(); // write graphic control extension
            writeImageDesc(); // image descriptor
            if (!firstFrame) {
                writePalette(); // local color table
            }
            writePixels(); // encode and write pixel data
            firstFrame = false;
        } catch (IOException e) {
            ok = false;
        }

        return ok;
    }

    //added by alvaro
    /**
     * Flushes the underlying output stream without closing it.
     *
     * @return true if the flush succeeded, false if an IOException occurred
     */
    public boolean outFlush() {
        boolean ok = true;
        try {
            out.flush();
            return ok;
        } catch (IOException e) {
            ok = false;
        }

        return ok;
    }

    /**
     * Returns the bytes written so far, assuming the encoder was started on a
     * {@link ByteArrayOutputStream}.
     *
     * @return a copy of the GIF bytes buffered in the output stream
     * @throws ClassCastException if the output stream is not a ByteArrayOutputStream
     */
    public byte[] getFrameByteArray() {
        return ((ByteArrayOutputStream) out).toByteArray();
    }

    /**
     * Flushes any pending data and closes output file.
     * If writing to an OutputStream, the stream is not
     * closed.
     *
     * @return true if the trailer was written and the stream flushed, false if the encoder
     *     was not started or an IOException occurred
     */
    public boolean finish() {
        if (!started) {
            return false;
        }
        boolean ok = true;
        started = false;
        try {
            out.write(0x3b); // gif trailer
            out.flush();
            if (closeStream) {
                out.close();
            }
        } catch (IOException e) {
            ok = false;
        }

        return ok;
    }

    /**
     * Clears the per-encoding state (stream, frame, pixel buffers and palette) so that
     * this instance can be reused for a new GIF. Frame size, delay, repeat count and
     * quality settings are kept.
     */
    public void reset() {
        // reset for subsequent use
        transIndex = 0;
        out = null;
        image = null;
        pixels = null;
        indexedPixels = null;
        colorTab = null;
        closeStream = false;
        firstFrame = true;
    }

    /**
     * Sets frame rate in frames per second. Equivalent to
     * <code>setDelay(1000/fps)</code>.
     *
     * @param fps float frame rate (frames per second)
     */
    public void setFrameRate(float fps) {
        if (fps != 0f) {
            delay = Math.round(100f / fps);
        }
    }

    /**
     * Sets quality of color quantization (conversion of images
     * to the maximum 256 colors allowed by the GIF specification).
     * Lower values (minimum = 1) produce better colors, but slow
     * processing significantly. 10 is the default, and produces
     * good color mapping at reasonable speeds. Values greater
     * than 20 do not yield significant improvements in speed.
     *
     * @param quality int greater than 0.
     */
    public void setQuality(int quality) {
        if (quality < 1) {
            quality = 1;
        }
        sample = quality;
    }

    /**
     * Sets the GIF frame size. The default size is the
     * size of the first frame added if this method is
     * not invoked.
     *
     * @param w int frame width.
     * @param h int frame width.
     */
    public void setSize(int w, int h) {
        if (started && !firstFrame) {
            return;
        }
        width = w;
        height = h;
        if (width < 1) {
            width = 320;
        }
        if (height < 1) {
            height = 240;
        }
        sizeSet = true;
    }

    /**
     * Initiates GIF file creation on the given stream. The stream
     * is not closed automatically.
     *
     * @param os OutputStream on which GIF images are written.
     * @return false if initial write failed.
     */
    public boolean start(OutputStream os) {
        if (os == null) {
            return false;
        }
        boolean ok = true;
        closeStream = false;
        out = os;
        try {
            writeString("GIF89a"); // header
        } catch (IOException e) {
            ok = false;
        }
        return started = ok;
    }

    /**
     * Initiates writing of a GIF file with the specified name.
     *
     * @param file String containing output file name.
     * @return false if open or initial write failed.
     */
    public boolean start(String file) {
        boolean ok = true;
        try {
            out = new BufferedOutputStream(new FileOutputStream(file));
            ok = start(out);
            closeStream = true;
        } catch (IOException e) {
            ok = false;
        }
        return started = ok;
    }

    /**
     * Analyzes image colors and creates color map.
     */
    protected void analyzePixels() {
        int len = pixels.length;
        int nPix = len / 3;
        indexedPixels = new byte[nPix];
        Quant nq = new Quant(pixels, len, sample);
        // initialize quantizer
        colorTab = nq.process(); // create reduced palette
        // convert map from BGR to RGB
        for (int i = 0; i < colorTab.length; i += 3) {
            byte temp = colorTab[i];
            colorTab[i] = colorTab[i + 2];
            colorTab[i + 2] = temp;
            usedEntry[i / 3] = false;
        }
        // map image pixels to new palette
        int k = 0;
        for (int i = 0; i < nPix; i++) {
            int index =
                    nq.map(pixels[k++] & 0xff,
                            pixels[k++] & 0xff,
                            pixels[k++] & 0xff);
            usedEntry[index] = true;
            indexedPixels[i] = (byte) index;
        }
        pixels = null;
        colorDepth = 8;
        palSize = 7;
        // get closest match to transparent color if specified
        if (transparent != null) {
            transIndex = findClosest(transparent);
        }
    }

    /**
     * Returns index of palette color closest to c
     *
     * @param c the color to look up
     * @return index of the used palette entry with the smallest RGB distance to c,
     *     or -1 if no color table has been built yet
     */
    protected int findClosest(Color c) {
        if (colorTab == null) {
            return -1;
        }
        int r = c.getRed();
        int g = c.getGreen();
        int b = c.getBlue();
        int minpos = 0;
        int dmin = 256 * 256 * 256;
        int len = colorTab.length;
        for (int i = 0; i < len;) {
            int dr = r - (colorTab[i++] & 0xff);
            int dg = g - (colorTab[i++] & 0xff);
            int db = b - (colorTab[i] & 0xff);
            int d = dr * dr + dg * dg + db * db;
            int index = i / 3;
            if (usedEntry[index] && (d < dmin)) {
                dmin = d;
                minpos = index;
            }
            i++;
        }
        return minpos;
    }

    /**
     * Extracts image pixels into byte array "pixels"
     */
    protected void getImagePixels() {
        int w = image.getWidth();
        int h = image.getHeight();
        int type = image.getType();
        if ((w != width)
                || (h != height)
                || (type != BufferedImage.TYPE_3BYTE_BGR)) {
            // create new image with right size/format
            BufferedImage temp =
                    new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = temp.createGraphics();
            g.drawImage(image, 0, 0, null);
            image = temp;
        }
        pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    }

    /**
     * Writes Graphic Control Extension
     *
     * @throws IOException if writing to the output stream fails
     */
    protected void writeGraphicCtrlExt() throws IOException {
        out.write(0x21); // extension introducer
        out.write(0xf9); // GCE label
        out.write(4); // data block size
        int transp;
        int disp;
        if (transparent == null) {
            transp = 0;
            disp = 0; // dispose = no action
        } else {
            transp = 1;
            disp = 2; // force clear if using transparent color
        }
        if (dispose >= 0) {
            disp = dispose & 7; // user override
        }
        disp <<= 2;

        // packed fields
        out.write(0 | // 1:3 reserved
                disp | // 4:6 disposal
                0 | // 7   user input - 0 = none
                transp); // 8   transparency flag

        writeShort(delay); // delay x 1/100 sec
        out.write(transIndex); // transparent color index
        out.write(0); // block terminator
    }

    /**
     * Writes Image Descriptor
     *
     * @throws IOException if writing to the output stream fails
     */
    protected void writeImageDesc() throws IOException {
        out.write(0x2c); // image separator
        writeShort(0); // image position x,y = 0,0
        writeShort(0);
        writeShort(width); // image size
        writeShort(height);
        // packed fields
        if (firstFrame) {
            // no LCT  - GCT is used for first (or only) frame
            out.write(0);
        } else {
            // specify normal LCT
            out.write(0x80 | // 1 local color table  1=yes
                    0 | // 2 interlace - 0=no
                    0 | // 3 sorted - 0=no
                    0 | // 4-5 reserved
                    palSize); // 6-8 size of color table
        }
    }

    /**
     * Writes Logical Screen Descriptor
     *
     * @throws IOException if writing to the output stream fails
     */
    protected void writeLSD() throws IOException {
        // logical screen size
        writeShort(width);
        writeShort(height);
        // packed fields
        out.write((0x80 | // 1   : global color table flag = 1 (gct used)
                0x70 | // 2-4 : color resolution = 7
                0x00 | // 5   : gct sort flag = 0
                palSize)); // 6-8 : gct size

        out.write(0); // background color index
        out.write(0); // pixel aspect ratio - assume 1:1
    }

    /**
     * Writes Netscape application extension to define
     * repeat count.
     *
     * @throws IOException if writing to the output stream fails
     */
    protected void writeNetscapeExt() throws IOException {
        out.write(0x21); // extension introducer
        out.write(0xff); // app extension label
        out.write(11); // block size
        writeString("NETSCAPE" + "2.0"); // app id + auth code
        out.write(3); // sub-block size
        out.write(1); // loop sub-block id
        writeShort(repeat); // loop count (extra iterations, 0=repeat forever)
        out.write(0); // block terminator
    }

    /**
     * Writes color table
     *
     * @throws IOException if writing to the output stream fails
     */
    protected void writePalette() throws IOException {
        out.write(colorTab, 0, colorTab.length);
        int n = (3 * 256) - colorTab.length;
        for (int i = 0; i < n; i++) {
            out.write(0);
        }
    }

    /**
     * Encodes and writes pixel data
     *
     * @throws IOException if writing to the output stream fails
     */
    protected void writePixels() throws IOException {
        Encoder encoder = new Encoder(width, height, indexedPixels, colorDepth);
        encoder.encode(out);
    }

    /**
     * Write 16-bit value to output stream, LSB first
     *
     * @param value the 16-bit value to write, only its low two bytes are used
     * @throws IOException if writing to the output stream fails
     */
    protected void writeShort(int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    /**
     * Writes string to output stream
     *
     * @param s the string to write, one byte per character
     * @throws IOException if writing to the output stream fails
     */
    protected void writeString(String s) throws IOException {
        for (int i = 0; i < s.length(); i++) {
            out.write((byte) s.charAt(i));
        }
    }
}
