package com.example;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * 独立于主程序的日志工具
 * 实现原理：单线程异步阻塞队列写入 + 占位符替换
 */
public class FileLogger {
    private static final String LOG_FILE = "app.log";
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB 自动重置
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static volatile boolean running = true;

    // 异步队列，防止日志写入影响业务性能
    private static final BlockingQueue<String> logQueue = new ArrayBlockingQueue<>(1000);

    static {
        Thread t = new Thread(FileLogger::processLog);
        t.setDaemon(true); // 守护线程，随程序结束而结束
        t.setName("FileLogger-Worker");
        t.start();

        Runtime.getRuntime().addShutdownHook(new Thread(FileLogger::shutdown));
    }

    public static void shutdown() {
        running = false;
        flush();
    }

    private static void enqueue(String level, String className, String format, Object... args) {
        try {
            Throwable t = null;
            // 提取最后一个异常参数
            if (args.length > 0 && args[args.length - 1] instanceof Throwable) {
                t = (Throwable) args[args.length - 1];
            }

            String msg = formatMessage(format, args);
            String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
            String threadName = Thread.currentThread().getName();

            StringBuilder fullMsg = new StringBuilder()
                    .append(timestamp).append(" [").append(threadName).append("]")
                    .append("[").append(className).append("]")
                    .append("[").append(level).append("]")
                    .append(" ").append(msg).append("\n");

            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                fullMsg.append(sw.toString());
            }

            // 放入队列，若队列满则放弃，防止 OOM
            logQueue.offer(fullMsg.toString());
        } catch (Exception e) {
            System.err.println("CoreLogger Error: " + e.getMessage());
        }
    }

    private static void processLog() {
        while (running || !logQueue.isEmpty()) {
            try {
                String logEntry = logQueue.take();
                System.out.print(logEntry);
                writeToFile(logEntry);
            } catch (Exception e) {
                // 消费者线程不能死
            }
        }
    }

    private static void checkFileSize() {
        File file = new File(LOG_FILE);
        if (file.exists() && file.length() > MAX_FILE_SIZE) {
            file.delete(); // 简单处理：超过大小直接物理删除，也可重命名为 .bak
        }
    }

    private static void writeToFile(String content) {
        try {
            checkFileSize();
            try (FileOutputStream fos = new FileOutputStream(LOG_FILE, true)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // 文件系统错误不应中断业务
        }
    }

    // 强制刷盘的方法
    private static void flush() {
        while (!logQueue.isEmpty()) {
            String entry = logQueue.poll();
            if (entry != null) {
                System.out.print(entry);
                writeToFile(entry);
            }
        }
    }

    /**
     * 实现类似 Logback 的 {} 替换逻辑
     */
    private static String formatMessage(String pattern, Object... args) {
        if (pattern == null || args == null || args.length == 0) return pattern;

        StringBuilder sb = new StringBuilder(pattern.length() + 50);
        int cursor = 0;
        for (Object arg : args) {
            int placeholderIdx = pattern.indexOf("{}", cursor);
            if (placeholderIdx == -1) break;

            sb.append(pattern, cursor, placeholderIdx);
            sb.append(arg);
            cursor = placeholderIdx + 2;
        }
        sb.append(pattern.substring(cursor));
        return sb.toString();
    }

    public static FileLogger getLogger(Class<?> clazz) {
        return new FileLogger(clazz.getCanonicalName());
    }

    private final String className;

    public FileLogger(String className) {
        this.className = className;
    }

    public void info(String format, Object... args) {
        enqueue("INFO", this.className, format, args);
    }

    public void warn(String format, Object... args) {
        enqueue("WARN", this.className, format, args);
    }

    public void error(String format, Object... args) {
        enqueue("ERROR", this.className, format, args);
    }
}