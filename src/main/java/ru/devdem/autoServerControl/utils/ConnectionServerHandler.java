package ru.devdem.autoServerControl.utils;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import ru.devdem.autoServerControl.AutoServerControl;
import ru.devdem.autoServerControl.classes.configuredServer;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Управляет переходами игроков на серверы, автозапуском пустых серверов и автоостановкой.
 */
public class ConnectionServerHandler {

    /** Сколько раз проверять ping сервера после команды запуска. */
    private static final int PING_ATTEMPTS = 10;

    /** Таймаут SSH-подключения и открытия exec-канала. */
    private static final int SSH_TIMEOUT_MS = 10_000;

    /** Главный класс плагина, нужен для scheduler и доступа к конфигам. */
    public AutoServerControl plugin;

    /** Логгер плагина. */
    private final Logger logger;

    /** Velocity proxy API. */
    private final ProxyServer server;

    /** Настроенные серверы по имени Velocity-сервера. */
    public Map<String, configuredServer> servers = new ConcurrentHashMap<>();

    /** Игроки, которых плагин сам подключает через createConnectionRequest(), чтобы не зациклить ServerPreConnectEvent. */
    private final Set<UUID> connectingPlayers = ConcurrentHashMap.newKeySet();

    /** Очереди игроков, ожидающих запуска конкретного сервера. */
    private final Map<String, Set<UUID>> waitingPlayersByServer = new ConcurrentHashMap<>();

    /** Пул потоков для SSH-команд, чтобы не блокировать event loop Velocity. */
    private final ExecutorService sshExecutor = Executors.newCachedThreadPool();

    /** Singleton-экземпляр обработчика серверов. */
    private static ConnectionServerHandler instance;

    /**
     * Создает обработчик серверов.
     */
    private ConnectionServerHandler(AutoServerControl plugin) {
        instance = this;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        server = plugin.server;
    }

    /**
     * Обновляет список серверов после reload servers.yml.
     */
    public void updateServers(Map<String, configuredServer> serversMap) {
        servers = new ConcurrentHashMap<>(serversMap);
        waitingPlayersByServer.keySet().removeIf(serverName -> !servers.containsKey(serverName));
        checkAllServers();
    }

    /**
     * Возвращает singleton-экземпляр обработчика серверов.
     */
    public static ConnectionServerHandler getInstance(AutoServerControl plugin) {
        if (instance == null) {
            return new ConnectionServerHandler(plugin);
        } else {
            instance.plugin = plugin;
            return instance;
        }
    }

    /**
     * Проверяет все настроенные серверы и ставит таймер остановки для пустых.
     */
    private void checkAllServers() {
        for (configuredServer srv : servers.values()) {
            server.getServer(srv.name).ifPresent(registeredServer -> {
                int players = registeredServer.getPlayersConnected().size();
                if (players == 0) {
                    srv.scheduleShutdown(plugin);
                } else {
                    srv.cancelShutdown();
                    srv.status = configuredServer.StatusEnum.ONLINE;
                }
            });
        }
    }

    // =========================
    // АВТОПОДКЛЮЧЕНИЕ
    // =========================
    /**
     * После SSH-запуска периодически ping'ует сервер и подключает всю очередь игроков.
     */
    private void waitAndConnect(RegisteredServer target, String serverName) {
        server.getScheduler().buildTask(plugin, new Runnable() {
            int attempts = 0;
            boolean done = false;

            @Override
            public void run() {
                if (done) return;

                attempts++;

                target.ping().whenComplete((ping, error) -> {
                    if (done) return;

                    if (error == null && ping.getPlayers().isPresent() && ping.getPlayers().get().getMax() > 0) {
                        configuredServer configured = servers.get(serverName);
                        if (configured != null) {
                            configured.status = configuredServer.StatusEnum.ONLINE;
                        }
                        connectWaitingPlayers(serverName, target, Component.text("§aСервер запущен!"));
                        done = true;
                    } else if (attempts >= PING_ATTEMPTS) {
                        configuredServer configured = servers.get(serverName);
                        if (configured != null) {
                            configured.status = configuredServer.StatusEnum.ERROR;
                        }
                        failWaitingPlayers(serverName, Component.text("§cСервер не запустился :("));
                        done = true;
                    }
                });
            }
        }).repeat(10, TimeUnit.SECONDS).schedule();
    }

    /**
     * Добавляет игрока в очередь ожидания запуска конкретного сервера.
     */
    private void addWaitingPlayer(String serverName, Player player) {
        waitingPlayersByServer
                .computeIfAbsent(serverName, key -> ConcurrentHashMap.newKeySet())
                .add(player.getUniqueId());
    }

    /**
     * Проверяет, ожидает ли игрок запуска какого-либо сервера.
     */
    private boolean isWaitingForAnyServer(UUID playerId) {
        for (Set<UUID> waitingPlayers : waitingPlayersByServer.values()) {
            if (waitingPlayers.contains(playerId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Убирает игрока из всех очередей ожидания.
     */
    private void removeWaitingPlayer(UUID playerId) {
        waitingPlayersByServer.values().forEach(waitingPlayers -> waitingPlayers.remove(playerId));
    }

    /**
     * Подключает всех игроков, которые ждали запуска указанного сервера.
     */
    private void connectWaitingPlayers(String serverName, RegisteredServer target, Component message) {
        Set<UUID> waitingPlayers = waitingPlayersByServer.remove(serverName);
        if (waitingPlayers == null || waitingPlayers.isEmpty()) {
            return;
        }

        for (UUID playerId : waitingPlayers) {
            server.getPlayer(playerId).ifPresent(player -> {
                player.sendMessage(message);
                connectingPlayers.add(player.getUniqueId());
                player.createConnectionRequest(target).fireAndForget();
            });
        }
    }

    /**
     * Сообщает ожидающим игрокам, что запуск сервера не удался.
     */
    private void failWaitingPlayers(String serverName, Component message) {
        Set<UUID> waitingPlayers = waitingPlayersByServer.remove(serverName);
        if (waitingPlayers == null || waitingPlayers.isEmpty()) {
            return;
        }

        for (UUID playerId : waitingPlayers) {
            server.getPlayer(playerId).ifPresent(player -> player.sendMessage(message));
        }
    }

    /**
     * После отключения игрока проверяет пустые серверы с задержкой, чтобы Velocity успел обновить список игроков.
     */
    public void onDisconnectEvent() {
        server.getScheduler()
                .buildTask(plugin, this::checkAllServers)
                .delay(1, TimeUnit.SECONDS)
                .schedule();
    }

    /**
     * Перехватывает попытку перехода на сервер и запускает его через SSH, если ping не отвечает.
     */
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // защита от рекурсии после createConnectionRequest()
        if (connectingPlayers.remove(playerId)) {
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
            addWaitingPlayer(serverName, player);
            player.sendMessage(Component.text("§eСервер уже запускается. Подключим вас автоматически."));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        if (isWaitingForAnyServer(playerId)) {
            player.sendMessage(Component.text("§cВы уже ожидаете запуск сервера, дождитесь подключения."));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        player.sendMessage(Component.text("§7Проверка сервера..."));
        addWaitingPlayer(serverName, player);

        target.ping().whenComplete((ping, error) -> {
            if (error != null) {
                srv.status = configuredServer.StatusEnum.STARTING;
                player.sendMessage(Component.text("§6Сервер запускается..."));
                startServer(serverName);
                waitAndConnect(target, serverName);
            } else {
                srv.status = configuredServer.StatusEnum.ONLINE;
                connectingPlayers.add(playerId);
                player.createConnectionRequest(target).fireAndForget();
                removeWaitingPlayer(playerId);
            }
        });
    }

    /**
     * Обрабатывает успешный переход между серверами: сообщения в чат и таймер выключения прошлого сервера.
     */
    public void onServerPostConnectedEvent(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        Optional<ServerConnection> newServer = player.getCurrentServer();
        String serverName;
        if (newServer.isPresent()) serverName = newServer.get().getServerInfo().getName();
        else return;
        if (event.getPreviousServer() == null) {
            return;
        }
        String previousServerName = event.getPreviousServer().getServerInfo().getName();
        if (previousServerName.equalsIgnoreCase("auth")) {
            if (serverName.equalsIgnoreCase("lobby")) {
                broadcastMessage("player-join", Map.of("player", player.getUsername()));
            }
            return;
        }
        configuredServer current = servers.get(serverName);

        scheduleShutdownIfPreviousServerIsEmpty(event);

        if (serverName.equalsIgnoreCase("auth")) {
            return;
        }

        Component broadcastMsg;
        if (serverName.equalsIgnoreCase("lobby")) {
            broadcastMsg = Component.text(plugin.configsHandler.getMessage(
                    "player-return-lobby",
                    Map.of("player", player.getUsername())
            ));
        } else if (current != null) {
            broadcastMsg = Component.text(plugin.configsHandler.getMessage(
                    "player-switch-server",
                    Map.of("player", player.getUsername(), "server", current.displayName)
            ));
        } else {
            broadcastMsg = Component.text(plugin.configsHandler.getMessage(
                    "player-switch-unknown",
                    Map.of("player", player.getUsername(), "server", serverName)
            ));
        }
        server.getAllPlayers().forEach(p -> p.sendMessage(broadcastMsg));

        event.getPlayer().getCurrentServer().ifPresent(srv -> {
            String name = srv.getServerInfo().getName();

            if (current != null) {
                current.cancelShutdown();
                current.status = configuredServer.StatusEnum.ONLINE;
                logger.info("Отмена выключения сервера: {}", name);
            }
        });
    }

    /**
     * Рассылает всем игрокам сообщение из messages.yml.
     */
    private void broadcastMessage(String messageKey, Map<String, String> placeholders) {
        Component message = Component.text(plugin.configsHandler.getMessage(messageKey, placeholders));
        server.getAllPlayers().forEach(player -> player.sendMessage(message));
    }

    /**
     * Ставит таймер остановки прошлого сервера, если после ухода игрока он стал пустым.
     */
    private void scheduleShutdownIfPreviousServerIsEmpty(ServerPostConnectEvent event) {
        if (event.getPreviousServer() == null) {
            return;
        }

        String prevName = event.getPreviousServer().getServerInfo().getName();
        configuredServer prev = servers.get(prevName);

        if (prev != null) {
            RegisteredServer srv = event.getPreviousServer();
            if (srv.getPlayersConnected().isEmpty()) {
                prev.scheduleShutdown(plugin);
                logger.info("Запускаем таймер на {}", prev.name);
            }
        }
    }

    // =========================
    // АВТО ВЫКЛЮЧЕНИЕ
    // =========================
    /**
     * Запускает настроенный сервер через SSH.
     */
    public void startServer(String name) {
        configuredServer srv = servers.get(name);
        if (srv == null) return;

        executeSSH(srv, true);
    }

    /**
     * Останавливает настроенный сервер через SSH.
     */
    public void stopServer(String name) {
        configuredServer srv = servers.get(name);
        if (srv == null) return;

        executeSSH(srv, false);
    }

    /**
     * Выполняет systemctl start/stop для сервиса сервера через SSH.
     */
    private void executeSSH(configuredServer srv, boolean start) {
        sshExecutor.execute(() -> {
            Session session = null;
            ChannelExec channel = null;
            try {
                JSch jsch = new JSch();

                session = jsch.getSession(srv.sshUser, srv.ip, 22);
                session.setPassword(srv.sshPassword);
                session.setConfig("StrictHostKeyChecking", "no");
                session.connect(SSH_TIMEOUT_MS);

                channel = (ChannelExec) session.openChannel("exec");

                String cmd = (start ? "systemctl start " : "systemctl stop ") + srv.service;
                channel.setCommand(cmd);

                channel.connect(SSH_TIMEOUT_MS);

                while (!channel.isClosed()) {
                    Thread.sleep(100);
                }

                int exitStatus = channel.getExitStatus();
                if (exitStatus != 0) {
                    logger.error("SSH command for {} finished with exit code {}", srv.name, exitStatus);
                    if (start) {
                        failWaitingPlayers(srv.name, Component.text("§cНе удалось отправить команду запуска сервера."));
                        srv.status = configuredServer.StatusEnum.ERROR;
                    }
                    return;
                }

                logger.info("{}: {}", start ? "Запуск" : "Остановка", srv.name);

            } catch (Exception e) {
                logger.error("SSH ошибка: {}", srv.name, e);
                if (start) {
                    failWaitingPlayers(srv.name, Component.text("§cSSH ошибка при запуске сервера."));
                    srv.status = configuredServer.StatusEnum.ERROR;
                }
            } finally {
                if (channel != null) {
                    channel.disconnect();
                }
                if (session != null) {
                    session.disconnect();
                }
            }
        });
    }
}
