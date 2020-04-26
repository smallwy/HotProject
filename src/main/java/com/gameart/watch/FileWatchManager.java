package com.gameart.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件监视管理器。
 * 提供统一的接口用于注册、撤销对文件(或目录)的监视。
 *
 * @author zhangfei
 */
public class FileWatchManager {

    private static Logger logger = LoggerFactory.getLogger(FileWatchManager.class);

    /**
     * 存放所有被管理的文件(或目录)监视器，key为被监视的全路径，value为对应的监视器。
     */
    private static Map<String, FileWatcher> watcherMap = new ConcurrentHashMap<>();

    /**
     * 注册文件监视。
     *
     * @param path       被监视文件(或目录)的全路径
     * @param watchTypes 监视的变化类型
     * @param callback   文件(或目录)变化时的回调处理
     * @param notifyInterval 文件(或目录)变化后触发回调处理的最大延迟时间，这个时间是调用{@link #tick()}方法的周期时间的倍数
     * @return 如果注册成功就返回true，否则返回false
     */
    public synchronized static boolean register(String path, int watchTypes, FileChangeCallback callback,
                                                int notifyInterval) {
        if (FileWatchManager.watcherMap.containsKey(path)) {
            logger.error("重复注册文件监视路径[{}]", path);
            return false;
        }

        FileWatcher fileWatcher = new FileWatcher(path, watchTypes, callback, notifyInterval);
        boolean success = fileWatcher.init();
        if (!success) {
            return false;
        }

        FileWatchManager.watcherMap.putIfAbsent(path, fileWatcher);
        return true;
    }

    /**
     * 注册文件监视。
     *
     * @param path       被监视文件(或目录)的全路径
     * @param watchTypes 监视的变化类型
     * @param callback   文件(或目录)变化时的回调处理
     * @return 如果注册成功就返回true，否则返回false
     */
    public synchronized static boolean register(String path, int watchTypes, FileChangeCallback callback) {
        return register(path, watchTypes, callback, FileWatcher.DEFAULT_NOTIFY_INTERVAL);
    }

    /**
     * 撤销文件监视。
     *
     * @param path 被监视文件(或目录)的全路径
     * @return 撤销成功就返回true，否则返回false
     */
    public synchronized static boolean deregister(String path) {
        FileWatcher fileWatcher = FileWatchManager.watcherMap.remove(path);
        return (fileWatcher != null);
    }

    /**
     * 更新所有文件监视器的状态。
     * 此方法需要周期调度，用于驱动文件监视器的状态更新。
     */
    public static void tick() {
        FileWatchManager.watcherMap.values().forEach(FileWatcher::tick);
    }

}
