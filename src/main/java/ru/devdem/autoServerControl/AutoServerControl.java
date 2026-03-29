package ru.devdem.autoServerControl;

import com.google.inject.Inject;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;
import ru.devdem.autoServerControl.classes.configuredServer;
import ru.devdem.autoServerControl.commands.ReloadCommand;
import ru.devdem.autoServerControl.functions.OfflineMode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(id = "autoservercontrol", name = "AutoServerControl", version = BuildConstants.VERSION, description = "Only for deVDem MC HUB", url = "devdem.ru/mc-hub", authors = {"deVDem"})
public class AutoServerControl {

    /*
     * Известные баги.
     *
     * Было бы круче, чтобы после захода на сервер был титульник "Добро пожаловать в {serverName}" (можно использовать из serverTexts)
     *
     * */

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final AutoServerControl pluginClass;

    private final Set<String> startingServers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> connectingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> startingPlayers = ConcurrentHashMap.newKeySet();

    private final Map<String, configuredServer> servers = new HashMap<>();

    public Logger getLogger() {
        return logger;
    }

    public OfflineMode offlineModeClass;

    @Inject
    public AutoServerControl(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        pluginClass = this;
        offlineModeClass = OfflineMode.getInstance(pluginClass);
    }

    // =========================
    // ИНИЦИАЛИЗАЦИЯ
    // =========================
    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        servers.clear();
        reloadConfigs();
        registerCommands();
        logger.info("AutoServerControl загружен!");
    }

    private void registerCommands() {
        CommandManager manager = server.getCommandManager();
        manager.register(manager.metaBuilder("ascreload")
                        .aliases("areload")
                        .build(),
                new ReloadCommand(this)
        );
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

            Map<String, Map<String, Object>> serversSection = (Map<String, Map<String, Object>>) data.get("servers");

            for (String key : serversSection.keySet()) {

                Map<String, Object> s = serversSection.get(key);

                String ip = (String) s.get("ip");
                String service = (String) s.get("service");
                String display = (String) s.get("display");
                String user = (String) s.get("sshUser");
                String password = (String) s.get("sshPassword");

                configuredServer serverObj = new configuredServer(server, ip, key, service, display, user, password);

                servers.put(key, serverObj);

                logger.info("Загружен сервер: {}", key);
            }

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
            offlineModeClass.updateOnlineUsers(onlineUsers);
            logger.info("Загружено {} online users", onlineUsers.size());

        } catch (Exception e) {
            logger.error("Ошибка загрузки конфига с онлайн пользователями", e);
        }
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        offlineModeClass.onPreLogin(event);
    }

    @Subscribe
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        offlineModeClass.onGameProfileRequest(event);
    }

    @Subscribe
    public void onPostLoginEvent(PostLoginEvent event) {
        Player player = event.getPlayer();
        Component broadcastMsg = Component.text("§eИгрок §f" + player.getUsername() + "§e подключился на сервер!");
        server.getAllPlayers().forEach(p -> p.sendMessage(broadcastMsg));
    }

    @Subscribe
    public void onDisconnectEvent(DisconnectEvent event) {
        Player player = event.getPlayer();
        Component broadcastMsg = Component.text("§eИгрок §f" + player.getUsername() + "§e отключился");
        server.getAllPlayers().forEach(p -> p.sendMessage(broadcastMsg));
        checkAllServers();
    }

    private void checkAllServers() {
        for (configuredServer srv : servers.values()) {
            server.getServer(srv.name).ifPresent(registeredServer -> {
                int players = registeredServer.getPlayersConnected().size();
                if (players == 0) {
                    srv.scheduleShutdown(this);
                } else {
                    srv.cancelShutdown();
                }
            });
        }
    }

    @Subscribe
    public void onServerPostConnectedEvent(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        Optional<ServerConnection> newServer = player.getCurrentServer();
        String serverName;
        if (newServer.isPresent()) serverName = newServer.get().getServerInfo().getName();
        else return;
        if (event.getPreviousServer() == null) {
            return;
        }
        configuredServer current = servers.get(serverName);
        Component broadcastMsg;
        if (serverName.equalsIgnoreCase("lobby")) {
            broadcastMsg = Component.text("§eИгрок §f" + player.getUsername() + "§e вернулся в лобби");
        } else {
            broadcastMsg = Component.text("§eИгрок §f" + player.getUsername() + "§e отправился в " + current.displayName);
        }
        server.getAllPlayers().forEach(p -> p.sendMessage(broadcastMsg));
        // === ИГРОК УШЁЛ С СЕРВЕРА ===
        if (event.getPreviousServer() != null) {
            String prevName = event.getPreviousServer().getServerInfo().getName();

            configuredServer prev = servers.get(prevName);

            if (prev != null) {
                RegisteredServer srv = event.getPreviousServer();
                if (srv.getPlayersConnected().isEmpty()) {
                    prev.scheduleShutdown(this);
                    logger.info("Запускаем таймер на {}", prev.name);
                }
            }
        }

        // === ИГРОК ЗАШЁЛ НА СЕРВЕР ===
        event.getPlayer().getCurrentServer().ifPresent(srv -> {
            String name = srv.getServerInfo().getName();

            if (current != null) {
                current.cancelShutdown();
                logger.info("Отмена выключения сервера: {}", name);
            }
        });

    }

    // =========================
    // ПОДКЛЮЧЕНИЕ К СЕРВЕРУ
    // =========================
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {

        Player player = event.getPlayer();

        UUID playerId = player.getUniqueId();


        // защита от рекурсии
        if (connectingPlayers.remove(player.getUniqueId())) {
            return;
        }

        RegisteredServer target = event.getOriginalServer();
        if (target == null) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        String serverName = target.getServerInfo().getName();

        if (serverName.equalsIgnoreCase("lobby")) {
            return;
        }

        configuredServer srv = servers.get(serverName);
        if (srv == null) return;


        if (srv.status == configuredServer.StatusEnum.STARTING) {
            player.sendMessage(Component.text("§eСервер уже запускается..."));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        // игрок уже запускает сервер
        if (startingPlayers.contains(playerId)) {
            player.sendMessage(Component.text("§cВы уже запускаете сервер, дождитесь подключения."));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        player.sendMessage(Component.text("§7Проверка сервера..."));
        startingPlayers.add(playerId);

        target.ping().whenComplete((ping, error) -> {
            if (error != null) {
                startingServers.add(serverName);
                srv.status = configuredServer.StatusEnum.STARTING;
                player.sendMessage(Component.text("§6Сервер запускается..."));
                startServer(serverName);
                waitAndConnect(player, target, serverName);
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
            } else {
                connectingPlayers.add(player.getUniqueId());
                player.createConnectionRequest(target).fireAndForget();
                startingPlayers.remove(playerId);
            }
        });
    }

    // =========================
    // АВТОПОДКЛЮЧЕНИЕ
    // =========================
    private void waitAndConnect(Player player, RegisteredServer target, String serverName) {
        server.getScheduler().buildTask(this, new Runnable() {
            int attempts = 0;
            boolean done = false;

            @Override
            public void run() {

                if (done) return;

                attempts++;

                target.ping().whenComplete((ping, error) -> {
                    if (done) return;
                    if (error == null) {
                        if (ping.getPlayers().isPresent() && ping.getPlayers().get().getMax() > 0) {
                            player.sendMessage(Component.text("§aСервер запущен!"));
                            connectingPlayers.add(player.getUniqueId());
                            player.createConnectionRequest(target).fireAndForget();
                            servers.get(serverName).scheduleShutdown(pluginClass);

                            startingServers.remove(serverName);
                            servers.get(serverName).status = configuredServer.StatusEnum.ONLINE;
                            done = true;
                            startingPlayers.remove(player.getUniqueId());
                        }
                    } else if (attempts >= 10) {

                        player.sendMessage(Component.text("§cСервер не запустился :("));
                        servers.get(serverName).status = configuredServer.StatusEnum.ERROR;
                        startingServers.remove(serverName);
                        done = true;
                        startingPlayers.remove(player.getUniqueId());
                    }
                });
            }
        }).repeat(10, TimeUnit.SECONDS).schedule();
    }

    // =========================
    // АВТО ВЫКЛЮЧЕНИЕ
    // =========================
    public void startServer(String name) {

        configuredServer srv = servers.get(name);
        if (srv == null) return;

        executeSSH(srv, true);
    }

    public void stopServer(String name) {

        configuredServer srv = servers.get(name);
        if (srv == null) return;

        executeSSH(srv, false);
    }

    private void executeSSH(configuredServer srv, boolean start) {

        new Thread(() -> {
            try {
                JSch jsch = new JSch();

                Session session = jsch.getSession(srv.sshUser, srv.ip, 22);
                session.setPassword(srv.sshPassword);
                session.setConfig("StrictHostKeyChecking", "no");
                session.connect();

                ChannelExec channel = (ChannelExec) session.openChannel("exec");

                String cmd = (start ? "systemctl start " : "systemctl stop ") + srv.service;
                channel.setCommand(cmd);

                channel.connect();

                logger.info("{}: {}", start ? "Запуск" : "Остановка", srv.name);

                channel.disconnect();
                session.disconnect();

            } catch (Exception e) {
                logger.error("SSH ошибка: {}", srv.name, e);
            }
        }).start();
    }


    @Subscribe
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // проверка на "!"
        if (!message.startsWith("!")) return;

        // отменяем отправку на сервер
        // event.setResult(PlayerChatEvent.ChatResult.message("")); кикает, если использовать.

        // убираем "!"
        String globalMessage = message.substring(1);

        // если пусто — не отправляем
        if (globalMessage.isBlank()) return;

        // получаем сервер игрока
        String serverName = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("unknown");

        configuredServer serverd = servers.get(serverName);

        if (serverd == null && !serverName.equals("lobby")) {
            Component msg = Component.text("Ошибка 100");
            player.sendMessage(msg);
            return;
        }

        // красивое сообщение
        Component msg = Component.text("§6[Глобальный] §f" + player.getUsername() + " §7(" + (serverd != null ? serverd.displayName : "lobby") + "§7) §8» §f" + globalMessage);

        // рассылка всем игрокам
        server.getAllPlayers().forEach(p -> p.sendMessage(msg));
    }

    public void reloadConfigs() {
        createDefaultConfig();
        loadServers();
        loadOnlineUsers();
    }
}
