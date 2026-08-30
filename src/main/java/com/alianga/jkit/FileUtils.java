package com.alianga.jkit;

import com.alianga.jkit.convert.ConvertUtils;
import com.alianga.jkit.log.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * This class provides methods to create new file/directory
 */
public class FileUtils {
    private static final String FOLDER_SEPARATOR = "/";

    private static final char FOLDER_SEPARATOR_CHAR = '/';

    private static final String WINDOWS_FOLDER_SEPARATOR = "\\";

    private static final String TOP_PATH = "..";

    private static final String CURRENT_PATH = ".";

    private static final char EXTENSION_SEPARATOR = '.';

    private static final Log log = Log.get(FileUtils.class);
    /**
     * 默认缓存区大小（默认50KB）
     */
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 50;
    /**
     * 读取大文件时的默认缓存区大小（默认5M）
     */
    private static final int DEFAULT_LARGE_BUFFER_SIEZE = 5 * 1024 * 1024;
    /**
     * 默认读写文件编码
     */
    private static final String DEFAULT_ENCODING = "UTF-8";

    /**
     * 无法探测出 MIME 类型时使用的默认 Content-Type
     */
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /**
     * 1KB 占用的长度
     */
    public static final Long KB = 1024L;
    /**
     * 1MB 占用的长度
     */
    public static final Long MB = 1024 * KB;

    /**
     * GB 占用的长度
     */
    public static final Long GB = 1024 * MB;

    /**
     * Enhancement of java.io.File#createNewFile()
     * Create the given file. If the parent directory don't exists, we will create them all.
     *
     * @param file the file to be created
     * @return true if the named file does not exist and was successfully created; false if the named file already
     * exists
     * @throws IOException if an I/O error occurs while creating the file
     * @see File#createNewFile
     */
    public static boolean createFile(File file) throws IOException {
        boolean flag = false;
        try {
            if (!file.exists()) {
                makeDir(file.getParentFile());
            }
            flag = file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return flag;
    }

    /**
     * Enhancement of java.io.File#mkdir()
     * Create the given directory . If the parent folders don't exists, we will create them all.
     *
     * @param dir the directory to be created
     * @see File#mkdir()
     */
    public static void makeDir(File dir) {
        try {
            if (!dir.getParentFile().exists()) {
                makeDir(dir.getParentFile());
            }
            dir.mkdir();
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    /**
     * @param path 文件路径及文件名称
     * @param content 要写入的内容
     * @param append 是否追加 新内容在原有内容中，{@code ture}追加 {@code false}不追加
         * @time 2017年3月10日 上午10:30:44
     * @description <p> 向文本文件中写入内容或追加新内容,如果append为true则直接追加新内容,<br>
     * 如果append为false则覆盖原来的内容<br> <br>
     */
    public static void writeFile(String path, String content, boolean append) {
        File writefile;
        FileOutputStream fw = null;
        try {
            writefile = new File(path);

            // 如果文本文件不存在则创建它
            // modify by zml 修正当传入的文件是相对路径时，直接获取parent会有空指针问题
            String parentPath = writefile.getAbsoluteFile().getParent();
            File parentFile = new File(parentPath);
            if (!parentFile.exists()) {
                parentFile.mkdirs();
            }
            if (!writefile.exists()) {
                writefile.createNewFile();
                writefile = new File(path); // 重新实例化
            }

            fw = new FileOutputStream(writefile, append);
            log.debug("###content:{}", content);
            fw.write(content.getBytes());
            fw.flush();
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            IOUtils.close(fw);
        }
    }

    /**
     * 复制文件
     *
     * @param source 源文件
     * @param target 目标文件，已存在时内容会被覆盖
     */
    public static void nioTransferCopyFile(File source, File target) {
        FileChannel in = null;
        FileChannel out = null;
        FileInputStream inStream = null;
        FileOutputStream outStream = null;
        try {
            inStream = new FileInputStream(source);
            outStream = new FileOutputStream(target);
            in = inStream.getChannel();
            out = outStream.getChannel();

            in.transferTo(0, in.size(), out);

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            IOUtils.close(outStream);
            IOUtils.close(inStream);
            IOUtils.close(in);
            IOUtils.close(out);
        }
    }

    /**
     * 将存放在sourceFilePath目录下的源文件，打包成fileName名称的zip文件，并存放到zipFilePath路径下
     *
     * @param sourceFilePath :待压缩的文件路径
     * @param zipFilePath :压缩后存放路径
     * @param fileName :压缩后文件的名称
     * @return 生成的压缩文件名称；无法压缩时返回空字符串
     */
    @SuppressWarnings("resource")
    public static String fileToZip(String sourceFilePath, String zipFilePath, String fileName) {
        File sourceFile = new File(sourceFilePath);
        FileInputStream fis = null;

        BufferedInputStream bis = null;
        FileOutputStream fos = null;
        ZipOutputStream zos = null;
        String zipName = "";

        if (!sourceFile.exists()) {
            log.debug("待压缩的文件目录：{}不存在.", sourceFilePath);
        } else {
            try {
                File zipFile = new File(zipFilePath + "/" + fileName + ".zip");
                if (zipFile.exists()) {
                    log.debug("{}目录下存在名字为:{}.zip 打包文件.", zipFilePath, fileName);
                } else {
                    File[] sourceFiles = sourceFile.listFiles();
                    if (null == sourceFiles || sourceFiles.length < 1) {
                        log.debug("待压缩的文件目录：{} 里面不存在文件，无需压缩.", sourceFilePath);
                    } else {
                        fos = new FileOutputStream(zipFile);
                        zos = new ZipOutputStream(new BufferedOutputStream(fos));
                        byte[] bufs = new byte[DEFAULT_BUFFER_SIZE];
                        for (int i = 0; i < sourceFiles.length; i++) {
                            //创建ZIP实体，并添加进压缩包
                            ZipEntry zipEntry = new ZipEntry(sourceFiles[i].getName());
                            zos.putNextEntry(zipEntry);
                            //读取待压缩的文件并写进压缩包里
                            fis = new FileInputStream(sourceFiles[i]);
                            //fixme 这里需要对流关闭一下，不然可能会因为文件过大，导致内存溢出
                            bis = new BufferedInputStream(fis, DEFAULT_BUFFER_SIZE);
                            int read = 0;
                            while ((read = bis.read(bufs, 0, DEFAULT_BUFFER_SIZE)) != -1) {
                                zos.write(bufs, 0, read);
                            }
                        }
                    }
                }
                zipName = zipFile.getName();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                //关闭流
                IOUtils.close(zos);
                IOUtils.close(bis);
                IOUtils.close(fis);
                IOUtils.close(fos);
            }
        }
        return zipName;
    }

    /**
     * 递归删除目录下的所有文件及子目录下所有文件
     *
     * @param dir 将要删除的文件目录
     * @return boolean Returns "true" if all deletions were successful.
     * If a deletion fails, the method stops attempting to
     * delete and returns "false".
     * @throws FileNotFoundException 入参 {@code dir} 为 {@code null} 时抛出
     */
    public static boolean deleteDir(File dir) throws FileNotFoundException {
        if (dir == null) {
            throw new FileNotFoundException();
        }

        if (dir.isDirectory()) {
            String[] children = dir.list();
            //递归删除目录中的子目录下
            for (int i = 0; children != null && i < children.length; i++) {
                boolean success = deleteDir(new File(dir, children[i]));
                if (!success) {
                    return false;
                }
            }
        }

//        Files.delete();
        // 目录此时为空，可以删除
        return dir.delete();
    }

    /**
     * @param filePath 纯文本文件路径
     * @param encoding 文件编码
     * @return 文件内容
     */
    public static String readTxtFile(String filePath, String encoding) {
        return readTxtFile(new File(filePath), encoding);
    }

    /**
     * @param filePath 纯文本文件路径
     * @return 文件内容
     * @since 1.1.2
     */
    public static String readTxtFile(String filePath) {
        return readTxtFile(new File(filePath), DEFAULT_ENCODING);
    }

    /**
     * @param file 纯文本文件路径
     * @return 文件内容
     * @since 1.1.2
     */
    public static String readTxtFile(File file) {
        return readTxtFile(file, DEFAULT_ENCODING);
    }

    /**
         * @email zhengmingliang911@gmail.com
     * @time 2017年3月28日 下午5:51:05
     * @description <p> 读取文件中的内容 </P>
     * @param fileName
     * @return See the method result described above.
     */
    /**
     * 读取纯文本文件内容
     *
     * @param file 待读取的纯文本文件
     * @param encoding 该文件编码 ，若输入null则自动判断文件编码
     * @return See the method result described above.
     */
    public static String readTxtFile(File file, String encoding) {
        FileInputStream fis = null;
        InputStreamReader read = null;
        BufferedReader bufferedReader = null;
        try {
            String filePath = file.getAbsolutePath();
            encoding = encoding == null ? EncodingDetect.getJavaEncode(filePath) : encoding;
            log.debug(filePath);
            StringBuilder sb = new StringBuilder();
            if (file.isFile() && file.exists()) { // 判断文件是否存在
                fis = new FileInputStream(file);
                read = new InputStreamReader(fis, encoding); // 考虑到编码格式
                bufferedReader = new BufferedReader(read, DEFAULT_LARGE_BUFFER_SIEZE);
                String lineTxt = null;
                while ((lineTxt = bufferedReader.readLine()) != null) {
                    sb.append(lineTxt).append("\r\n");
                }

            } else {
                log.error("找不到指定的文件");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("读取文件内容出错", e);
        } finally {
            IOUtils.close(read);
            IOUtils.close(fis);
            IOUtils.close(bufferedReader);
        }
        return "";
    }

    /**
     * 分批读取大文本文件，每累积到指定行数就回调一次，首行单独通过
     * {@link Callback#getFirstLine(String)} 回调。读取异常仅打印堆栈，不向外抛出。
     *
     * @param file           待读取的文件
     * @param encoding       文件编码
     * @param maxLinePerTime 每次回调累积的最大行数
     * @param callback       内容回调
     */
    public static void readLargeFile(File file, String encoding, long maxLinePerTime, Callback callback) {
        try (BufferedInputStream fis = new BufferedInputStream(new FileInputStream(file));
             // 用5M的缓冲读取文本文件
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, encoding),
                     DEFAULT_LARGE_BUFFER_SIEZE)) {
            String line = reader.readLine();
            callback.getFirstLine(line);
            int lineCount = 0;
            StringBuilder sb = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\r\n");
                lineCount++;
                if (lineCount >= maxLinePerTime) {
                    callback.apply(sb.toString());
                    lineCount = 0;
                    sb = new StringBuilder();
                }
            }
            if (sb.length() > 0) {
                callback.apply(sb.toString());
                sb = null;
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 逐行读取大文本文件，每读到一行就回调一次，首行单独通过
     * {@link Callback#getFirstLine(String)} 回调。读取异常仅打印堆栈，不向外抛出。
     *
     * @param file     待读取的文件
     * @param encoding 文件编码
     * @param callback 内容回调
     */
    public static void readLargeFileByLine(File file, String encoding, Callback callback) {
        try (FileInputStream fis = new FileInputStream(file);
             // 用5M的缓冲读取文本文件
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, encoding),
                     DEFAULT_LARGE_BUFFER_SIEZE)) {
            String line = reader.readLine();
            callback.getFirstLine(line);
            while ((line = reader.readLine()) != null) {
                callback.apply(line);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 基于 NIO 的 {@link Files#lines} 逐行读取大文本文件，
     * 首行通过 {@link Callback#getFirstLine(String)} 回调，其余行逐行通过 {@link Callback#apply(String)} 回调。
     * 读取异常仅打印堆栈，不向外抛出。
     *
     * @param path     文件路径
     * @param encoding 文件编码名称
     * @param callback 内容回调
     */
    public static void readLargeFileByLineNIO(String path, String encoding, Callback callback) {
        try (Stream<String> lines = Files.lines(Paths.get(path), Charset.forName(encoding))) {
            Iterator<String> iterator = lines.iterator();
            callback.getFirstLine(iterator.hasNext() ? iterator.next() : "");
            while (iterator.hasNext()) {
                callback.apply(iterator.next());
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /*public static void readLargeFile(File file, String encoding, Callback callback) {
        try {
            long counts = 0;
            FileInputStream fis = new FileInputStream(file);
            FileChannel fc = fis.getChannel();
            ByteBuffer byteBuffer = ByteBuffer.allocate(2048);
            int offset = 0;
            byte[] buffer = new byte[2048];

            while((offset = fc.read(byteBuffer)) != -1) {
                byteBuffer.flip();
                byteBuffer.get(buffer,0,byteBuffer.limit());
//                if(offset <= 10000){
                    String str = new String(buffer, "UTF-8");
//                    System.out.println(str);

//                }
                counts = counts + offset;
                byteBuffer.clear();
            }
            fc.close();
            fis.close();
//            return counts;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }*/

    /**
     * 按指定文件大小切割文件（可能会存在字符被切割的问题）
     *
     * @param sourceFileName 需要切割的源文件
     * @param targetFolder 切割的目标文件放置的文件夹
     * @param size 每个文件的大小（单位：字节）
     */
    public static void cutFile(String sourceFileName, String targetFolder, int size) {
        BufferedOutputStream bos = null;
        File targetFolderFile = new File(targetFolder);
        File sourceFile = new File(sourceFileName);
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(sourceFile))) {
            String fullName = sourceFile.getName();
            int beginIndex = fullName.indexOf(".");
            if (beginIndex == -1) {
                beginIndex = fullName.length() - 1;
            }
            //源文件名称（不含后缀名）
            String fileName = fullName.substring(0, beginIndex);
            // 源文件后缀名
            String suffixName = fullName.substring(beginIndex);

            byte[] bytes = new byte[DEFAULT_BUFFER_SIZE];
            int count = 0;
            int len = 0;
            int suffixCount = 0;
            while ((len = bis.read(bytes)) != -1) {
                if (bos == null) {
                    suffixCount++;
                    String targetFileName =
                            String.format("%s%s%s_%s%s", targetFolderFile.getAbsolutePath(), File.separator,
                                    fileName, suffixCount, suffixName);
                    bos = new BufferedOutputStream(new FileOutputStream(new File(targetFileName)));
                }
                bos.write(bytes, 0, len);
                count += len;

                // 分次写入文件
                if (count >= size) {
                    bos.flush();
                    bos.close();
                    bos = null;
                    count = 0;
                }
            }

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            IOUtils.flush(bos);
            IOUtils.close(bos);
        }
    }

    /**
     * 将源文件拆分成多个子文件（根据行数）
     *
     * @param sourceFileName 需要拆分的源文件全路径文件名
     * @param encoding 源文件编码
     * @param targetFolder 拆分的文件存储路径
     * @param maxLine 每个拆分的文件最多多少行
     * @description 该方法在拆分大文件（几个G）时最多需要消耗1G多的内存，可使用 com.alianga.jkit.FileUtils#cutFileByNIO(java.lang.String, java.lang
     * .String, java.lang.String, long)方法拆分大文件
     * @see FileUtils#cutFileByLine(String, String, String, long)
     */
    @Deprecated
    public static void cutFile(String sourceFileName, String encoding, String targetFolder, long maxLine) {
        FileInputStream fis = null;
        BufferedReader reader = null;
        BufferedWriter writer = null;
        try {
            File targetFolderFile = new File(targetFolder);
            if (!targetFolderFile.exists()) {
                targetFolderFile.mkdirs();
            }
            File sourceFile = new File(sourceFileName);
            String fullName = sourceFile.getName();
            int beginIndex = fullName.indexOf('.');
            if (beginIndex == -1) {
                beginIndex = fullName.length() - 1;
            }
            //源文件名称（不含后缀名）
            String fileName = fullName.substring(0, beginIndex);
            // 源文件后缀名
            String suffixName = fullName.substring(beginIndex);

            fis = new FileInputStream(sourceFile);
            // 用5M的缓冲读取文本文件
            reader = new BufferedReader(new InputStreamReader(fis, encoding), DEFAULT_LARGE_BUFFER_SIEZE);

            String line = "";
            writer = null;
            int lineCount = 0;
            int suffixCount = 0;

            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (writer == null) {
                    // 文件后缀数量累计
                    suffixCount++;
                    // 拆分的新文件名称
                    String targetFileName =
                            String.format("%s%s%s_%s%s", targetFolderFile.getAbsolutePath(), File.separator,
                                    fileName, suffixCount, suffixName);
                    writer = new BufferedWriter(new FileWriter(new File(targetFileName)), DEFAULT_LARGE_BUFFER_SIEZE);
                }
                writer.write(line + "\r\n");

                // 当总行数大于等于设定最大行时，写入文件，并重新初始化
                if (lineCount >= maxLine) {
                    writer.flush();
                    writer.close();
                    writer = null;
                    // 重新初始化累计和
                    lineCount = 0;
                }
            }

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            IOUtils.flush(writer);
            IOUtils.close(writer);
            IOUtils.close(reader);
            IOUtils.close(fis);
        }
    }

    /**
     * 将源文件拆分成多个子文件（根据行数）
     *
     * @param sourceFileName 需要拆分的源文件全路径文件名
     * @param encoding 源文件编码
     * @param targetFolder 拆分的文件存储路径
     * @param maxLine 每个拆分的文件最多多少行，必须大于 0
     * @throws IllegalArgumentException {@code maxLine} 不大于 0 时抛出
     * @throws UncheckedIOException 读取源文件或写入拆分文件失败时抛出，避免拆分结果残缺却被判定为成功
     * @description 该方法在拆分大文件（5个G）时最多需要消耗800M的内存，后续稳定在几十到200M内存之间
     * @see FileUtils#cutFile(String, String, String, long)
     */
    public static void cutFileByLine(String sourceFileName, String encoding, String targetFolder, long maxLine) {
        if (maxLine <= 0) {
            throw new IllegalArgumentException("maxLine must be greater than 0, but was " + maxLine);
        }
        File targetFolderFile = new File(targetFolder);
        if (!targetFolderFile.exists()) {
            targetFolderFile.mkdirs();
        }
        File sourceFile = new File(sourceFileName);
        String fullName = sourceFile.getName();
        int beginIndex = fullName.indexOf('.');
        if (beginIndex == -1) {
            beginIndex = fullName.length() - 1;
        }
        //源文件名称（不含后缀名）
        String fileName = fullName.substring(0, beginIndex);
        // 源文件后缀名
        String suffixName = fullName.substring(beginIndex);
        Charset charset = Charset.forName(encoding);

        // 行计数器
        long lineCount = 0;
        //拆分的目标文件数量计数器
        int suffixCount = 0;
        BufferedWriter writer = null;
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(sourceFileName), charset)) {
            String line;
            while ((line = reader.readLine()) != null) {
                //1. 需要写入新的文件时，对相关参数进行初始化
                if (lineCount == 0) {
                    // 文件后缀数量累计
                    suffixCount++;
                    // 拆分的新文件名称
                    String targetFileName =
                            String.format("%s%s%s_%s%s", targetFolderFile.getAbsolutePath(), File.separator,
                                    fileName, suffixCount, suffixName);
                    writer = Files.newBufferedWriter(Paths.get(targetFileName), charset);
                }
                //写入缓冲区
                writer.write(line + "\r\n");
                lineCount++;

                //写入文件行数达到要求行数时，数据刷盘，并重新计数
                if (lineCount >= maxLine) {
                    // 从缓冲区刷盘，并关闭释放io资源
                    writer.flush();
                    writer.close();
                    writer = null;
                    // 重新初始化累计和
                    lineCount = 0;
                }
            }
            // 不足 maxLine 的尾部数据仍在缓冲区中，必须刷盘后关闭，否则最后一个拆分文件为空
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cut file by line failed: " + sourceFileName, e);
        } finally {
            // 仅在异常路径上生效：避免抛出前泄漏未关闭的写句柄
            IOUtils.close(writer);
        }
    }

    /**
     * @param f 文件或目录
     * @return 文件或目录大小（字节）
     * @throws Exception 获取文件大小失败
         * @email zhengmingliang911@gmail.com
     * @time 2017年4月13日 下午7:21:57
     * @description 获取指定文件夹大小
     */
    public static long getFileSizes(File f) throws Exception {
        long size = 0;
        if (f == null) {
            throw new UnsupportedOperationException("传入file不能为空");
        }
        if (f.isFile()) {
            return getFileSize(f);
        }
        if (f.exists()) {
            File[] flist = f.listFiles();
            for (int i = 0; flist != null && i < flist.length; i++) {
                if (flist[i].isDirectory()) {
                    size = size + getFileSizes(flist[i]);
                } else {
                    size = size + getFileSize(flist[i]);
                }
            }
        } else {
            log.error("{}不存在", f.getAbsolutePath());
        }

        return size;
    }

    /**
     * @param file 文件
     * @return 文件大小（字节）
     * @email zhengmingliang911@gmail.com
     * @time 2017年4月13日 下午7:21:43
     * @description <p>获取指定文件大小 </p>
     */
    public static long getFileSize(File file) {
        long size = 0;
        if (file.exists() && file.isFile()) {
            return file.length();
        } else {
//            file.createNewFile();
            log.error("{}不存在,已创建新文件", file.getAbsolutePath());
        }
        return size;
    }

    /**
     * 修改文件或目录的文件名，不变更路径，只是简单修改文件名<br>
     *
     *
     * <pre>
     * FileUtil.rename(file, "aaa.jpg", false) xx/xx.png =》xx/aaa.jpg
     * </pre>
     *
     * @param file 被修改的文件
     * @param newName 新的文件名，包括扩展名
     * @param isOverride 是否覆盖目标文件
     * @return 目标文件
     * @since 1.1.2
     */
    public static File rename(File file, String newName, boolean isOverride) {
        final Path path = file.toPath();
        final CopyOption[] options =
                isOverride ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING} : new CopyOption[]{};
        try {
            return Files.move(path, path.resolveSibling(newName), options).toFile();
        } catch (IOException e) {
            log.error("rename异常", e);
        }
        return null;
    }

    /**
     * 获取文件Content-Type(Mime-Type)
     *
     * @param filePath 文件路径
     * @return 文件的 MIME 类型，无法探测时返回 {@code application/octet-stream}
     */
    public static String getContentType(String filePath) {
        return getContentType(Paths.get(filePath));
    }

    /**
     * 获取文件Content-Type(Mime-Type)
     *
     * @param file 文件
     * @return 文件的 MIME 类型，无法探测时返回 {@code application/octet-stream}
     */
    public static String getContentType(File file) {
        return getContentType(file.toPath());
    }

    private static String getContentType(Path path) {
        try {
            // probeContentType 探测不出类型时返回 null，此时要退回默认值而不是把 null 传出去
            String probed = Files.probeContentType(path);
            if (StringUtils.isNotBlank(probed)) {
                return probed;
            }
        } catch (IOException e) {
            log.debug("探测文件 MIME 类型失败: {}", path, e);
        }
        return DEFAULT_CONTENT_TYPE;
    }

    /**
     * 通过抑制像 "../" 、"path/.." 和 "." 这样的序列来规范化路径
     * <p>结果便于路径比较。对于其他用途，请注意 Windows 分隔符 ("\") 被简单的斜杠替换
     *
     * <p><strong>NOTE</strong> 不应该依赖 {@code cleanPath} 去解决安全问题 . 应使用其他机制来预防路径遍历问题。
     *
     * <p>实现统一收敛到 {@link StringUtils#cleanPath(String)}，此处仅作为文件工具入口保留。
     *
     * @param path 原始路径
     * @return 规范化后的路径
     */
    public static String cleanPath(String path) {
        return StringUtils.cleanPath(path);
    }

    /**
     * @param url 网络请求
     * @return 文件名称
     * @throws IOException 网络超时等异常
     * @description <p>获取 从网络请求中请求到的文件名称，实现委托给 {@link HttpUtils#getFileName(String)}；
     *     响应头中取不到时退回从 URL 截取 <br>
     */
    public static String getFileNameFromHttp(String url) throws IOException {
        String fileName = HttpUtils.getFileName(url);
        //如果从响应头中获取失败，则从URL中进行截取文件名称
        return StringUtils.isBlank(fileName) ? getFileNameFromUrl(url) : fileName;
    }

    /**
     * @param headers 响应头（如 {@code HttpURLConnection.getHeaderFields()} 的返回值）
     * @return 文件名称，响应头中不含文件名时为 {@code null}
     * @description <p>从响应头中获取请求到的文件名称，解析委托给 {@link HttpUtils#parseFileName(String, String)} <br>
     */
    public static String getFileNameFromHttp(Map<String, List<String>> headers) {
        String disposition = null;
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                // HTTP 头名大小写不敏感，服务端返回的大小写不固定
                if ("Content-Disposition".equalsIgnoreCase(entry.getKey())) {
                    List<String> values = entry.getValue();
                    if (values != null && !values.isEmpty()) {
                        disposition = values.get(0);
                    }
                    break;
                }
            }
        }
        String fileName = HttpUtils.parseFileName(disposition, null);
        log.debug("从响应头中解析到的文件名称为：{}", fileName);
        return StringUtils.isBlank(fileName) ? null : fileName;

    }

    /**
     * 从 URL 中截取文件名称。
     *
     * @param url http URL
     * @return 截取到的文件名称
     * @time 2017年3月19日 下午4:41:50
     */
    public static String getFileNameFromUrl(String url) {
        String fileName = "";
        //如果URL结尾不是文件名，而是相关参数，则截取?前的内容
        if (url.contains("?")) {
            url = url.substring(0, url.indexOf('?'));
        }

        //对URL进行解码处理
        try {
            fileName = URLDecoder.decode(url.substring(url.lastIndexOf('/') + 1), "utf-8");
        } catch (Exception e) {
            e.printStackTrace();
        }
        log.info("从URL中截取到的文件名：{}", fileName);
        return fileName;
    }

    /**
     * 从路径或 URL 中截取文件名。
     *
     * @param path 文件路径或以 {@code http} 开头的 URL
     * @return URL 形式时返回解码后的 URL 文件名，否则返回路径最后一级的文件名
     */
    public static String getFileNameFromPath(String path) {
        if (path.startsWith("http")) {
            return getFileNameFromUrl(path);
        }
        File file = new File(path);
        return file.getName();
    }

    /**
     * 将字符串形式的大小转换为长整型的大小值。根据字符串中的单位（如GB、MB、KB），将其转换为相应的大小值并返回。如果字符串为空，则返回0。
     *
     * @param size 文件大小字符串
     * @return 转换后的字节数
     * @since 1.4.4
     */
    public static long getSize(String size) {
        if (StringUtils.isBlank(size)) {
            return 0L;
        }
        long sizeNumber;
        if (size.contains("GB") || size.contains("gb") || size.contains("G") || size.contains("g")) {
            Double tmp = ConvertUtils.toDouble(size, 0.0);
            sizeNumber = (long) (tmp * GB);
        } else if (size.contains("MB") || size.contains("mb") || size.contains("M") || size.contains("m")) {
            Double tmp = ConvertUtils.toDouble(size, 0.0);
            sizeNumber = (long) (tmp * MB);
        } else if (size.contains("KB") || size.contains("kb") || size.contains("K") || size.contains("k")) {
            Double tmp = ConvertUtils.toDouble(size, 0.0);
            sizeNumber = (long) (tmp * KB);
        } else {
            sizeNumber = ConvertUtils.toLong(size, 0L);
        }

        return sizeNumber;
    }

    /**
     * 将字节数转换为带单位的可读文本
     *
     * <p>按 1024 进制依次使用 B、KB、MB、GB、TB；负数按绝对值换算后保留负号，
     * 因此 {@code getSize(-2048)} 得到 {@code "-2.0KB"}。
     * 小数点固定使用 {@link Locale#US} 的格式，不受运行环境默认区域设置影响。</p>
     *
     * @param byteSize 文件大小，单位为字节
     * @return 带单位的文件大小文本，如 {@code "1.5KB"}、{@code "2.00GB"}
     * @since 1.4.4
     */
    public static String getSize(long byteSize) {
        if (byteSize > -1024 && byteSize < 1024) {
            return byteSize + "B";
        }
        String sign = byteSize < 0 ? "-" : "";
        // 取绝对值后再分档，避免负数落到 B 档输出形如 "-2048B"
        double abs = Math.abs((double) byteSize);
        double kb = abs / 1024.0;
        if (kb < 1024) {
            return sign + formatSize(kb, 1) + "KB";
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return sign + formatSize(mb, 2) + "MB";
        }
        double gb = mb / 1024.0;
        if (gb < 1024) {
            return sign + formatSize(gb, 2) + "GB";
        }
        return sign + formatSize(gb / 1024.0, 2) + "TB";
    }

    /**
     * 按固定区域设置格式化文件大小的数值部分
     *
     * @param value 数值
     * @param scale 保留的小数位数
     * @return 格式化后的文本，小数点恒为 {@code .}
     */
    private static String formatSize(double value, int scale) {
        return String.format(Locale.US, "%." + scale + "f", value);
    }

    /**
     * 列出指定目录下的文件（不含子目录，不递归）。
     *
     * @param dirPath 目录路径
     * @return 目录下的文件数组；路径不存在时返回空数组，路径本身是文件时返回仅含该文件的数组
     */
    public static File[] listFiles(String dirPath) {
        return listFiles(new File(dirPath));
    }

    /**
     * 列出指定目录下的文件（不含子目录，不递归）。
     *
     * @param dirPath 目录
     * @return 目录下的文件数组；目录不存在时返回空数组，入参本身是文件时返回仅含该文件的数组
     */
    public static File[] listFiles(File dirPath) {
        return listFiles(dirPath, true);
    }

    /**
     * 列出指定目录下的一级子项，不递归。
     *
     * @param dirPath  目录
     * @param onlyFile {@code true} 时只返回文件，{@code false} 时同时返回子目录
     * @return 满足条件的数组；目录不存在时返回空数组，入参本身是文件时返回仅含该文件的数组
     */
    public static File[] listFiles(File dirPath, boolean onlyFile) {
        if (!dirPath.exists()) {
            return new File[0];
        }
        if (dirPath.isFile()) {
            return new File[]{dirPath};
        }
        File[] files = dirPath.listFiles(file -> {
            if (onlyFile) {
                return file.isFile();
            } else {
                return true;
            }
        });

        return files;
    }

    /**
     * Extract the filename from the given Java resource path,
     * e.g. {@code "mypath/myfile.txt" &rarr; "myfile.txt"}.
     *
     * @param path the file path (may be {@code null})
     * @return the extracted filename, or {@code null} if none
     */

    public static String getFilename(String path) {
        if (path == null) {
            return null;
        }

        int separatorIndex = path.lastIndexOf(FOLDER_SEPARATOR_CHAR);
        return (separatorIndex != -1 ? path.substring(separatorIndex + 1) : path);
    }

    /**
     * Extract the filename extension from the given Java resource path,
     * e.g. "mypath/myfile.txt" &rarr; "txt".
     *
     * @param path the file path (may be {@code null})
     * @return the extracted filename extension, or {@code null} if none
     */

    public static String getFilenameExtension(String path) {
        if (path == null) {
            return null;
        }

        int extIndex = path.lastIndexOf(EXTENSION_SEPARATOR);
        if (extIndex == -1) {
            return null;
        }

        int folderIndex = path.lastIndexOf(FOLDER_SEPARATOR_CHAR);
        if (folderIndex > extIndex) {
            return null;
        }

        return path.substring(extIndex + 1);
    }

    /**
     * Strip the filename extension from the given Java resource path,
     * e.g. "mypath/myfile.txt" &rarr; "mypath/myfile".
     *
     * @param path the file path
     * @return the path with stripped filename extension
     */
    public static String stripFilenameExtension(String path) {
        int extIndex = path.lastIndexOf(EXTENSION_SEPARATOR);
        if (extIndex == -1) {
            return path;
        }

        int folderIndex = path.lastIndexOf(FOLDER_SEPARATOR_CHAR);
        if (folderIndex > extIndex) {
            return path;
        }

        return path.substring(0, extIndex);
    }

    /**
     * Apply the given relative path to the given Java resource path,
     * assuming standard Java folder separation (i.e. "/" separators).
     *
     * @param path the path to start from (usually a full file path)
     * @param relativePath the relative path to apply
     * (relative to the full file path above)
     * @return the full file path that results from applying the relative path
     */
    public static String applyRelativePath(String path, String relativePath) {
        int separatorIndex = path.lastIndexOf(FOLDER_SEPARATOR_CHAR);
        if (separatorIndex != -1) {
            String newPath = path.substring(0, separatorIndex);
            if (!relativePath.startsWith(FOLDER_SEPARATOR)) {
                newPath += FOLDER_SEPARATOR_CHAR;
            }
            return newPath + relativePath;
        } else {
            return relativePath;
        }
    }

}
