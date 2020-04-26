package com.gameart.watch;

import java.util.Map;

/**
 * 文件(或目录)发生变化后的回调处理器。
 *
 * @author zhangfei
 */
@FunctionalInterface
public interface FileChangeCallback {

    /**
     * 回调处理。<br/>
     *
     * <b>注意：这个回调处理是在统一的文件监视调度线程中被调用，耗时过长的逻辑处理会影响到其它的文件监视器调度。</b>
     *
     * @param fileChanges 发生变化的文件信息，key为文件全路径，value为变化类型
     */
    void process(Map<String, Integer> fileChanges);

}
