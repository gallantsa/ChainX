package com.nageoffer.shortlink.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 短链接后管应用
 */
@SpringBootApplication
@EnableDiscoveryClient // 用于将应用程序注册为服务发现的客户端。
@EnableFeignClients("com.nageoffer.shortlink.admin.remote") // 用于启用Feign客户端功能。
@MapperScan("com.nageoffer.shortlink.admin.dao.mapper") // 用于扫描指定包下的Mapper接口，并注册到Spring容器中。
public class ShortLinkAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShortLinkAdminApplication.class, args);
    }
}
