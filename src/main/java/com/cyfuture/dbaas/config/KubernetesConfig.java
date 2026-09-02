package com.cyfuture.dbaas.config;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.KubeConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileReader;
import java.io.IOException;

@Configuration
@EnableConfigurationProperties(DatabaseProperties.class)
public class KubernetesConfig {
    @Bean
    ApiClient apiClient(DatabaseProperties properties) throws IOException {
        if (properties.getKubeconfig() == null || properties.getKubeconfig().isBlank()) {
            ApiClient client = Config.defaultClient();
            client.setReadTimeout(30_000);
            return client;
        }
        try (FileReader reader = new FileReader(properties.getKubeconfig())) {
            ApiClient client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(reader)).build();
            client.setReadTimeout(30_000);
            return client;
        }
    }
}
