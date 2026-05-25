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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ConnectionServerHandler {

    private static final int PING_ATTEMPTS = 10;
    private static final int SSH_TIMEOUT_MS = 10_000;

    public AutoServerControl plugin;
    private final Logger logger;
    private final ProxyServer server;

    public Map<String, configuredServer> servers = new ConcurrentHashMap<>();

    private final Set<UUID> connectingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<UUID>> waitingPlayersByServer = new ConcurrentHashMap<>();
    private final ExecutorService sshExecutor = Executors.newCachedThreadPool();

    private static ConnectionServerHandler instance;

    private ConnectionServerHandler(AutoServerControl plugin) {
        instance = this;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        server = plugin.server;
    }

    public void updateServers(Map<String, configuredServer> serversMap) {
        servers = new ConcurrentHashMap<>(serversMap);
        waitingPlayersByServer.keySet().removeIf(serverName -> !servers.containsKey(serverName));
        checkAllServers();
    }

    public static ConnectionServerHandler getInstance(AutoServerControl plugin) {
        if (instance == null) {
            return new ConnectionServerHandler(plugin);
        } else {
            instance.plugin = plugin;
            return instance;
        }
    }

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

    private void addWaitingPlayer(String serverName, Player player) {
        waitingPlayersByServer
                .computeIfAbsent(serverName, key -> ConcurrentHashMap.newKeySet())
                .add(player.getUniqueId());
    }

    private boolean isWaitingForAnyServer(UUID playerId) {
        for (Set<UUID> waitingPlayers : waitingPlayersByServer.values()) {
            if (waitingPlayers.contains(playerId)) {
                return true;
            }
        }
        return false;
    }

    private void removeWaitingPlayer(UUID playerId) {
        waitingPlayersByServer.values().forEach(waitingPlayers -> waitingPlayers.remove(playerId));
    }

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

    private void failWaitingPlayers(String serverName, Component message) {
        Set<UUID> waitingPlayers = waitingPlayersByServer.remove(serverName);
        if (waitingPlayers == null || waitingPlayers.isEmpty()) {
            return;
        }

        for (UUID playerId : waitingPlayers) {
            server.getPlayer(playerId).ifPresent(player -> player.sendMessage(message));
        }
    }

    public void onDisconnectEvent() {
        checkAllServers();
    }

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

    public void onServerPostConnectedEvent(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        Optional<ServerConnection> newServer = player.getCurrentServer();
        String serverName;
        if (newServer.isPresent()) serverName = newServer.get().getServerInfo().getName();
        else return;
        if (event.getPreviousServer() == null) {
            return;
        }
        if (Objects.equals(event.getPreviousServer().getServerInfo().getName(), "auth")) {
            return;
        }
        configuredServer current = servers.get(serverName);
        Component broadcastMsg;
        if (serverName.equalsIgnoreCase("lobby")) {
            broadcastMsg = Component.text("§eИгрок §f" + player.getUsername() + "§e вернулся в лобби");
        } else if (current != null) {
            broadcastMsg = Component.text("§eИгрок §f" + player.getUsername() + "§e отправился в " + current.displayName);
        } else {
            broadcastMsg = Component.text("§eИгрок §f" + player.getUsername() + "§e перешел на " + serverName);
        }
        server.getAllPlayers().forEach(p -> p.sendMessage(broadcastMsg));

        if (event.getPreviousServer() != null) {
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

        event.getPlayer().getCurrentServer().ifPresent(srv -> {
            String name = srv.getServerInfo().getName();

            if (current != null) {
                current.cancelShutdown();
                current.status = configuredServer.StatusEnum.ONLINE;
                logger.info("Отмена выключения сервера: {}", name);
            }
        });
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
