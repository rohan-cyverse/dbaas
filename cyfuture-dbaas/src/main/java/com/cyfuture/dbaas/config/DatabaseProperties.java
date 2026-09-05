package com.cyfuture.dbaas.config;

import com.cyfuture.dbaas.model.DatabaseEngine;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "dbaas")
@Getter
@Setter
public class DatabaseProperties {
    private String kubeconfig;
    private String namespacePrefix = "dbaas-p-";
    private String storageClass = "cinder-sc";
    private GatewaySettings gateway = new GatewaySettings();
    private EngineSettings postgresql = new EngineSettings();
    private EngineSettings mysql = new EngineSettings();
    private EngineSettings mongodb = new EngineSettings();

    public EngineSettings engine(DatabaseEngine engine) {
        return switch (engine) {
            case POSTGRESQL -> postgresql;
            case MYSQL -> mysql;
            case MONGODB -> mongodb;
        };
    }

    public Map<DatabaseEngine, List<String>> supportedVersions() {
        Map<DatabaseEngine, List<String>> result = new EnumMap<>(DatabaseEngine.class);
        for (DatabaseEngine engine : DatabaseEngine.values()) {
            result.put(engine, engine(engine).getVersions());
        }
        return result;
    }

    @Getter
    @Setter
    public static class EngineSettings {
        private String clusterDefinition;
        private String topology;
        private String componentName;
        private String credentialAccount;
        private String credentialImage;
        private List<String> versions = List.of();
    }

    @Getter
    @Setter
    public static class GatewaySettings {
        private String namespace = "dbaas-gateway";
        private String serviceName = "dbaas-public-gateway";
        private String deploymentName = "dbaas-public-gateway";
        private String configMapName = "dbaas-public-gateway-config";
        private int portStart = 31000;
        private int portEnd = 31009;
        private String publicHost;
    }
}
