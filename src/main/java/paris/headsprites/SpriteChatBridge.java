package paris.headsprites;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SpriteChatBridge implements Listener {
    private static final Map<UUID, String> RENDERED = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Component rendered = ChatListener.renderMessageBody(player, event.message());
        String json = GsonComponentSerializer.gson().serialize(rendered);
        RENDERED.put(player.getUniqueId(), json);
    }

    public static String getRendered(UUID uuid) {
        return uuid == null ? null : RENDERED.get(uuid);
    }
}
