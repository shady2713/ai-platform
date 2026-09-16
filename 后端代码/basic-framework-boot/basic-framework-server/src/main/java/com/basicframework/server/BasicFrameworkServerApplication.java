package com.basicframework.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 项目的启动类
 *
 */
@SuppressWarnings("SpringComponentScan") // 忽略 IDEA 无法识别 ${basic-framework.info.base-package}
@SpringBootApplication(
        scanBasePackages = {"${basic-framework.info.base-package}.server", "${basic-framework.info.base-package}.module"
        })
public class BasicFrameworkServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BasicFrameworkServerApplication.class, args);
    }
}
