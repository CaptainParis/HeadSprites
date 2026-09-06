package paris.headsprites;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import paris.headsprites.sprite.SpriteTags;

import java.util.regex.Pattern;

public final class ChatListener implements Listener {
    private static final MiniMessage MINI_MESSAGE = SpriteTags.chatMiniMessage();
    private static final Pattern SPRITE_TAG_PATTERN =
            Pattern.compile("<(head_texture|head|sprite|seq|anim)\\s*:[^<>]+>", Pattern.CASE_INSENSITIVE);
    private static final TextReplacementConfig SPRITE_REPLACER = TextReplacementConfig.builder()
            .match(SPRITE_TAG_PATTERN)
            .replacement((match, builder) -> {
                try {
                    return SpriteTags.sanitizeHeads(MINI_MESSAGE.deserialize(match.group()));
                } catch (Exception ex) {
                    return builder;
                }
            })
            .build();

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        try {
            Component rendered = renderMessageBody(event.getPlayer(), event.message());
            if (rendered != null) {
                event.message(rendered);
            }
        } catch (Exception e) {
            HeadSprites.warnOnce("chat-render",
                    "Failed to render sprite tags in a chat message from " + event.getPlayer().getName()
                            + "; message left unchanged", e);
        }
    }

    public static Component renderMessageBody(Player sender, Component message) {
        if (sender == null || message == null) {
            return message;
        }
        try {
            if (sender.hasPermission("headsprites.chatformat")) {
                String raw = PlainTextComponentSerializer.plainText().serialize(message);
                try {
                    return SpriteTags.sanitizeHeads(MINI_MESSAGE.deserialize(raw));
                } catch (Exception ignored) {
                }
            }
            return SpriteTags.sanitizeHeads(message.replaceText(SPRITE_REPLACER));
        } catch (Exception e) {
            HeadSprites.warnOnce("chat-render-body",
                    "Failed to render sprite tags for " + sender.getName() + "; message left unchanged", e);
            return message;
        }
    }
}
