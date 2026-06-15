package ru.devdem.autoServerControl;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
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
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import ru.devdem.autoServerControl.classes.configuredServer;
import ru.devdem.autoServerControl.commands.LobbyCommand;
import ru.devdem.autoServerControl.commands.ReloadCommand;
import ru.devdem.autoServerControl.commands.ServerAliasCommand;
import ru.devdem.autoServerControl.functions.OfflineMode;
import ru.devdem.autoServerControl.utils.ConfigsHandler;
import ru.devdem.autoServerControl.utils.ConnectionServerHandler;
import ru.devdem.autoServerControl.utils.Utils;

import java.nio.file.Path;
import java.util.*;

/**
 * Главная точка входа Velocity-плагина.
 *
 * Регистрирует команды, подключает обработчики событий и связывает конфиги с runtime-логикой.
 */
@Plugin(id = "autoservercontrol", name = "AutoServerControl", version = BuildConstants.VERSION, description = "Only for deVDem MC HUB", url = "devdem.ru/mc-hub", authors = {"deVDem"})
public class AutoServerControl {

    /** Velocity proxy API. Используется для команд, игроков, серверов и рассылок. */
    public final ProxyServer server;

    /** Логгер плагина. */
    private final Logger logger;


    /**
     * Возвращает логгер плагина для вспомогательных классов.
     */
    public Logger getLogger() {
        return logger;
    }

    /** Обработчик автозапуска, автоподключения и автоостановки игровых серверов. */
    public ConnectionServerHandler serverHandler;

    /** Обработчик offline/online/bedrock логики входа. */
    public OfflineMode offlineModeClass;

    /** Загрузчик config.yml, servers.yml и messages.yml. */
    public ConfigsHandler configsHandler;

    /**
     * Velocity создает плагин через DI и передает основные зависимости.
     */
    @Inject
    public AutoServerControl(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        configsHandler = new ConfigsHandler(this, this.logger, dataDirectory, this.server);
        serverHandler = ConnectionServerHandler.getInstance(this);
    }

    // =========================
    // ИНИЦИАЛИЗАЦИЯ
    // =========================
    /**
     * Загружает конфиги и runtime-обработчики после старта proxy.
     */
    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        reloadConfigs();
        offlineModeClass = OfflineMode.getInstance(this);
        logger.info("AutoServerControl загружен!");
    }


    /** Метаданные команд, которые были зарегистрированы плагином и должны сниматься при reload. */
    private final Set<CommandMeta> registeredCommands = new HashSet<>();

    /**
     * Регистрирует команды плагина и серверные алиасы из servers.yml.
     */
    private void registerCommands() {
        CommandManager manager = server.getCommandManager();

        for (CommandMeta meta : registeredCommands) {
            manager.unregister(meta);
        }
        registeredCommands.clear();

        CommandMeta reloadMeta = manager.metaBuilder("ascreload")
                        .aliases("areload")
                        .build();
        manager.register(reloadMeta,
                new ReloadCommand(this)
        );
        registeredCommands.add(reloadMeta);

        for (configuredServer srv : serverHandler.servers.values()) {
            CommandMeta commandMeta =  manager.metaBuilder(srv.name)
                            .aliases(srv.aliases.toArray(new String[0]))
                            .build();
            manager.register(commandMeta, new ServerAliasCommand(this, srv));
            registeredCommands.add(commandMeta);
            logger.info("Добавлены команды {} для {}", Utils.getAliasesFromSet(srv.aliases), srv.name);
        }
        CommandMeta lobbyMeta = manager.metaBuilder("lobby")
                        .aliases("l", "hub")
                        .build();
        manager.register(lobbyMeta,
                new LobbyCommand(server)
        );
        registeredCommands.add(lobbyMeta);


        logger.info("Команды загружены.");
    }

    /**
     * Асинхронно обрабатывает раннюю фазу входа игрока.
     */
    @Subscribe
    public EventTask onPreLogin(PreLoginEvent event) {
        return EventTask.async(() -> {
            offlineModeClass.onPreLogin(event);

        });
    }

    /**
     * Асинхронно подменяет профиль игрока, если нужен offline-mode UUID.
     */
    @Subscribe
    public EventTask onGameProfileRequest(GameProfileRequestEvent event) {
        return EventTask.async(() -> {
            offlineModeClass.onGameProfileRequest(event);
        });
    }

    /**
     * Асинхронно сохраняет финальные данные игрока после успешной авторизации.
     */
    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        return EventTask.async(() -> {
            offlineModeClass.onLoginEvent(event);
        });
    }

    /**
     * Рассылает сообщение об отключении и запускает проверку пустых серверов.
     */
    @Subscribe
    public void onDisconnectEvent(DisconnectEvent event) {
        Player player = event.getPlayer();
        Component broadcastMsg = Component.text(configsHandler.getMessage(
                "player-disconnect",
                Map.of("player", player.getUsername())
        ));
        server.getAllPlayers().forEach(p -> p.sendMessage(broadcastMsg));
        serverHandler.onDisconnectEvent();
        offlineModeClass.onDisconnectEvent(event);
    }

    /**
     * Обрабатывает переходы между серверами после фактического подключения.
     */
    @Subscribe
    public void onServerPostConnectedEvent(ServerPostConnectEvent event) {
        serverHandler.onServerPostConnectedEvent(event);
    }

    // =========================
    // ПОДКЛЮЧЕНИЕ К СЕРВЕРУ
    // =========================
    /**
     * Перехватывает подключение к серверу до перехода, чтобы при необходимости запустить сервер.
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        serverHandler.onServerPreConnect(event);
    }


    /**
     * Реализует глобальный чат: сообщение, начинающееся с "!", отправляется всем игрокам proxy.
     */
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

        configuredServer currentConfiguredServer = serverHandler.servers.get(serverName);

        if (currentConfiguredServer == null && !serverName.equals("lobby")) {
            Component msg = Component.text("Ошибка 100");
            player.sendMessage(msg);
            return;
        }

        // красивое сообщение
        Component msg = Component.text("§6[Глобальный] §f" + player.getUsername() + " §7(" + (currentConfiguredServer != null ? currentConfiguredServer.displayName : "lobby") + "§7) §8» §f" + globalMessage);

        // рассылка всем игрокам
        server.getAllPlayers().forEach(p -> p.sendMessage(msg));
    }

    /**
     * Перечитывает все конфиги и обновляет динамические команды серверов.
     */
    public void reloadConfigs() {
        configsHandler.reloadConfigs();
        registerCommands();
    }
}
