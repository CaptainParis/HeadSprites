package paris.headsprites;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public final class SpriteChatPlaceholder extends PlaceholderExpansion {
    private static final String EMPTY_COMPONENT_JSON = "{\"text\":\"\"}";
    private final HeadSprites plugin;

    public SpriteChatPlaceholder(HeadSprites plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "headsprites";
    }

    @Override
    public @NotNull String getAuthor() {
        return "CaptainParis";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        if (params.equalsIgnoreCase("msg")) {
            String encoded = SpriteChatBridge.getRendered(player.getUniqueId());
            return encoded == null ? EMPTY_COMPONENT_JSON : encoded;
        }
        return null;
    }
}
