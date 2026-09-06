package paris.headsprites;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SpriteChatBridge implements Listener {
    private static final int MAX_ENTRIES = 1024;
    private static final Map<UUID, String> RENDERED = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        try {
            Player player = event.getPlayer();
            Component rendered = ChatListener.renderMessageBody(player, event.message());
            String json = GsonComponentSerializer.gson().serialize(rendered == null ? event.message() : rendered);
            if (RENDERED.size() >= MAX_ENTRIES && !RENDERED.containsKey(player.getUniqueId())) {
                RENDERED.clear();
            }
            RENDERED.put(player.getUniqueId(), json);
        } catch (Exception e) {
            HeadSprites.warnOnce("chat-bridge",
                    "Failed to cache rendered chat for %headsprites_msg%; placeholder will be empty", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        RENDERED.remove(event.getPlayer().getUniqueId());
    }

    public static String getRendered(UUID uuid) {
        return uuid == null ? null : RENDERED.get(uuid);
    }
}
