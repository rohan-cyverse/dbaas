package com.cyfuture.dbaas.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI dbaasOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Cyfuture DBaaS Provisioning API")
                .version("1.0")
                .description("Provision and manage PostgreSQL, MySQL and MongoDB through KubeBlocks, "
                        + "including asynchronous full backup, scheduled backup, retention, and restore APIs. "
                        + "PITR and incremental backups are intentionally unavailable in this version."));
    }
}
