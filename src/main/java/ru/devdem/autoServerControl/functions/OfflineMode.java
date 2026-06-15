package ru.devdem.autoServerControl.functions;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.UuidUtils;
import net.kyori.adventure.text.Component;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.slf4j.Logger;
import ru.devdem.autoServerControl.AutoServerControl;
import ru.devdem.autoServerControl.classes.DevdemUser;
import ru.devdem.autoServerControl.utils.DatabaseManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Обрабатывает смешанный вход Java online/offline и Bedrock-игроков через Geyser.
 */
public class OfflineMode {

    /** Singleton-экземпляр обработчика offline-mode логики. */
    private static OfflineMode instance;

    /** Главный класс плагина; обновляется при повторном получении singleton. */
    private AutoServerControl plugin;

    /** Логгер плагина. */
    private final Logger logger;

    /** Менеджер MySQL-подключений. */
    private final DatabaseManager databaseManager;

    /** Игроки, находящиеся между PreLogin/GameProfileRequest/Login фазами. */
    private final Set<DevdemUser> connectingPlayers = ConcurrentHashMap.newKeySet();

    /**
     * Создает обработчик offline-mode логики.
     */
    private OfflineMode(AutoServerControl plugin) {
        instance = this;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        databaseManager = DatabaseManager.getInstance();
    }

    /**
     * Возвращает singleton-экземпляр обработчика.
     */
    public static OfflineMode getInstance(AutoServerControl plugin) {
        if (instance == null) {
            return new OfflineMode(plugin);
        } else {
            instance.plugin = plugin;
            return instance;
        }
    }

    /**
     * Проверяет, пришел ли игрок через Geyser как Bedrock-клиент.
     */
    private boolean isBedrock(String username) {
        for (GeyserConnection con : GeyserApi.api().onlineConnections()) {
            if (con.bedrockUsername().equalsIgnoreCase(username)) {
                return true;
            }
        }
        return false;
    }


    /**
     * Ранняя фаза входа: решает, какой тип профиля нужен игроку, и создает запись в БД.
     */
    public void onPreLogin(PreLoginEvent event) {
        String username = event.getUsername();
        boolean isBedrock = isBedrock(username);
        logger.info("onPreLogin: {} isBedrock: {}", username, isBedrock);

        DevdemUser connectingUser = findConnectingUser(username);
        if (connectingUser != null && connectingUser.shouldRetryInOfflineMode) {
            // Игрок уже был добавлен в базу и заходит повторно, теперь можно форсировать offline-mode.
            connectingUser.setType(DevdemUser.UserType.OFFLINE);
            connectingUser.shouldRetryInOfflineMode = false;
            connectingUser.updateUser();
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
            logger.info("Форсируем Offline мод для {}", username);
            return;
        }

        try (Connection conn = databaseManager.getConnection()) {
            logger.info("Ищем {} в БД", username);
            var stmt = conn.prepareStatement(
                    "SELECT * FROM `users` WHERE username = ?"
            );
            stmt.setString(1, username);
            var rs = stmt.executeQuery(); // ищем сначала пользователя

            DevdemUser user = new DevdemUser();
            if (isBedrock) user.setType(DevdemUser.UserType.BEDROCK);
            else user.setType(DevdemUser.UserType.ONLINE);
            user.setUsername(username);
            user.shouldRetryInOfflineMode = false;
            String ip = "unknown";
            if (event.getConnection().getRemoteAddress() != null) {
                ip = event.getConnection()
                        .getRemoteAddress()
                        .getAddress()
                        .getHostAddress();
            }
            user.setLastIp(ip); // делаем полу-пустышку чтобы удобнее было использовать после.

            if (rs.next()) { // игрок найден в бд
                logger.info("Игрок {} найден в БД", username);
                user = DevdemUser.fromResultSet(rs);
                if (user.getType() == DevdemUser.UserType.OFFLINE) { // если в БД уже написано, что он оффлайн - так и делаем
                    event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
                }
            } else {
                // Игрока нет в бд, пробуем его подключить (перехватываем в disconnect) и если всё ок, то пишем в базу.
                // Но сначала надо записать, что он оффлайн:
                logger.info("{} нет в БД, пишем его в базу.", username);
                var stmtup = conn.prepareStatement(
                        "INSERT INTO users (username, type, uuid, lastip, lastdate) " +
                                "VALUES (?, ?, ?, ?, NOW()) " +
                                "ON DUPLICATE KEY UPDATE " +
                                "type = VALUES(type), " +
                                "lastip = VALUES(lastip), " +
                                "lastdate = NOW()"
                );
                stmtup.setString(1, username);
                stmtup.setString(2, user.getType().name().toLowerCase());
                stmtup.setNull(3, 0);
                stmtup.setString(4, ip);
                stmtup.executeUpdate(); // надо теперь получить все данные из БД

                var getDataSQL = conn.prepareStatement("SELECT * FROM `users` WHERE `username` = ?");
                getDataSQL.setString(1, username);
                var resultSet = getDataSQL.executeQuery();
                if (resultSet.next()) {
                    user = DevdemUser.fromResultSet(resultSet);
                }
                user.shouldRetryInOfflineMode = true;
                connectingPlayers.add(user);
            }

        } catch (SQLException e) {
            logger.error("Ошибка SQL: ", e);
        }
    }

    /**
     * Финальная фаза входа: сохраняет UUID игрока и закрывает временное состояние connectingPlayers.
     */
    public void onLoginEvent(LoginEvent event) {
        String username = event.getPlayer().getUsername();
        DevdemUser user = findConnectingUser(username);
        logger.info("onLoginEvent for {}", username);
        if (user != null) {
            if (user.shouldRetryInOfflineMode) {
                user.setType(DevdemUser.UserType.ONLINE);
                user.shouldRetryInOfflineMode = false;
                user.updateUser();
            }
            if (user.getUuid() == null) {
                user.setUuid(event.getPlayer().getGameProfile().getId().toString());
                user.updateUser();
            }
            connectingPlayers.remove(user);
        }
    }


    /**
     * При необходимости подменяет GameProfile на offline UUID или проверяет Bedrock UUID.
     */
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        String username = event.getUsername();
        logger.info("onGameProfileRequest for {}", username);
        if (username == null) return;
        DevdemUser user = findConnectingUser(username);
        if (user == null) return;
        if (user.getType() == DevdemUser.UserType.OFFLINE) {
            UUID offlineUuid;
            if (user.getUuid() != null) {
                offlineUuid = UuidUtils.fromUndashed(user.getUuid());
            } else {
                offlineUuid = UuidUtils.generateOfflinePlayerUuid(username);
            }
            GameProfile offlineProfile = new GameProfile(offlineUuid, username, Collections.emptyList());
            event.setGameProfile(offlineProfile);
            user.setUuid(offlineUuid.toString());
            user.updateUser();
            logger.info("Используем оффлайн режим для {}", username);
        }
        if (user.getType() == DevdemUser.UserType.BEDROCK) {
            if (user.getUuid() == null) {
                user.setUuid(event.getGameProfile().getUndashedId());
                user.updateUser();
            } else {
                if (!Objects.equals(user.getUuid(), event.getGameProfile().getUndashedId())) {
                    var optionalPlayer = plugin.server.getPlayer(UUID.fromString(event.getGameProfile().getUndashedId()));
                    optionalPlayer.ifPresent(player -> player.disconnect(Component.text("ID не соответствует.")));
                }
            }
        }
    }

    /**
     * Зарезервировано под очистку состояния при отключении игрока.
     */
    public void onDisconnectEvent(DisconnectEvent event) {

    }

    /**
     * Ищет временного пользователя по нику между фазами входа.
     */
    private DevdemUser findConnectingUser(String username) {
        for (DevdemUser connectingUser : connectingPlayers) {
            if (Objects.equals(connectingUser.getUsername(), username)) {
                return connectingUser;
            }
        }
        return null;
    }

}
