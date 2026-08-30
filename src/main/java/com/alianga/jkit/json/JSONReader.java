package com.alianga.jkit.json;

import com.alianga.jkit.json.exceptions.JSONException;
import com.alianga.jkit.reflect.GenericParameterizedType;

import java.io.*;

/**
 * 1,JSON parsing based on stream (character stream):
 * <p>
 * Large file JSON file parsing (unlimited file size reading), no need to read stream content into memory for parsing
 * . <br>
 * Can be terminated as needed. <br>
 * Supports asynchronous. <br>
 * </p>
 * <br>
 * 2, The streaming content needs to strictly adhere to the JSON specification.
 *
 * <br>
 * <br>
 * Example:
 * <pre>
 * final JSONReader reader = JSONReader.from(new File("/tmp/text.json"));
 * </pre>
 * <p> 1、Read complete stream
 * <pre>
 * reader.read();
 * Object result = reader.getResult(); (map or list)
 * </pre>
 * 2、On demand read stream
 * <p> Specify pattern when constructing ReaderCallback
 * <pre>
 * reader.read(new JSONReader.ReaderCallback(JSONReader.ReadParseMode.ExternalImpl) {
 *
 * public void parseValue(String key, Object value, Object host, int elementIndex, String path) throws Exception {
 * if(path.equals("/features/[100000]/properties/STREET")) {
 * System.out.println(value);
 * abort();
 * }
 * }
 * }, true);
 * </pre>
 * <p> Calling abort() can terminate stream read at any time
 *
 * @see JSONReaderHook
 * @see JSONReader#JSONReader(InputStream)
 * @see JSONReader#JSONReader(InputStream, String)
 * @see JSONReader#JSONReader(Reader)
 * @see JSON
 * @see JSONNode
 * @see JSONCharArrayWriter
 */
public class JSONReader extends JSONAbstractReader {
    /**
     * Character stream reader
     */
    final Reader reader;

    /**
     * Buffered character array
     */
    private char[] buf;

    /**
     * Building a JSON stream reader from a file object
     *
     * @param file
     */
    private JSONReader(File file) throws FileNotFoundException {
        this(new FileReader(file));
    }

    /**
     * Building a JSON stream reader from a file object
     *
     * @param file the JSON file to read
     * @return a new JSONReader reading the given file with the default charset
     */
    public static JSONReader from(File file) {
        try {
            return new JSONReader(file);
        } catch (FileNotFoundException e) {
            throw new JSONException(e);
        }
    }

    /**
     * Building a JSON stream reader from a stream object
     *
     * @param inputStream the stream to read, decoded with the default charset
     * @return a new JSONReader reading the given stream
     */
    public static JSONReader from(InputStream inputStream) {
        return new JSONReader(inputStream);
    }

    /**
     * Build through strings
     *
     * @param json the complete JSON string
     * @return a new JSONReader over the characters of the given string
     */
    public static JSONReader from(String json) {
        return new JSONReader(getChars(json));
    }

    /**
     * Build through character arrays
     *
     * @param source the character array holding the complete JSON content
     * @return a new JSONReader over the given character array
     */
    public static JSONReader from(char[] source) {
        return new JSONReader(source);
    }

    /**
     * Build through character arrays
     *
     * @param buf the character array holding the complete JSON content, used directly without copying
     */
    public JSONReader(char[] buf) {
        this.buf = buf;
        this.count = buf.length;
        this.reader = null;
    }

    /**
     * Building a JSON stream reader from a stream object
     *
     * @param inputStream the stream to read, decoded with the default charset
     */
    public JSONReader(InputStream inputStream) {
        this.reader = new InputStreamReader(inputStream);
    }

    /**
     * Building a JSON stream reader from a stream object
     *
     * @param inputStream the stream to read, decoded with the default charset
     * @param buffSize the size of the character buffer used while reading
     */
    public JSONReader(InputStream inputStream, int buffSize) {
        this.reader = new InputStreamReader(inputStream);
        this.bufferSize = buffSize;
    }

    /**
     * Building a JSON stream reader from a stream object
     *
     * @param inputStream the stream to read
     * @param charsetName the charset used to decode the stream, an unsupported name causes an
     *                    {@link UnsupportedOperationException}
     */
    public JSONReader(InputStream inputStream, String charsetName) {
        Reader reader;
        try {
            reader = new InputStreamReader(inputStream, charsetName);
        } catch (UnsupportedEncodingException e) {
            throw new UnsupportedOperationException(e);
        }
        this.reader = reader;
    }

    /**
     * Build directly through the reader
     *
     * @param reader the character stream to read, must not be {@code null}
     */
    public JSONReader(Reader reader) {
        if (reader == null) {
            throw new UnsupportedOperationException("reader is null");
        }
        this.reader = reader;
    }

    /**
     * Set the size of the character buffer used while reading
     *
     * @param bufferSize the expected buffer size, values below 64 are raised to 64
     * @return this reader, for chained calls
     */
    public final JSONReader bufferSize(int bufferSize) {
        this.bufferSize = Math.max(bufferSize, 64);
        return this;
    }

    /**
     * 直接从流中提取数据
     *
     * @param is 待读取的JSON流
     * @param path 要提取的节点路径，例如 {@code /features/[0]/properties}
     * @return 路径匹配到的第一个值，未匹配到时返回 {@code null}
     */
    public static final Object exactPath(InputStream is, String path) {
        JSONReader jsonReader = from(is);
        JSONReaderHook readerHook = JSONReaderHook.exactPath(path);
        jsonReader.read(readerHook);
        return readerHook.first();
    }

    /**
     * 直接从流中提取数据并转化为指定类型
     *
     * @param is 待读取的JSON流
     * @param path 要提取的节点路径
     * @param resultClass 提取结果的目标类型
     * @param <T> 提取结果的类型
     * @return 路径匹配到的第一个值（已转换为目标类型），未匹配到时返回 {@code null}
     */
    public static final <T> T exactPathAs(InputStream is, String path, Class<T> resultClass) {
        return exactPathAs(is, path, GenericParameterizedType.actualType(resultClass));
    }

    /**
     * 直接从流中提取数据并转化为指定类型
     *
     * @param is 待读取的JSON流
     * @param path 要提取的节点路径
     * @param parameterizedType 提取结果的泛型类型描述
     * @param <T> 提取结果的类型
     * @return 路径匹配到的第一个值（已按泛型类型转换），未匹配到时返回 {@code null}
     */
    public static final <T> T exactPathAs(InputStream is, String path, GenericParameterizedType<T> parameterizedType) {
        JSONReader jsonReader = from(is);
        JSONReaderHook readerHook = JSONReaderHook.exactPathAs(path, parameterizedType);
        jsonReader.read(readerHook);
        return readerHook.first();
    }

    public final Object read() {
        try {
            this.readBuffer();
            if (!multiple && this.isCompleted()) {
                return JSONStore.PARSER.parse(buf, 0, count, readOptions);
            }
            this.defaultRead();
        } catch (Exception e) {
            throw new JSONException(e);
        } finally {
            this.tryCloseReader();
        }
        return result;
    }

    protected void readBuffer() throws IOException {
        if (reader == null) {
            return;
        }
        if (buf == null) {
            buf = new char[bufferSize];
        }
        if (this.readingOffset > -1) {
            // put all the remaining unread content in the builder
            if (bufferSize > this.readingOffset) {
                this.writer.append(buf, this.readingOffset, bufferSize - this.readingOffset);
            }
            // reset
            this.readingOffset = 0;
        }
        if (offset >= count) {
            offset = count = 0;
            int n;
            int remSize = bufferSize;
            while ((n = reader.read(buf, count, remSize)) != -1) {
                count += n;
                if (remSize == n) {
                    return;
                }
                remSize -= n;
            }
            // n = -1
            completed = true;
        }
    }

    protected String endReadingAsString(int n) {
        if (writer.length() > 0) {
            endReading(n);
            return writer.toString();
        } else {
            int endIndex = offset + n;
            String result = new String(buf, this.readingOffset, endIndex - this.readingOffset);
            this.readingOffset = -1;
            return result;
        }
    }

    /**
     * @param n         End offset correction position
     * @param newOffset the offset from which the next reading starts
     */
    protected void endReading(int n, int newOffset) {
        int endIndex = offset + n;
        if (endIndex > this.readingOffset) {
            this.writer.append(buf, this.readingOffset, endIndex - this.readingOffset);
        }
        this.readingOffset = newOffset;
    }

    protected int readNext() throws Exception {
        pos++;
        if (offset < count) {
            return current = buf[offset++];
        }
        if (isCompleted()) {
            return current = -1;
        }
        readBuffer();
        if (count <= 0) {
            return current = -1;
        }
        return current = buf[offset++];
    }

    /**
     * whether the reading completed
     *
     * @return {@code true} if there is no underlying reader or the stream has reached the end,
     *         otherwise {@code false}
     */
    protected boolean isCompleted() {
        return reader == null || completed;
    }

    /**
     * close stream
     */
    public void close() {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException e) {
        } finally {
            this.closed = true;
        }
    }
}
