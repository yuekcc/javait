package com.example;

import lombok.CustomLog;

@CustomLog
public class DemoClass {
    public static void print() {
        log.info("hello, {}!", "world");
    }
}
