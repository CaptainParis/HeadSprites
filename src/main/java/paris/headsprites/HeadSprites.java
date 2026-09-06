package paris.headsprites;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import paris.headsprites.sprite.MineSkinClient;
import paris.headsprites.sprite.SpriteCommand;
import paris.headsprites.sprite.SpriteManager;
import paris.headsprites.sprite.SpriteWebServer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class HeadSprites extends JavaPlugin {
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static volatile HeadSprites instance;
    private SpriteWebServer spriteWebServer;

    public static HeadSprites getInstance() {
        return instance;
    }

    public static void warnOnce(String key, String context, Throwable error) {
        if (!WARNED.add(key)) {
            return;
        }
        HeadSprites plugin = instance;
        Logger logger = plugin != null ? plugin.getLogger() : Logger.getLogger("HeadSprites");
        logger.log(Level.WARNING, context + " (further '" + key + "' failures are suppressed): "
                + (error == null ? "unknown error" : error), error);
    }

    @Override
    public void onEnable() {
        instance = this;

        try {
            SpriteManager.initialize(this);
            getLogger().info("Sprite system initialized successfully");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize sprite system; sprite tags will render as '?'", e);
        }

        SpriteManager spriteManager = SpriteManager.getInstance();
        if (spriteManager != null && spriteManager.isWebEnabled()) {
            try {
                spriteWebServer = new SpriteWebServer(this, spriteManager);
                spriteWebServer.start();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to start sprite web UI; continuing without it", e);
                spriteWebServer = null;
            }
        }

        SpriteCommand spriteCommand = new SpriteCommand();
        PluginCommand command = getCommand("sprite");
        if (command != null) {
            command.setExecutor(spriteCommand);
            command.setTabCompleter(spriteCommand);
        } else {
            getLogger().severe("Command 'sprite' is missing from plugin.yml; /sprite will not work.");
        }

        getServer().getPluginManager().registerEvents(new ChatListener(), this);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                getServer().getPluginManager().registerEvents(new SpriteChatBridge(), this);
                new SpriteChatPlaceholder(this).register();
                getLogger().info("PlaceholderAPI found; registered %headsprites_msg% for Skript/SkBee chat bridging.");
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to register PlaceholderAPI expansion; chat bridge disabled", e);
            }
        }
    }

    @Override
    public void onDisable() {
        try {
            SpriteCommand.stopAllDisplays();
        } catch (Exception e) {
            getLogger().warning("Failed to remove animated displays: " + e.getMessage());
        }
        getServer().getScheduler().cancelTasks(this);
        if (spriteWebServer != null) {
            spriteWebServer.stop();
            spriteWebServer = null;
        }
        MineSkinClient.shutdown();
        SpriteManager.shutdownInstance();
        instance = null;
    }
}
