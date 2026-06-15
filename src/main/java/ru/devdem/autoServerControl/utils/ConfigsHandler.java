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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Создает и загружает конфигурационные файлы плагина.
 */
public class ConfigsHandler {

    /**
     * Сообщения по умолчанию, которые используются, если messages.yml пустой или в нем нет ключа.
     */
    private static final Map<String, String> DEFAULT_MESSAGES = Map.of(
            "player-join", "§eИгрок §f{player}§e подключился на сервер!",
            "player-disconnect", "§eИгрок §f{player}§e отключился",
            "player-return-lobby", "§eИгрок §f{player}§e вернулся в лобби",
            "player-switch-server", "§eИгрок §f{player}§e отправился в {server}",
            "player-switch-unknown", "§eИгрок §f{player}§e перешел на {server}"
    );

    /** Главный класс плагина, нужен для передачи в ConnectionServerHandler. */
    private final AutoServerControl plugin;

    /** Логгер для ошибок загрузки и информационных сообщений. */
    private final Logger logger;

    /** Папка данных плагина, куда копируются YAML-файлы. */
    private final Path dataDirectory;

    /** Velocity proxy, нужен при создании объектов configuredServer. */
    private final ProxyServer server;

    /** Загруженные сообщения из messages.yml с fallback-значениями. */
    private final Map<String, String> messages = new ConcurrentHashMap<>();

    /**
     * Создает обработчик конфигов.
     */
    public ConfigsHandler(AutoServerControl plugin, Logger logger, Path dataDirectory, ProxyServer server) {
        this.plugin = plugin;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.server = server;
    }

    /**
     * Перечитывает все конфиги плагина.
     */
    public void reloadConfigs() {
        createDefaultConfig();
        loadServers();
        loadSQLConfig();
        loadMessages();
    }

    /**
     * Создает config.yml, servers.yml и messages.yml из ресурсов jar, если их еще нет.
     */
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

            Path messagesPath = dataDirectory.resolve("messages.yml");
            if (!Files.exists(messagesPath)) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("messages.yml")) {
                    Files.copy(in, messagesPath);
                    logger.info("Создан messages.yml");
                }
            }
        } catch (Exception e) {
            logger.error("Ошибка создания конфига", e);
        }
    }

    /**
     * Загружает параметры MySQL из config.yml и инициализирует DatabaseManager.
     */
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
            DatabaseManager.getInstance(host, port, database, username, password);
            logger.info("MySQL config загружен");


        } catch (Exception e) {
            logger.error("Ошибка загрузки config.yml: ", e);
        }
    }


    /**
     * Загружает список управляемых серверов из servers.yml.
     */
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

    /**
     * Возвращает сообщение по ключу и подставляет плейсхолдеры формата {name}.
     *
     * @param key ключ сообщения в messages.yml
     * @param placeholders значения для замены плейсхолдеров
     * @return готовая строка для отправки игрокам
     */
    public String getMessage(String key, Map<String, String> placeholders) {
        String message = messages.getOrDefault(key, DEFAULT_MESSAGES.getOrDefault(key, key));
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            message = message.replace("{" + placeholder.getKey() + "}", placeholder.getValue());
        }
        return message;
    }

    /**
     * Загружает messages.yml поверх DEFAULT_MESSAGES.
     */
    private void loadMessages() {
        try {
            Path messagesPath = dataDirectory.resolve("messages.yml");

            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.newInputStream(messagesPath));

            messages.clear();
            messages.putAll(DEFAULT_MESSAGES);
            if (data == null) {
                logger.warn("messages.yml пустой");
                return;
            }

            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if (entry.getValue() != null) {
                    messages.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }

            logger.info("messages.yml загружен");
        } catch (Exception e) {
            logger.error("Ошибка загрузки messages.yml", e);
        }
    }

}
