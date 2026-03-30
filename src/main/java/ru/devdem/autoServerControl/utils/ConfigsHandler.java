package ru.devdem.autoServerControl.utils;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;
import ru.devdem.autoServerControl.AutoServerControl;
import ru.devdem.autoServerControl.classes.configuredServer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ConfigsHandler {


    private final AutoServerControl plugin;
    private final Logger logger;
    private final Path dataDirectory;
    private final ProxyServer server;

    public ConfigsHandler(AutoServerControl plugin, Logger logger, Path dataDirectory, ProxyServer server) {
        this.plugin = plugin;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.server = server;
    }

    public void reloadConfigs() {
        createDefaultConfig();
        loadServers();
        loadSQLConfig();
    }

    private void createDefaultConfig() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path configPath = dataDirectory.resolve("config.yml");
            if (!Files.exists(configPath)) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    Files.copy(in, configPath);
                    logger.info("Создан config.yml");
                }
            }

            Path serversPath = dataDirectory.resolve("servers.yml");
            if (!Files.exists(serversPath)) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("servers.yml")) {
                    Files.copy(in, serversPath);
                    logger.info("Создан servers.yml");
                }
            }
        } catch (Exception e) {
            logger.error("Ошибка создания конфига", e);
        }
    }

    public void loadSQLConfig() {
        try {
            Path configPath = dataDirectory.resolve("config.yml");

            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(configPath));
            Map<String, Object> mysql = (Map<String, Object>) data.get("mysql");

            String host = (String) mysql.getOrDefault("host", "127.0.0.1");
            int port = (int) mysql.getOrDefault("port", 3306);
            String database = (String) mysql.getOrDefault("database", "velocity_users");
            String username = (String) mysql.getOrDefault("username", "root");
            String password = (String) mysql.getOrDefault("password", "");
            boolean useSSL = (boolean) mysql.getOrDefault("useSSL", false);
            DatabaseManager.getInstance(host, port, database, username, password);
            logger.info("MySQL config загружен");


        } catch (Exception e) {
            logger.error("Ошибка загрузки config.yml: ", e);
        }
    }


    private void loadServers() {
        try {
            Path serverPath = dataDirectory.resolve("servers.yml");

            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(serverPath));
            Map<String, configuredServer> servers = new HashMap<>();
            Map<String, Map<String, Object>> serversSection = (Map<String, Map<String, Object>>) data.get("servers");

            for (String key : serversSection.keySet()) {

                Map<String, Object> s = serversSection.get(key);

                String ip = (String) s.get("ip");
                String service = (String) s.get("service");
                String display = (String) s.get("display");
                String user = (String) s.get("sshUser");
                String password = (String) s.get("sshPassword");
                List<String> aliasesList = (List<String>) s.get("aliases");
                Set<String> aliases = aliasesList == null ? new HashSet<>() : new HashSet<>(aliasesList);

                configuredServer serverObj = new configuredServer(server, ip, key, service, display, user, password, aliases);
                servers.put(key, serverObj);

                logger.info("Загружен сервер: {}", key);
            }

            ConnectionServerHandler.getInstance(plugin).updateServers(servers);

        } catch (Exception e) {
            logger.error("Ошибка загрузки конфига с серверами", e);
        }
    }

}
