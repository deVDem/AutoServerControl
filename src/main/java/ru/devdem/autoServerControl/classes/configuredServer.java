package ru.devdem.autoServerControl.classes;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import ru.devdem.autoServerControl.AutoServerControl;
import ru.devdem.autoServerControl.utils.ConnectionServerHandler;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Описание управляемого игрового сервера из servers.yml.
 */
public class configuredServer {

    /**
     * Runtime-состояние сервера внутри плагина.
     */
    public enum StatusEnum {
        /** Статус еще не определен. */
        NONE,

        /** Сервер запускается через SSH и ожидает успешного ping. */
        STARTING,

        /** Сервер отвечает и считается доступным для игроков. */
        ONLINE,

        /** Сервер был остановлен плагином. */
        SHUTDOWN,

        /** Сервер пустой, запланировано выключение. */
        AWAITING,

        /** Последняя операция запуска завершилась ошибкой. */
        ERROR
    }

    /** Velocity proxy, через который планируются задачи и ищутся RegisteredServer. */
    private final ProxyServer proxy;

    /** IP-адрес машины, на которой доступен SSH и игровой сервер. */
    public final String ip;

    /** Имя сервера в Velocity и ключ в servers.yml. */
    public final String name;

    /** Имя systemd-сервиса, которым управляет SSH-команда. */
    public final String service;

    /** Отображаемое имя сервера для сообщений игрокам. */
    public final String displayName;

    /** SSH-пользователь для запуска и остановки сервиса. */
    public final String sshUser;

    /** SSH-пароль для запуска и остановки сервиса. */
    public final String sshPassword;

    /** Командные алиасы, которые телепортируют игрока на этот сервер. */
    public final Set<String> aliases;

    /** Активная отложенная задача выключения, если сервер сейчас ожидает остановку. */
    private ScheduledTask shutdownTask;

    /** Центральный обработчик, через который выполняется stopServer(). */
    private final ConnectionServerHandler serverHandler;

    /** Текущее состояние сервера с точки зрения AutoServerControl. */
    public StatusEnum status = StatusEnum.NONE;

    /**
     * Создает объект конфигурации сервера из servers.yml.
     */
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
        serverHandler = ConnectionServerHandler.getInstance(null);
    }

    /**
     * Ставит выключение сервера через 5 минут, если к моменту выполнения он все еще пустой.
     */
    public void scheduleShutdown(AutoServerControl plugin) {
        cancelShutdown();

        shutdownTask = proxy.getScheduler()
                .buildTask(plugin, () -> {
                    proxy.getServer(name).ifPresent(srv -> {
                        if (srv.getPlayersConnected().isEmpty()) {
                            plugin.getLogger().info("Выключаем сервер: {}", name);
                            serverHandler.stopServer(name);
                            status = StatusEnum.SHUTDOWN;
                        }
                    });
                })
                .delay(5, TimeUnit.MINUTES)
                .schedule();

        plugin.getLogger().info("Сервер {} будет выключен через 5 минут", name);
        status = StatusEnum.AWAITING;
    }

    /**
     * Отменяет запланированное выключение, если игрок снова зашел на сервер.
     */
    public void cancelShutdown() {
        if (shutdownTask != null) {
            shutdownTask.cancel();
            shutdownTask = null;
        }
    }
}
