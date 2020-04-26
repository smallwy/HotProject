package com.gameart.watch;

/**
 * 文件(或目录)的变更类型。
 *
 * @author zhangfei
 */
public interface FileChangeType {

    /**
     * 未知的类型。
     */
    int UNKNOWN = 0;

    /**
     * 创建文件(或目录)。
     */
    int CREATE = 1;

    /**
     * 删除文件(或目录)。
     */
    int DELETE = 2;

    /**
     * 修改文件(或目录)。
     */
    int MODIFY = 4;

    /**
     * 添加或修改文件(或目录)。
     */
    int CREATE_OR_MODIFY = CREATE | MODIFY;

    /**
     * 创建、删除、修改文件(或目录)。
     */
    int ALL = CREATE | DELETE | MODIFY;

    /**
     * 判断源类型是否包含了目标类型。
     *
     * @param sourceTypes 源类型
     * @param targetType  目标类型
     * @return 如果包含了就返回true，否则返回false
     */
    static boolean contains(int sourceTypes, int targetType) {
        return (sourceTypes & targetType) == targetType;
    }

}
