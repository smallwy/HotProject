package com.gameart.hotswap;

import com.gameart.utils.watch.FileChangeType;
import com.gameart.utils.watch.FileWatchManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 热更管理器，对部分代码进行动态更新。
 */
public class HotSwapManager {

    /**
     * 存放需要被热更类名的文件。
     */
    private static final String HOT_SWAP_FILENAME = "hotswap.txt";

    /**
     * 用于分隔多个class的分隔符。
     */
    private static final String MULTIPLE_CLASS_SEPARATOR = ";";

    /**
     * 此类唯一实例。
     */
    private static final HotSwapManager INSTANCE = new HotSwapManager();

    private static Logger logger = LoggerFactory.getLogger(HotSwapManager.class);

    /**
     * 每次成功重载的类型。
     */
    private final List<Class<?>> reloadedClasses = new ArrayList<>();

    /**
     * 用于监控热更文件是否被修改。
     */
    private WatchService watcher;

    /**
     * 是否需要执行重载操作。
     */
    private boolean needReload;

    /**
     * 服务方法{@link #execute()}被执行的频率太快了，使用手段降低实际方法体被执行的频率。
     * 每次tick自增，一秒大约tick次数为30。
     */
    private int tickTimes;

    /**
     * 重载倒计时，当值为0时开始执行自动重载操作。
     */
    private int reloadCountDown = 10;

    /**
     * 重载特定的类。
     */
    public synchronized void reload() {
        this.reloadedClasses.clear();

        // 读取热更配置文件，获取需要执行热更的类名。
        List<String> configLines = getHotSwapConfig(HOT_SWAP_FILENAME);
        if (configLines == null) {
            logger.error("热更失败，读取热更配置文件[{}]失败", HOT_SWAP_FILENAME);
            return;
        }

        if (configLines.isEmpty()) {
            logger.error("热更失败，读取热更配置文件[{}]时没有找到有效的类名信息", HOT_SWAP_FILENAME);
            return;
        }

        boolean enable = false;
        Iterator<String> iterator = configLines.iterator();
        while (iterator.hasNext()) {
            String str = iterator.next();
            if (str.startsWith("switch=")) {
                iterator.remove();
                if (str.equals("switch=on")) {
                    enable = true;
                    break;
                }
            }
        }

        if (!enable) {
            logger.warn("热更失败，读取热更配置文件[{}]时发现热更开关没有打开", HOT_SWAP_FILENAME);
            return;
        }

        // 根据类名对类型进行重载。
        for (String className : configLines) {
            if (className.contains(MULTIPLE_CLASS_SEPARATOR)) {
                /*
                 * 一行包含多个类名，需要同时重载多个类。
                 * 比如这种形式：com.gameart.hotswap.HotSwapExample;com.gameart.hotswap.HotSwapExample$Inner
                 */
                String[] arr = className.split(MULTIPLE_CLASS_SEPARATOR);
                batchHotSwap(arr);
            } else {
                // 一行只包含一个类名，只需要重载一个类。
                singleHotSwap(className);
            }
        }

        // 如果被重载成功的类型中有热更脚本类型，需要执行热更脚本类型。
        for (Class<?> clazz : this.reloadedClasses) {
            if (HotSwapScript.class.isAssignableFrom(clazz)) {
                try {
                    HotSwapScript script = (HotSwapScript) clazz.newInstance();
                    Method method = clazz.getDeclaredMethod("execute");
                    method.invoke(script);
                    logger.info("执行热更脚本[{}]成功", clazz.getName());
                } catch (Throwable t) {
                    logger.error("执行热更脚本[{}]失败", clazz.getName(), t);
                }
            }
        }
    }

    /**
     * 单次热更。
     * 每次只热更一个类型。
     *
     * @param className 被热更的类名
     */
    private void singleHotSwap(String className) {
        Class<?> clazz;
        try {
            // 如果一个类在JVM启动时不在jar包里面，在JVM启动后又加入到jar包里面，
            // 在这种情况下，class.forName(className)是会失败的。
            // 但是如果一个类是在classpath下的普通目录里，不会存在上面的问题。
            clazz = Class.forName(className);
        } catch (ClassNotFoundException e) {
            logger.error("单次热更失败，没有找到类型[{}]", className, e);
            return;
        }

        byte[] classFile = getClassFile(clazz);
        if (classFile == null) {
            logger.error("单次热更失败，没有找到类型[{}]的classfile", className);
            return;
        }

        try {
            HotSwapAgent.reload(className, classFile);
            this.reloadedClasses.add(clazz);
            logger.info("单次热更成功，类型[{}]", className);
        } catch (Throwable throwable) {
            logger.info("单次热更失败，重载类型[{}]时出现系统错误", className, throwable);
        }
    }

    /**
     * 批量热更。
     * 一次热更多个类型，要么同时热更成功，要么同时热更失败。
     *
     * @param classNames 被热更的多个类名
     */
    private void batchHotSwap(String[] classNames) {
        ArrayList<String> classNameList = new ArrayList<>(classNames.length);
        ArrayList<byte[]> classFileList = new ArrayList<>(classNames.length);

        ArrayList<Class<?>> classList = new ArrayList<>();
        boolean success = true;
        for (int i = 0; i < classNames.length; i++) {
            String className = classNames[i].trim();
            Class<?> clazz;
            try {
                clazz = Class.forName(className);
                classList.add(clazz);
            } catch (ClassNotFoundException e) {
                logger.error("批量热更失败，没有找到类型列表{}中的第[{}]个类型", Arrays.toString(classNames), i + 1);
                success = false;
                break;
            }

            byte[] classFile = getClassFile(clazz);
            if (classFile == null) {
                success = false;
                logger.error("批量热更失败，没有找到类型列表{}中的第[{}]个类型的classfile", Arrays.toString(classNames), i + 1);
                break;
            } else {
                classFileList.add(classFile);
                classNameList.add(className);
            }
        }

        if (success) {
            try {
                HotSwapAgent.reload(classNameList, classFileList);
                this.reloadedClasses.addAll(classList);
                logger.info("批量热更成功，类型{}", Arrays.toString(classNames));
            } catch (Throwable throwable) {
                logger.info("批量热更失败，重载类型列表{}时出现系统错误", Arrays.toString(classNames), throwable);
            }
        }
    }

    /**
     * 获取某个类型对应的字节数组。
     *
     * @param clazz 类型
     * @return 对应的字节数组，如果获取失败就返回null
     */
    private byte[] getClassFile(Class<?> clazz) {
        /*
         * 必须要通过此方式来获取class对应的文件。
         * 使用ClassLoader.getResource()的方式，在遇到成员类是行不通的，因为class文件都是存放在jar包中。
         * 每次在成员类或外部类的代码中额外添加、删除一些多余字节，使用getResource()都会导致读取不到。
         */
        String path = clazz.getProtectionDomain().getCodeSource().getLocation().getPath();
        String filename = clazz.getName().replaceAll("\\.", "/") + ".class";

        byte[] classFile;
        if (path.endsWith(".jar")) {
            classFile = readBytesFromJar(path, filename);
        } else {
            File file = new File(path + filename);
            classFile = readBytesFromFile(file);
        }
        return classFile;
    }

    /**
     * 从普通文件中读取所有字节。
     *
     * @param file 被读取文件
     * @return 返回文件包含的字节数组，如果读取失败就返回null
     */
    private static byte[] readBytesFromFile(File file) {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            int length = (int) file.length();
            byte[] data = new byte[length];
            dis.readFully(data);
            return data;
        } catch (IOException e) {
            logger.error("从普通文件[{}]中读取字节内容时出现错误", file.getPath(), e);
            return null;
        }
    }

    /**
     * 从jar文件中读取某个文件项对应的字节内容。
     *
     * @param path      jar文件的路径
     * @param entryName 文件项名称
     * @return 返回文件项包含的字节数组，如果读取失败就返回null
     */
    private static byte[] readBytesFromJar(String path, String entryName) {
        try (JarFile jarFile = new JarFile(path)) {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry == null) {
                return null;
            }

            int length = (int) entry.getSize();
            InputStream is = jarFile.getInputStream(entry);
            DataInputStream dis = new DataInputStream(is);
            byte[] data = new byte[length];
            dis.readFully(data);
            return data;
        } catch (IOException e) {
            logger.error("从jar文件[{}]中读取某一项[{}]的字节内容时出现错误", path, entryName, e);
            return null;
        }
    }

    /**
     * 从文件中读取配置信息。
     *
     * @param filename 热更配置文件名称
     * @return 包含配置信息的列表
     */
    private List<String> getHotSwapConfig(String filename) {
        URL url = Thread.currentThread().getContextClassLoader().getResource(filename);
        if (url == null) {
            return null;
        }

        ArrayList<String> lines = new ArrayList<>();
        File file = new File(url.getFile());
        try (
                InputStreamReader isr = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(isr)
        ) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                // 以#开头的行被认为是注释行，直接忽略。
                if (line.startsWith("#")) {
                    continue;
                }

                lines.add(line);
            }
        } catch (IOException e) {
            logger.error("读取热更配置文件[{}]时出现错误", filename, e);
            return null;
        }

        return lines;
    }

    /**
     * 启动热更服务。
     *
     * @return 如果操作成功就返回true，否则返回false
     */
    public boolean start() {
        URL url = Thread.currentThread().getContextClassLoader().getResource(HOT_SWAP_FILENAME);
        if (url == null) {
            logger.warn("热更服务启动失败，热更配置文件[{}]不存在", HOT_SWAP_FILENAME);
            return false;
        }

        String filePath;
        try {
            File file = new File(url.toURI());
            filePath = file.getPath();
        } catch (URISyntaxException e) {
            logger.warn("热更服务启动失败，获取热更配置文件[{}]路径出现错误", HOT_SWAP_FILENAME, e);
            return false;
        }

        System.out.println(filePath);
        FileWatchManager.register(filePath, FileChangeType.CREATE_OR_MODIFY, fileChanges -> reload());
        logger.info("热更服务启动成功，读取的热更配置文件路径为[{}]", filePath);
        return true;
    }

    /**
     * @return the INSTANCE
     */
    public static HotSwapManager getInstance() {
        return HotSwapManager.INSTANCE;
    }

    /**
     * Private default constructor.
     */
    private HotSwapManager() {
    }

}
