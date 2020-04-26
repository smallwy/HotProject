package com.gameart.hotswap;

/**
 * 热更脚本。
 * 当脚本被重载成功后，会立即被执行一次。
 *
 * 热更脚本是一种预留类型，用于在热更中处理特殊情况。
 * 热更脚本可被用于处理任意逻辑。
 * 比如游戏服中的角色数据定时存储机制失效，需要手动将角色数据刷新到数据库，此时就可以通过脚本类型来处理。
 *
 * @author zhangfei
 */
public interface HotSwapScript {

    /**
     * 执行脚本逻辑。
     *
     * @throws Exception 在执行过程中可能会抛出此异常
     */
    void execute() throws Exception;

}
