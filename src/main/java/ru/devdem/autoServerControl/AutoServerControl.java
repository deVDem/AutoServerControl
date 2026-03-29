package ru.devdem.autoServerControl;

import com.google.inject.Inject;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
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
import ru.devdem.autoServerControl.commands.ServerAliasCommand;
import ru.devdem.autoServerControl.functions.OfflineMode;
import ru.devdem.autoServerControl.utils.ConfigsHandler;
import ru.devdem.autoServerControl.utils.ConnectionServerHandler;
import ru.devdem.autoServerControl.utils.Utils;

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

    public final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final AutoServerControl pluginClass;


    public Logger getLogger() {
        return logger;
    }

    public ConnectionServerHandler serverHandler;
    public OfflineMode offlineModeClass;
    public ConfigsHandler configsHandler;

    @Inject
    public AutoServerControl(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        pluginClass = this;
        offlineModeClass = OfflineMode.getInstance(pluginClass);
        configsHandler = new ConfigsHandler(this, this.logger, this.dataDirectory, this.server);
        serverHandler = ConnectionServerHandler.getInstance(this);
    }

    // =========================
    // ИНИЦИАЛИЗАЦИЯ
    // =========================
    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        reloadConfigs();
        registerCommands();
        logger.info("AutoServerControl загружен!");
    }


    private final Set<CommandMeta> servercommands = new HashSet<>();
    private void registerCommands() {
        CommandManager manager = server.getCommandManager();
        manager.register(manager.metaBuilder("ascreload")
                        .aliases("areload")
                        .build(),
                new ReloadCommand(this)
        );

        for (CommandMeta meta : servercommands) {
            manager.unregister(meta);
        }

        for (configuredServer srv : serverHandler.servers.values()) {
            CommandMeta commandMeta =  manager.metaBuilder(srv.name)
                            .aliases(Utils.getAliasesFromSet(srv.aliases))
                            .build();
            manager.register(commandMeta, new ServerAliasCommand(this, srv));
            servercommands.add(commandMeta);
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
        serverHandler.onDisconnectEvent();
    }

    @Subscribe
    public void onServerPostConnectedEvent(ServerPostConnectEvent event) {
        serverHandler.onServerPostConnectedEvent(event);
    }

    // =========================
    // ПОДКЛЮЧЕНИЕ К СЕРВЕРУ
    // =========================
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        serverHandler.onServerPreConnect(event);
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

        configuredServer serverd = serverHandler.servers.get(serverName);

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
        configsHandler.reloadConfigs();
        registerCommands();
    }
}
