package com.crfd.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Content Recommendation Feedback Dashboard (CRFD).
 *
 * <p>Extends {@link SpringBootServletInitializer} so the very same code base can be packaged
 * as a WAR and deployed to an external Apache Tomcat when the {@code war} Maven profile is used,
 * while remaining a runnable fat JAR (embedded Tomcat) for local development, Docker and Nginx
 * fronted deployments.</p>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class DashboardApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(DashboardApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(DashboardApplication.class);
    }
}
