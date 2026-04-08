package ru.devdem.autoServerControl.classes;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import ru.devdem.autoServerControl.AutoServerControl;
import ru.devdem.autoServerControl.utils.ConnectionServerHandler;

import java.util.Set;
import java.util.concurrent.TimeUnit;

public class configuredServer {

    public enum StatusEnum {
        NONE,
        STARTING,
        ONLINE,
        SHUTDOWN,
        AWAITING,
        ERROR
    }

    private final ProxyServer proxy;

    public final String ip;
    public final String name;
    public final String service;
    public final String displayName;
    public final String sshUser;
    public final String sshPassword;
    public final Set<String> aliases;

    private ScheduledTask shutdownTask;
    private final ConnectionServerHandler serverHandler;

    public StatusEnum status = StatusEnum.NONE;

    public configuredServer(ProxyServer proxy,
                            String ip,
                            String name,
                            String service,
                            String displayName,
                            String sshUser,
                            String sshPassword,
                            Set<String> aliases) {

        this.proxy = proxy;
        this.ip = ip;
        this.name = name;
        this.service = service;
        this.displayName = displayName;
        this.sshUser = sshUser;
        this.sshPassword = sshPassword;
        this.aliases = aliases;
        serverHandler = ConnectionServerHandler.getInstance(null); // я надеюсь, что Handler уже создан и он просто вернёт готовый
    }

    // =========================
    // ЗАПЛАНИРОВАТЬ ВЫКЛЮЧЕНИЕ
    // =========================
    public void scheduleShutdown(AutoServerControl plugin) {

        cancelShutdown();

        shutdownTask = proxy.getScheduler()
                .buildTask(plugin, () -> {
                    proxy.getServer(name).ifPresent(srv -> {
                        if (srv.getPlayersConnected().isEmpty()) {
                            srv.ping().whenComplete((ping, error) -> {
                                if (error != null) { // сервер включен, тогда выключаем.
                                    plugin.getLogger().info("Выключаем сервер: {}", name);
                                    serverHandler.stopServer(name);
                                    status = StatusEnum.SHUTDOWN;
                                } else {
                                    plugin.getLogger().info("Пытались выключить {}, но он был выключен.", name);
                                }
                            });
                        }
                    });
                })
                .delay(5, TimeUnit.MINUTES)
                .schedule();

        plugin.getLogger().info("Сервер {} будет выключен через 5 минут", name);
        status = StatusEnum.AWAITING;
    }

    // =========================
    // ОТМЕНА ВЫКЛЮЧЕНИЯ
    // =========================
    public void cancelShutdown() {
        if (shutdownTask != null) {
            shutdownTask.cancel();
            shutdownTask = null;
        }
    }
}