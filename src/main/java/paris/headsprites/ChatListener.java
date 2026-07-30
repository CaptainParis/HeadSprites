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
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Pattern SPRITE_TAG_PATTERN =
            Pattern.compile("<(head|sprite|seq):[^<>]+>", Pattern.CASE_INSENSITIVE);

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        event.message(renderMessageBody(player, event.message()));
    }

    private static Component renderMessageBody(Player sender, Component message) {
        if (sender.hasPermission("headsprites.chatformat")) {
            String raw = PlainTextComponentSerializer.plainText().serialize(message);
            try {
                return MINI_MESSAGE.deserialize(raw, SpriteTags.resolver());
            } catch (Exception ignored) {
            }
        }
        return resolveSpriteTags(message);
    }

    private static Component resolveSpriteTags(Component message) {
        TextReplacementConfig replacer = TextReplacementConfig.builder()
                .match(SPRITE_TAG_PATTERN)
                .replacement((match, builder) -> {
                    try {
                        return MINI_MESSAGE.deserialize(match.group(), SpriteTags.resolver());
                    } catch (Exception ex) {
                        return builder;
                    }
                })
                .build();
        return message.replaceText(replacer);
    }
}
