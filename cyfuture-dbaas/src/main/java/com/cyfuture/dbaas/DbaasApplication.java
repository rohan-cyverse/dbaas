package com.cyfuture.dbaas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class DbaasApplication extends SpringBootServletInitializer {
    private static final Pattern ENV_KEY = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    public static void main(String[] args) {
        loadLocalEnv();
        SpringApplication.run(DbaasApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(DbaasApplication.class);
    }

    private static void loadLocalEnv() {
        Path envFile = Path.of(".env");
        if (!Files.isRegularFile(envFile)) return;
        try {
            for (String rawLine : Files.readAllLines(envFile)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int separator = line.indexOf('=');
                if (separator <= 0) continue;
                String key = line.substring(0, separator).trim();
                if (!ENV_KEY.matcher(key).matches() || System.getenv(key) != null
                        || System.getProperty(key) != null) {
                    continue;
                }
                String value = line.substring(separator + 1).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                System.setProperty(key, value);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read local .env file", exception);
        }
    }
}
