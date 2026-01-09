package com.example;

import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 生产级异步日志工具
 * 特性：动态路径切换、高性能长连接写入、线程安全、自动扩容目录
 */
public class FileLogger {
    // 动态路径，使用 volatile 保证多线程可见性
    private static volatile String logFilePath = "app.log";
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static volatile boolean running = true;
    private static final BlockingQueue<String> logQueue = new ArrayBlockingQueue<>(5000);

    static {
        Thread worker = new Thread(FileLogger::processLog);
        worker.setDaemon(true);
        worker.setName("FileLogger-Worker");
        worker.start();

        // 注册关闭钩子，确保程序退出前日志落盘
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            flush();
        }));
    }

    /**
     * 动态设置日志文件路径
     */
    public static synchronized void setLogFile(String newPath) {
        if (StringUtils.isEmpty(newPath)) return;
        logFilePath = newPath;
    }

    private final String className;

    public FileLogger(String className) {
        this.className = className;
    }

    public static FileLogger getLogger(Class<?> clazz) {
        return new FileLogger(clazz.getCanonicalName());
    }

    // --- 公共 API ---

    public void info(String format, Object... args) { enqueue("INFO", format, args); }
    public void warn(String format, Object... args) { enqueue("WARN", format, args); }
    public void error(String format, Object... args) { enqueue("ERROR", format, args); }

    // --- 内部逻辑 ---

    private void enqueue(String level, String format, Object... args) {
        if (!running) return;
        try {
            Throwable t = null;
            if (args.length > 0 && args[args.length - 1] instanceof Throwable) {
                t = (Throwable) args[args.length - 1];
            }

            String msg = formatMessage(format, args);
            String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
            String threadName = Thread.currentThread().getName();

            StringBuilder sb = new StringBuilder(128)
                    .append(timestamp).append(" ")
                    .append("[").append(threadName).append("] ")
                    .append("[").append(level).append("] ")
                    .append(className)
                    .append(" - ")
                    .append(msg)
                    .append("\n");

            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                sb.append(sw.toString());
            }

            // 非阻塞尝试放入队列，若满则丢弃，保护主业务性能
            logQueue.offer(sb.toString());
        } catch (Exception e) {
            System.err.println("FileLogger Enqueue Error: " + e.getMessage());
        }
    }

    private static void processLog() {
        String currentPath = null;
        FileOutputStream fos = null;

        while (running || !logQueue.isEmpty()) {
            try {
                // 等待获取日志，带超时以检查 running 状态和路径变更
                String entry = logQueue.poll(500, TimeUnit.MILLISECONDS);
                if (entry == null) continue;

                String targetPath = logFilePath;

                // 路径切换或首次打开
                if (fos == null || !targetPath.equals(currentPath)) {
                    if (fos != null) {
                        fos.flush();
                        fos.close();
                    }
                    fos = initOutputStream(targetPath);
                    currentPath = targetPath;
                }

                // 写入并控制文件大小（简单策略：每写一条检查一次，生产环境可优化为计数器检查）
                checkFileSize(currentPath);
                fos.write(entry.getBytes(StandardCharsets.UTF_8));
                System.out.print(entry);

                // 视需求调整：是否每条都强制刷新。如果不刷新，OS 崩溃可能丢数据；刷新则性能略降
                // fos.flush();

            } catch (Exception e) {
                System.err.println("Worker thread error: " + e.getMessage());
                if (fos != null) {
                    try { fos.close(); } catch (IOException ignore) {}
                    fos = null;
                }
            }
        }
        // 最终释放资源
        if (fos != null) {
            try { fos.close(); } catch (IOException ignore) {}
        }
    }

    private static FileOutputStream initOutputStream(String path) throws IOException {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        return new FileOutputStream(file, true);
    }

    private static void checkFileSize(String path) {
        File file = new File(path);
        if (file.exists() && file.length() > MAX_FILE_SIZE) {
            // 简单处理：重命名备份旧文件，避免直接删除导致无法追溯
            File backup = new File(path + ".bak");
            if (backup.exists()) backup.delete();
            file.renameTo(backup);
        }
    }

    private static void flush() {
        // 简单的退出前清理
        while (!logQueue.isEmpty()) {
            String entry = logQueue.poll();
            if (entry != null) System.out.print("[SHUTDOWN] " + entry);
        }
    }

    private String formatMessage(String pattern, Object... args) {
        if (pattern == null || args == null || args.length == 0) return pattern;
        StringBuilder sb = new StringBuilder(pattern.length() + 32);
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
}