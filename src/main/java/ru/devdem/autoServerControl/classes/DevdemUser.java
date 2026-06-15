package ru.devdem.autoServerControl.classes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.devdem.autoServerControl.utils.DatabaseManager;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Пользователь из таблицы users.
 */
public class DevdemUser {

    /** Логгер для ошибок сохранения пользователя. */
    private static final Logger LOGGER = LoggerFactory.getLogger(DevdemUser.class);

    /** Внутренний ID строки в базе данных. */
    private int id;

    /** Ник игрока. */
    private String username;

    /**
     * Тип игрока, который определяет режим авторизации и UUID.
     */
    public enum UserType {
        /** Java-игрок, которому нужно форсировать offline-mode. */
        OFFLINE,

        /** Обычный Java-игрок с online-mode профилем. */
        ONLINE,

        /** Bedrock-игрок, пришедший через Geyser. */
        BEDROCK;

        /**
         * Парсит значение из базы данных.
         */
        public static UserType fromString(String value) {
            return UserType.valueOf(value.toUpperCase());
        }
    }

    /** Тип игрока: online/offline/bedrock. */
    private UserType type;

    /** UUID игрока, сохраненный в базе. */
    private String uuid;

    /** Последний IP-адрес игрока. */
    private String lastIp;

    /** Дата последнего подключения. */
    private Timestamp lastDate;

    /** Хэш пароля, если он используется внешней авторизацией. */
    private String passwordHash;

    /** Менеджер базы для сохранения изменений пользователя. */
    private DatabaseManager manager;

    /**
     * true, если при следующей попытке входа игрока нужно принудительно пустить в offline-mode.
     */
    public boolean shouldRetryInOfflineMode = false;


    /**
     * Пустой конструктор нужен для временного объекта до чтения строки из базы.
     */
    public DevdemUser() {
    }


    /**
     * Создает пользователя из всех полей базы данных.
     */
    public DevdemUser(int id, String username, UserType type, String uuid,
                      String lastIp, Timestamp lastDate, String passwordHash) {
        this.id = id;
        this.username = username;
        this.type = type;
        this.uuid = uuid;
        this.lastIp = lastIp;
        this.lastDate = lastDate;
        this.passwordHash = passwordHash;
        manager = DatabaseManager.getInstance();
    }


    /**
     * Собирает объект пользователя из ResultSet.
     */
    public static DevdemUser fromResultSet(ResultSet rs) throws SQLException {
        return new DevdemUser(
                rs.getInt("id"),
                rs.getString("username"),
                UserType.fromString(rs.getString("type")),
                rs.getString("uuid"),
                rs.getString("lastip"),
                rs.getTimestamp("lastdate"),
                rs.getString("passwordhash")
        );
    }

    // --- Геттеры и сеттеры ---

    /** Возвращает ID пользователя в базе. */
    public int getId() {
        return id;
    }

    /** Устанавливает ID пользователя в базе. */
    public void setId(int id) {
        this.id = id;
    }

    /** Возвращает ник игрока. */
    public String getUsername() {
        return username;
    }

    /** Устанавливает ник игрока. */
    public void setUsername(String username) {
        this.username = username;
    }

    /** Возвращает тип авторизации игрока. */
    public UserType getType() {
        return type;
    }

    /** Устанавливает тип авторизации игрока. */
    public void setType(UserType type) {
        this.type = type;
    }

    /** Возвращает сохраненный UUID игрока. */
    public String getUuid() {
        return uuid;
    }

    /** Устанавливает UUID игрока. */
    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    /** Возвращает последний известный IP игрока. */
    public String getLastIp() {
        return lastIp;
    }

    /** Устанавливает последний известный IP игрока. */
    public void setLastIp(String lastIp) {
        this.lastIp = lastIp;
    }

    /** Возвращает дату последнего входа игрока. */
    public Timestamp getLastDate() {
        return lastDate;
    }

    /** Устанавливает дату последнего входа игрока. */
    public void setLastDate(Timestamp lastDate) {
        this.lastDate = lastDate;
    }

    /** Возвращает хэш пароля игрока. */
    public String getPasswordHash() {
        return passwordHash;
    }

    /** Устанавливает хэш пароля игрока. */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * Сохраняет текущие поля пользователя в таблицу users.
     */
    public void updateUser() {
        if (manager == null) {
            manager = DatabaseManager.getInstance();
        }
        try (Connection conn = manager.getConnection()) {
            var stmtup = conn.prepareStatement(
                    "UPDATE `users` SET" +
                            "`username`=?," +
                            "`type`=?," +
                            "`uuid`=?," +
                            "`lastip`=?," +
                            "`lastdate`=?," +
                            "`passwordHash`=?" +
                            " WHERE `id` = ?"
            );
            stmtup.setString(1, username);
            stmtup.setString(2, type.toString());
            stmtup.setString(3, uuid);
            stmtup.setString(4, lastIp);
            stmtup.setTimestamp(5, Objects.requireNonNullElseGet(lastDate, () -> Timestamp.valueOf(LocalDateTime.now())));
            stmtup.setString(6, passwordHash);
            stmtup.setInt(7, id);
            stmtup.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Не удалось обновить пользователя {}", username, e);
        }
    }

    /**
     * Возвращает краткое представление пользователя для логов и отладки.
     */
    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", type=" + type +
                ", uuid='" + uuid + '\'' +
                ", lastIp='" + lastIp + '\'' +
                ", lastDate=" + lastDate +
                '}';
    }
}
