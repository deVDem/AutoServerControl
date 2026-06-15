package ru.devdem.autoServerControl.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

/**
 * Команда /lobby, /l и /hub для возврата игрока в лобби.
 */
public class LobbyCommand implements SimpleCommand {

    /** Velocity proxy, через который ищется сервер lobby. */
    private final ProxyServer proxy;

    /**
     * Создает команду возврата в лобби.
     */
    public LobbyCommand(ProxyServer server) {
        this.proxy = server;
    }

    /**
     * Выполняет команду и отправляет игрока на сервер lobby.
     */
    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();

        // Проверяем, что это игрок
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("Эту команду может использовать только игрок."));
            return;
        }

        proxy.getServer("lobby").ifPresentOrElse(server -> {
            if (player.getCurrentServer()
                    .map(s -> s.getServerInfo().getName().equalsIgnoreCase("lobby"))
                    .orElse(false)) {

                player.sendMessage(Component.text("Ты уже на этом сервере."));
                return;
            }

            player.createConnectionRequest(server).fireAndForget();

        }, () -> {
            player.sendMessage(Component.text("Сервер не найден."));
        });
    }
}
