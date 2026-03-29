package ru.devdem.autoServerControl.functions;

import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.UuidUtils;
import org.slf4j.Logger;
import ru.devdem.autoServerControl.AutoServerControl;

import java.util.*;

public class OfflineMode {

    private static OfflineMode instance;
    private AutoServerControl plugin;
    private Logger logger;

    private Set<String> onlineUsers = new HashSet<>();

    private OfflineMode(AutoServerControl plugin) {
        instance = this;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public static OfflineMode getInstance() {
        if (instance == null) {
            return new OfflineMode(null);
        } else {
            return instance;
        }
    }

    public static OfflineMode getInstance(AutoServerControl plugin) {
        if (instance == null) {
            return new OfflineMode(plugin);
        } else {
            instance.plugin = plugin;
            return instance;
        }
    }

    public void updateOnlineUsers(Set<String> users) {
        onlineUsers = users;
    }

    public void onPreLogin(PreLoginEvent event) {
        String username = event.getUsername();
        if (username != null && !isUserConfigured(username)) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
        }
    }

    public void onGameProfileRequest(GameProfileRequestEvent event) {
        String username = event.getUsername();
        if (username != null && !isUserConfigured(username)) {
            UUID offlineUuid = UuidUtils.generateOfflinePlayerUuid(username);
            GameProfile offlineProfile = new GameProfile(offlineUuid, username, Collections.emptyList());
            event.setGameProfile(offlineProfile);
            logger.info("Используем оффлайн режим для {}", username);
        }
    }

    private boolean isUserConfigured(String username) {
        String normalized = normalizeUsername(username);
        return onlineUsers.contains(normalized);
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

}
