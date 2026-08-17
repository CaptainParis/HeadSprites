package paris.headsprites;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import paris.headsprites.sprite.SpriteManager;

import java.util.Locale;

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
        String lower = params.toLowerCase(Locale.ROOT);
        if (lower.startsWith("anim_")) {
            return renderAnimationFrame(lower.substring("anim_".length()));
        }
        return null;
    }

    private String renderAnimationFrame(String name) {
        SpriteManager manager = SpriteManager.getInstance();
        if (manager == null || name.isBlank()) {
            return EMPTY_COMPONENT_JSON;
        }
        Component frame = manager.buildAnimation(name);
        if (frame == null) {
            return EMPTY_COMPONENT_JSON;
        }
        return GsonComponentSerializer.gson().serialize(frame);
    }
}
