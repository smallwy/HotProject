package com.gameart.hotswap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.List;

/**
 * 热更新代理。
 * 通过使用java代理的方式，对已经加载过的代码做动态更新操作。
 *
 */
public class HotSwapAgent {

    static Logger logger = LoggerFactory.getLogger(HotSwapAgent.class);

    private static Instrumentation instrumentation;

    /**
     * 如果在启动JVM时指定了-javaagent参数，JVM在初始化后将会调用此方法，然后调用应用程序的main方法。
     *
     * @param agentArgs 在命令行中传递的参数
     * @param inst      jvm传递的Instrumentation实例
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        HotSwapAgent.instrumentation = inst;
        logger.info("HotSwapAgent.premain has been invoked.");
    }

    /**
     * 如果是在在JVM启动之后，通过attach方式启动代理，将会调用此方法。
     *
     * @param agentArgs 传递给代理的参数
     * @param inst      jvm传递的Instrumentation实例
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        if ("hotswap".equals(agentArgs)) {
            logger.info("HotSwapAgent.agentmain has been invoked.");
            HotSwapManager.getInstance().reload();
        } else {
            logger.warn("unknown agentmain's agentArgs: {}.", agentArgs);
        }
    }

    /**
     * 同时对多个类进行重载。
     *
     * @param classNames 被重载的类名
     * @param classFiles 类对应的新的字节数组
     * @throws Throwable 重载类型出现错误时抛出此异常
     */
    static void reload(List<String> classNames, List<byte[]> classFiles) throws Throwable {
        int size = classNames.size();
        ClassDefinition[] definitions = new ClassDefinition[size];
        for (int i = 0; i < size; i++) {
            Class<?> clazz = Class.forName(classNames.get(i));
            ClassDefinition definition = new ClassDefinition(clazz, classFiles.get(i));
            definitions[i] = definition;
        }
        HotSwapAgent.instrumentation.redefineClasses(definitions);
    }

    /**
     * 对某个类进行重载。
     *
     * @param className 被重载的类名
     * @param classFile 类对应的新的字节数组
     * @throws Throwable 重载类型出现错误时抛出此异常
     */
    static void reload(String className, byte[] classFile) throws Throwable {
        Class<?> clazz = Class.forName(className);
        ClassDefinition definition = new ClassDefinition(clazz, classFile);
        HotSwapAgent.instrumentation.redefineClasses(definition);
    }

}
