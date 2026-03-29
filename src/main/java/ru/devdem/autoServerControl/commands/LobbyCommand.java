package ru.devdem.autoServerControl.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

public class LobbyCommand implements SimpleCommand {

    private final ProxyServer proxy;

    public LobbyCommand(ProxyServer server) {
        this.proxy = server;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();

        // Проверяем, что это игрок
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("Эту команду может использовать только игрок."));
            return;
        }

        // Получаем сервер
        proxy.getServer("lobby").ifPresentOrElse(server -> {
            // Если уже на сервере — можно не телепортировать
            if (player.getCurrentServer()
                    .map(s -> s.getServerInfo().getName().equalsIgnoreCase("lobby"))
                    .orElse(false)) {

                player.sendMessage(Component.text("Ты уже на этом сервере."));
                return;
            }

            // Отправка
            player.createConnectionRequest(server).fireAndForget();

        }, () -> {
            player.sendMessage(Component.text("Сервер не найден."));
        });
    }
}
