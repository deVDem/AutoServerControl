package ru.devdem.autoServerControl.functions;

import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.UuidUtils;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.slf4j.Logger;
import ru.devdem.autoServerControl.AutoServerControl;
import ru.devdem.autoServerControl.classes.DevdemUser;
import ru.devdem.autoServerControl.utils.DatabaseManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

public class OfflineMode {

    private static OfflineMode instance;
    private AutoServerControl plugin;
    private final Logger logger;
    private final DatabaseManager databaseManager;
    private final Set<DevdemUser> connectingPlayers = new HashSet<>();

    private OfflineMode(AutoServerControl plugin) {
        instance = this;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        databaseManager = DatabaseManager.getInstance();
    }

    public static OfflineMode getInstance() {
        return Objects.requireNonNullElseGet(instance, () -> new OfflineMode(null));
    }

    public static OfflineMode getInstance(AutoServerControl plugin) {
        if (instance == null) {
            return new OfflineMode(plugin);
        } else {
            instance.plugin = plugin;
            return instance;
        }
    }

    public void onPreLogin(PreLoginEvent event) {
        String username = event.getUsername();
        GeyserConnection conng = GeyserApi.api().connectionByUuid(event.getUniqueId()); // below 1.19.2 it's null
        boolean isBedrock = conng != null;
        logger.info("onPreLogin: {} isBedrock: {}", username, isBedrock);

        DevdemUser c_user = SearchConnectingUser(username);
        if (c_user != null && c_user.canyouagain) {
            // обработчик если мы уже игрока попросили перезайти и пробуем с ним провернуть оффлайн мод
            c_user.setType(DevdemUser.UserType.OFFLINE);
            c_user.canyouagain = false;
            c_user.updateUser();
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
            logger.info("Форсируем Offline мод для {}", username);
            return;
        }

        try (Connection conn = databaseManager.getConnection()) {
            logger.info("Ищем {} в БД", username);
            var stmt = conn.prepareStatement(
                    "SELECT * FROM `devdem_users` WHERE username = ?"
            );
            stmt.setString(1, username);
            var rs = stmt.executeQuery(); // ищем сначала пользователя

            DevdemUser user = new DevdemUser();
            if (isBedrock) user.setType(DevdemUser.UserType.BEDROCK);
            else user.setType(DevdemUser.UserType.ONLINE);
            user.setUsername(username);
            user.canyouagain = false;
            String ip = "unknown";
            if (event.getConnection().getRemoteAddress() != null) {
                ip = event.getConnection()
                        .getRemoteAddress()
                        .getAddress()
                        .getHostAddress(); // какое спагетти..
            }
            user.setLastIp(ip); // делаем полу-пустышку чтобы удобнее было использовать после.

            if (rs.next()) { // игрок найден в бд
                logger.info("Игрок {} найден в БД", username);
                user = DevdemUser.fromResultSet(rs);
                connectingPlayers.add(user);
                if (user.getType() == DevdemUser.UserType.OFFLINE) { // если в БД уже написано, что он оффлайн - так и делаем
                    event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
                }
            } else {
                // Игрока нет в бд, пробуем его подключить (перехватываем в disconnect) и если всё ок, то пишем в базу.
                // Но сначала надо записать, что он оффлайн:
                logger.info("{} нет в БД, пишем его в базу.", username);
                var stmtup = conn.prepareStatement(
                        "INSERT INTO devdem_users (username, type, uuid, lastip, lastdate) " +
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

                var getDataSQL = conn.prepareStatement("SELECT * FROM `devdem_users` WHERE `username` = ?");
                getDataSQL.setString(1, username);
                var resultSet = getDataSQL.executeQuery();
                if (resultSet.next()) {
                    user = DevdemUser.fromResultSet(resultSet);
                }
                connectingPlayers.add(user);
            }

        } catch (SQLException e) {
            logger.error("Ошибка SQL: ", e);
        }
    }

    public void onLoginEvent(LoginEvent event) {
        String username = event.getPlayer().getUsername();
        DevdemUser user = SearchConnectingUser(username);
        logger.info("onLoginEvent for {}", username);
        if (user != null) {
            if (user.canyouagain) {
                user.setType(DevdemUser.UserType.ONLINE);
                user.canyouagain = false;
                user.updateUser();
            }
            if (user.getUuid() == null) {
                user.setUuid(event.getPlayer().getGameProfile().getId().toString()); // колбаса
                user.updateUser();
            }
            connectingPlayers.remove(user);
        }
    }


    public void onGameProfileRequest(GameProfileRequestEvent event) {
        String username = event.getUsername();
        logger.info("onGameProfileRequest for {}", username);
        if (username == null) return;
        DevdemUser user = SearchConnectingUser(username);
        if (user == null) return;
        if (user.getType() == DevdemUser.UserType.OFFLINE) {
            UUID offlineUuid;
            if (user.getUuid() == null) {
                offlineUuid = UuidUtils.fromUndashed(user.getUuid());
            } else {
                offlineUuid = UuidUtils.generateOfflinePlayerUuid(username);
            }
            GameProfile offlineProfile = new GameProfile(offlineUuid, username, Collections.emptyList());
            event.setGameProfile(offlineProfile);
            logger.info("Используем оффлайн режим для {}", username);
        }
    }

    private DevdemUser SearchConnectingUser(String username) {
        for (DevdemUser p_user : connectingPlayers) {
            if (Objects.equals(p_user.getUsername(), username)) {
                return p_user;
            }
        }
        return null;
    }

}
