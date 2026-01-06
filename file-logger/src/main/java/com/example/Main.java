package com.example;

import java.io.IOException;

public class Main {
    private static final FileLogger log = FileLogger.getLogger(Main.class);

    private static void logs() {
        // 1. 普通文本
        log.info("Core JAR 已由自定义 ClassLoader 加载");

        // 2. 带参数格式化
        String taskId = "taskId";
        String userName = "userName";
        log.info("正在处理远程任务，任务ID: {}, 用户: {}", taskId, userName);

        // 3. 打印异常堆栈（只需放在最后一个参数）
        try {
            throw new RuntimeException("数据库连接超时");
        } catch (Exception e) {
            log.error("执行业务逻辑失败，错误原因: {}", e.getMessage(), e);
        }

        new Thread(() -> {
            log.info("log in new thread");
        }).start();
    }

    public static void main(String[] args) throws IOException {
        logs();

        DemoClass.print();
    }
}