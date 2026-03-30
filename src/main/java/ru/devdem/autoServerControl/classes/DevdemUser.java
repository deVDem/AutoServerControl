package ru.devdem.autoServerControl.classes;

import ru.devdem.autoServerControl.utils.DatabaseManager;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Objects;

public class DevdemUser {

    private int id;
    private String username;

    public enum UserType {
        OFFLINE,
        ONLINE,
        BEDROCK;

        public static UserType fromString(String value) {
            return UserType.valueOf(value.toUpperCase());
        }
    }

    private UserType type;
    private String uuid;
    private String lastIp;
    private Timestamp lastDate;
    private String passwordHash;

    private DatabaseManager manager;

    public boolean canyouagain = false;


    public DevdemUser() {
    }


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

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public UserType getType() {
        return type;
    }

    public void setType(UserType type) {
        this.type = type;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getLastIp() {
        return lastIp;
    }

    public void setLastIp(String lastIp) {
        this.lastIp = lastIp;
    }

    public Timestamp getLastDate() {
        return lastDate;
    }

    public void setLastDate(Timestamp lastDate) {
        this.lastDate = lastDate;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void updateUser() {
        if (manager == null) {
            manager = DatabaseManager.getInstance();
        }
        try (Connection conn = manager.getConnection()) {
            var stmtup = conn.prepareStatement(
                    "UPDATE `devdem_users` SET" +
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

        }
    }

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
