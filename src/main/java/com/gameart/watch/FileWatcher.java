package com.gameart.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 文件(或目录)监视器，当被监视的文件(或目录)发生改变时，会触发相应的回调处理。
 *
 * @author zhangfei
 */
class FileWatcher {

    private static Logger logger = LoggerFactory.getLogger(FileWatcher.class);

    /**
     * 默认的通知间隔次数。
     */
    static final int DEFAULT_NOTIFY_INTERVAL = 3;

    /**
     * 通知的间隔次数，也就是每多少次等待能触发一次通知。
     */
    private final int notifyInterval;

    /**
     * 通知的等待次数，当等待次数达到一定数目后会触发通知。
     */
    private int notifyWaitTimes;

    /**
     * 被监视文件(或目录)的路径。
     *
     * @see #watchPath
     */
    private String filePath;

    /**
     * 如果被监视的路径是一个普通文件，那么这个字段存的就是文件名；否则它为null。
     */
    private String filename;

    /**
     * 被监视目录的路径，用于解析文件路径。
     */
    private Path watchPath;

    /**
     * 用于监视文件(或目录)变化的服务。
     */
    private WatchService watchService;

    /**
     * 监视的变化类型。
     */
    private final int watchTypes;

    /**
     * 文件(或目录)变化时的回调处理。
     */
    private final FileChangeCallback callback;

    /**
     * 当前正在变化的文件信息，key为文件全路径，value为变化类型。
     */
    private final HashMap<String, Integer> fileChanges = new HashMap<>();

    /**
     * 初始化。
     *
     * @return 如果初始化成功就返回true，否则返回false
     */
    public boolean init() {
        File file = new File(this.filePath);
        if (!file.exists()) {
            logger.error("文件监视器初始化失败，路径[{}]不存在", this.filePath);
            return false;
        }

        // 被监视的路径可能是一个文件，也可能是一个目录，这里需要作出区分。
        String dir;
        if (file.isFile()) {
            this.filename = file.getName();
            dir = file.getParentFile().getPath();
        } else {
            dir = this.filePath;
        }

        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            this.watchPath = Paths.get(dir);

            WatchEvent.Kind[] kinds = getEventKinds(watchTypes);
            if (kinds.length == 0) {
                logger.error("文件监视器初始化失败，路径[{}]监听的变化类型无效", this.filePath);
                return false;
            }

            watchPath.register(this.watchService, kinds);
        } catch (IOException e) {
            logger.error("文件监视器初始化失败，路径[{}]注册失败", this.filePath, e);
            return false;
        }

        return true;
    }

    /**
     * 根据变化类型获取对应的事件种类。
     *
     * @param watchTypes 被监视的变化类型
     * @return 对应的事件种类
     */
    private static WatchEvent.Kind[] getEventKinds(int watchTypes) {
        ArrayList<WatchEvent.Kind<Path>> kinds = new ArrayList<>(3);

        if (FileChangeType.contains(watchTypes, FileChangeType.CREATE)) {
            kinds.add(StandardWatchEventKinds.ENTRY_CREATE);
        }

        if (FileChangeType.contains(watchTypes, FileChangeType.DELETE)) {
            kinds.add(StandardWatchEventKinds.ENTRY_DELETE);
        }

        if (FileChangeType.contains(watchTypes, FileChangeType.MODIFY)) {
            kinds.add(StandardWatchEventKinds.ENTRY_MODIFY);
        }

        int size = kinds.size();
        return kinds.toArray(new WatchEvent.Kind[size]);
    }

    /**
     * 更新监视器的内部状态。
     */
    void tick() {
        checkEvents();
        notifyFileChanges();
    }

    /**
     * 检查文件被改变的事件。
     */
    private void checkEvents() {
        WatchKey key = this.watchService.poll();
        if (key == null) {
            return;
        }

        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                continue;
            }

            // 获取文件(或目录)的变化类型。
            int changeType;
            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                changeType = FileChangeType.CREATE;
            } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                changeType = FileChangeType.DELETE;
            } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                changeType = FileChangeType.MODIFY;
            } else {
                continue;
            }

            // 检查变化类型是否属于被监视的类型。
            boolean contains = FileChangeType.contains(this.watchTypes, changeType);
            if (!contains) {
                continue;
            }

            // 收集变化的文件(或目录)信息。
            @SuppressWarnings("unchecked")
            WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
            Path changed = pathEvent.context();
            Path absolute = this.watchPath.resolve(changed);
            File file = absolute.toFile();

            // 如果被监视的路径是一个文件名，需要检查变化的文件是否就是这个文件。
            // 比如监视的是a.txt，结果b.txt发生了变化，那么就需要忽略这个变化。
            if ((this.filename != null) && !file.getName().equals(this.filename)) {
                continue;
            }

            // 有时一个文件被修改后，有可能会收到这个文件的1~3个修改事件，这种情况需要被处理。
            // 在这里通过判断文件是否可以被重命名来处理这种情况。
            if ((changeType == FileChangeType.CREATE) || (changeType == FileChangeType.MODIFY)) {
                // 如果文件重命名失败，说明文件正在被写入，此时不允许触发通知，因为这时读取文件会出错。
                boolean success = file.renameTo(file);
                if (!success) {
                    continue;
                }
            }

            // 合并发生变化的文件信息，以减少回调处理的调用次数。
            String filePath = file.getPath();
            this.fileChanges.put(filePath, changeType);
            logger.info("监控到文件[{}]发生变化[{}]", filePath, changeType);
        }

        key.reset();
    }

    /**
     * 将发生变化的文件(或目录)进行通知。
     */
    private void notifyFileChanges() {
        if (this.fileChanges.size() <= 0) {
            return;
        }

        // 当等待次数没有达到通知的间隔次数时，等待checkEvents方法做文件变化信息的合并操作。
        // 通过合并不同文件的变化信息，减少回调处理的调用次数。
        // 这里对合并同一个文件的变化信息，也起到了一定的作用。
        if (this.notifyWaitTimes < this.notifyInterval) {
            this.notifyWaitTimes++;
            return;
        }

        // 等待次数达到通知的间隔次数，将所有发生变化的文件(或目录)进行回调处理。
        this.notifyWaitTimes = 0;
        Map<String, Integer> copy = new HashMap<>(this.fileChanges);
        this.fileChanges.clear();

        try {
            callback.process(copy);
        } catch (Exception e) {
            logger.error("文件监视器回调处理时出现异常，监听的路径是[{}]", this.filePath, e);
        }
    }

    /**
     * @param filePath       被监视的文件(或目录)的全路径
     * @param watchTypes     监视的变化类型
     * @param callback       回调处理
     * @param notifyInterval 通知的间隔次数
     */
    FileWatcher(String filePath, int watchTypes, FileChangeCallback callback, int notifyInterval) {
        this.filePath = filePath;
        this.watchTypes = watchTypes;
        this.callback = callback;
        this.notifyInterval = notifyInterval;
    }

    @Override
    public String toString() {
        return "FileWatcher{" +
                "filePath='" + filePath + '\'' +
                ", watchTypes=" + watchTypes +
                '}';
    }

}
