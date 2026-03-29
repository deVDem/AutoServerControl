package ru.devdem.autoServerControl.utils;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;
import ru.devdem.autoServerControl.AutoServerControl;
import ru.devdem.autoServerControl.classes.configuredServer;
import ru.devdem.autoServerControl.functions.OfflineMode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ConfigsHandler {


    private final AutoServerControl plugin;
    private final Logger logger;
    private final Path dataDirectory;
    private final ProxyServer server;

    private final OfflineMode offlineModeclass;

    public ConfigsHandler(AutoServerControl plugin, Logger logger, Path dataDirectory, ProxyServer server) {
        this.plugin = plugin;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.server = server;
        offlineModeclass = OfflineMode.getInstance();
    }

    public void reloadConfigs() {
        createDefaultConfig();
        loadServers();
        loadOnlineUsers();
    }

    private void createDefaultConfig() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path serversPath = dataDirectory.resolve("servers.yml");
            if (!Files.exists(serversPath)) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("servers.yml")) {
                    Files.copy(in, serversPath);
                    logger.info("Создан servers.yml");
                }
            }

            Path usersPath = dataDirectory.resolve("onlineUsers.yml");
            if (!Files.exists(usersPath)) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("onlineUsers.yml")) {
                    Files.copy(in, usersPath);
                    logger.info("Создан onlineUsers.yml");
                }
            }
        } catch (Exception e) {
            logger.error("Ошибка создания конфига", e);
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

    private void loadOnlineUsers() {
        try {
            Path usersPath = dataDirectory.resolve("onlineUsers.yml");
            Set<String> onlineUsers = new HashSet<>();

            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(usersPath));
            List<String> usersSection = (List<String>) data.get("onlineUsers");

            if (usersSection != null) {
                onlineUsers.addAll(usersSection);
            }
            offlineModeclass.updateOnlineUsers(onlineUsers);
            logger.info("Загружено {} online users", onlineUsers.size());

        } catch (Exception e) {
            logger.error("Ошибка загрузки конфига с онлайн пользователями", e);
        }
    }

}
