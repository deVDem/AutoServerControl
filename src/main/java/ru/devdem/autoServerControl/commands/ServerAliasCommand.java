package ru.devdem.autoServerControl.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import ru.devdem.autoServerControl.AutoServerControl;
import ru.devdem.autoServerControl.classes.configuredServer;

/**
 * Команда-переход на конкретный сервер из servers.yml.
 */
public class ServerAliasCommand implements SimpleCommand {

    /** Сервер, на который ведет эта команда. */
    private final configuredServer server;

    /** Главный класс плагина с доступом к Velocity proxy. */
    private final AutoServerControl plugin;

    /**
     * Создает команду для одного настроенного сервера.
     */
    public ServerAliasCommand(AutoServerControl plugin, configuredServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    /**
     * Проверяет право devdem.<serverName> на использование команды сервера.
     */
    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("devdem."+server.name);
    }

    /**
     * Отправляет игрока на сервер; фактический автозапуск перехватывается в ServerPreConnectEvent.
     */
    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();

        // Проверяем, что это игрок
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("Эту команду может использовать только игрок."));
            return;
        }

        plugin.server.getServer(server.name).ifPresentOrElse(srv -> {
            if (player.getCurrentServer()
                    .map(s -> s.getServerInfo().getName().equalsIgnoreCase(server.name))
                    .orElse(false)) {

                player.sendMessage(Component.text("Ты уже на этом сервере."));
                return;
            }

            player.createConnectionRequest(srv).fireAndForget();

        }, () -> {
            player.sendMessage(Component.text("Сервер не найден."));
        });
    }
}
