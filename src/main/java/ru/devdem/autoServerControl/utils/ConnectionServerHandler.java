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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ConnectionServerHandler {

    public AutoServerControl plugin;
    private final Logger logger;
    private final ProxyServer server;

    public Map<String, configuredServer> servers = new HashMap<>();

    private final Set<String> startingServers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> connectingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> startingPlayers = ConcurrentHashMap.newKeySet();

    private static ConnectionServerHandler instance;

    private ConnectionServerHandler(AutoServerControl plugin) {
        instance = this;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        server = plugin.server;
    }


    public void updateServers(Map<String, configuredServer> serversMap) {
        servers = serversMap;
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
                }
            });
        }
    }

    // =========================
    // АВТОПОДКЛЮЧЕНИЕ
    // =========================
    private void waitAndConnect(Player player, RegisteredServer target, String serverName) {
        server.getScheduler().buildTask(plugin, new Runnable() {
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
                            servers.get(serverName).scheduleShutdown(plugin);

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


    public void onDisconnectEvent() {
        checkAllServers();
    }

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
                    prev.scheduleShutdown(plugin);
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

}
