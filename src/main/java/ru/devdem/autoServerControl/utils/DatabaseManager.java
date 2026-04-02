package ru.devdem.autoServerControl.utils;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ru.devdem.autoServerControl.AutoServerControl;

import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseManager {
    private HikariDataSource dataSource;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;

    private static DatabaseManager instance;
    private boolean connected;

    private DatabaseManager(String host, int port, String database, String username, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    public static DatabaseManager getInstance() {
        if (instance != null) {
            return instance;
        } else {
            throw new NullPointerException("Database ещё не был создан.");
        }
    }

    public static DatabaseManager getInstance(String host, int port, String database, String username, String password) {
        if (instance != null) {
            return instance;
        } else {
            instance = new DatabaseManager(host, port, database, username, password);
            return instance;
        }
    }

    public void connect() {
        HikariConfig config = new HikariConfig();

        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database);
        config.setUsername(username);
        config.setPassword(password);

        config.setMaximumPoolSize(10);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");

        dataSource = new HikariDataSource(config);
    }

    public Connection getConnection() throws SQLException {
        if (!connected) {
            connect();
            connected = true;
        }
        return dataSource.getConnection();
    }

    public void disconnect() {
        if (dataSource != null) {
            connected = false;
            dataSource.close();
        }
    }
}
