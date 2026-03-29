package ru.devdem.autoServerControl.functions;

import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.UuidUtils;
import org.slf4j.Logger;
import ru.devdem.autoServerControl.AutoServerControl;
import ru.devdem.autoServerControl.utils.Utils;

import java.util.*;

public class OfflineMode {

    private static OfflineMode instance;
    private AutoServerControl plugin;
    private final Logger logger;

    private Set<String> onlineUsers = new HashSet<>();

    private OfflineMode(AutoServerControl plugin) {
        instance = this;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
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

    public void updateOnlineUsers(Set<String> users) {
        onlineUsers = users;
    }

    public void onPreLogin(PreLoginEvent event) {
        String username = event.getUsername();
        if (username != null && isUserNotConfigured(username)) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
        }
    }

    public void onGameProfileRequest(GameProfileRequestEvent event) {
        String username = event.getUsername();
        if (username != null && isUserNotConfigured(username)) {
            UUID offlineUuid = UuidUtils.generateOfflinePlayerUuid(username);
            GameProfile offlineProfile = new GameProfile(offlineUuid, username, Collections.emptyList());
            event.setGameProfile(offlineProfile);
            logger.info("Используем оффлайн режим для {}", username);
        }
    }

    private boolean isUserNotConfigured(String username) {
        String normalized = Utils.normalizeUsername(username);
        return !onlineUsers.contains(normalized);
    }

}
