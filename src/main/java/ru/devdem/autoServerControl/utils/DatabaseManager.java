package ru.devdem.autoServerControl.utils;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Управляет единственным пулом подключений к MySQL.
 */
public class DatabaseManager {

    /** Пул соединений HikariCP, создается лениво при первом запросе подключения. */
    private HikariDataSource dataSource;

    /** Адрес MySQL-сервера. */
    private final String host;

    /** Порт MySQL-сервера. */
    private final int port;

    /** Имя базы данных. */
    private final String database;

    /** Пользователь MySQL. */
    private final String username;

    /** Пароль пользователя MySQL. */
    private final String password;

    /** Singleton-экземпляр менеджера базы. */
    private static DatabaseManager instance;

    /** Флаг, что пул уже был инициализирован. */
    private boolean connected;

    /**
     * Создает менеджер с параметрами подключения из config.yml.
     */
    private DatabaseManager(String host, int port, String database, String username, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    /**
     * Возвращает уже созданный менеджер.
     *
     * @throws NullPointerException если конфиг базы еще не был загружен
     */
    public static DatabaseManager getInstance() {
        if (instance != null) {
            return instance;
        } else {
            throw new NullPointerException("Database ещё не был создан.");
        }
    }

    /**
     * Инициализирует singleton при загрузке конфига или возвращает уже созданный экземпляр.
     */
    public static DatabaseManager getInstance(String host, int port, String database, String username, String password) {
        if (instance != null) {
            return instance;
        } else {
            instance = new DatabaseManager(host, port, database, username, password);
            return instance;
        }
    }

    /**
     * Создает HikariCP-пул подключений.
     */
    public void connect() {
        HikariConfig config = new HikariConfig();

        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database);
        config.setUsername(username);
        config.setPassword(password);

        config.setMaximumPoolSize(10);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");

        dataSource = new HikariDataSource(config);
    }

    /**
     * Возвращает SQL-соединение из пула, создавая пул при первом обращении.
     */
    public Connection getConnection() throws SQLException {
        if (!connected) {
            connect();
            connected = true;
        }
        return dataSource.getConnection();
    }

    /**
     * Закрывает пул подключений.
     */
    public void disconnect() {
        if (dataSource != null) {
            connected = false;
            dataSource.close();
        }
    }
}
