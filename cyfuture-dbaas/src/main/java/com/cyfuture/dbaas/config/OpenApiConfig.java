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
                .description("""
                        Provision and manage PostgreSQL, MySQL and MongoDB databases through KubeBlocks.

                        Async create, scaling, restart, storage expansion, credential rotation and deletion return a small 202 Accepted body with operationId, databaseId, status, statusUrl and suggestedPollingIntervalSeconds. Poll statusUrl until terminal=true and status is SUCCEEDED or FAILED. Polling is read-only and never submits provisioning, rolls the shared gateway or changes allowlists.

                        Database health/status and operation status are separate. Use the database resource for health, and the operation resource for workflow progress and failure reasons.

                        Frontend JSON is intentionally small: database list/status JSON omits privateEndpoint, namespace, backend readiness counters and allowlist CIDRs; operation JSON omits project, stage and progress; connection JSON omits privateEndpoint and privateConnectionUri. Credentials are exposed only by the connection endpoint. Errors use code, message, fieldErrors and requestId.
                        """));
    }
}
