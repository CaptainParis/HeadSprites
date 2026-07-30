package paris.headsprites;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import paris.headsprites.sprite.SpriteCommand;
import paris.headsprites.sprite.SpriteManager;
import paris.headsprites.sprite.SpriteWebServer;

public final class HeadSprites extends JavaPlugin {
    private static HeadSprites instance;
    private SpriteWebServer spriteWebServer;

    public static HeadSprites getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        try {
            SpriteManager.initialize(this);
            getLogger().info("Sprite system initialized successfully");

            SpriteManager spriteManager = SpriteManager.getInstance();
            if (spriteManager != null && spriteManager.isWebEnabled()) {
                spriteWebServer = new SpriteWebServer(this, spriteManager);
                spriteWebServer.start();
            }
        } catch (Exception e) {
            getLogger().severe("Failed to initialize sprite system: " + e.getMessage());
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
    }

    @Override
    public void onDisable() {
        if (spriteWebServer != null) {
            spriteWebServer.stop();
        }
    }
}
