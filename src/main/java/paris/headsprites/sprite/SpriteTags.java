package paris.headsprites.sprite;

import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpriteTags {
    private static final Pattern CUSTOM_TAG =
            Pattern.compile("<(head|seq|anim|sprite):([^<>]+)>", Pattern.CASE_INSENSITIVE);
    private static final Component UNKNOWN_HEAD =
            Component.text("?").color(NamedTextColor.DARK_GRAY);
    private static final MiniMessage MINI = buildMini(false);
    private static final MiniMessage CHAT_MINI = buildMini(true);

    private SpriteTags() {
    }

    /**
     * MiniMessage that resolves {@code <head>} before Adventure's built-in tag.
     * Adventure's default {@code <head:Name>} is a dynamic name lookup; 1.21.11
     * clients crash with CME if that lookup races the tab list.
     */
    public static MiniMessage miniMessage() {
        return MINI;
    }

    public static MiniMessage chatMiniMessage() {
        return CHAT_MINI;
    }

    private static MiniMessage buildMini(boolean chatSnapshot) {
        return MiniMessage.builder()
                .emitVirtuals(false)
                .tags(TagResolver.resolver(resolver(chatSnapshot), StandardTags.defaults()))
                .build();
    }

    /**
     * Rewrite sprite tags into TAB {@code head_texture} syntax. Never emits
     * {@code <head:username>}, which would crash 1.21.11 clients on join.
     */
    public static String toTabMini(String mini) {
        if (mini == null || mini.isBlank()) {
            return mini == null ? "" : mini;
        }
        Matcher matcher = CUSTOM_TAG.matcher(mini);
        StringBuilder out = new StringBuilder(mini.length() + 32);
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    rewriteTabTag(matcher.group(1), matcher.group(2))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String rewriteTabTag(String name, String inner) {
        String kind = name.toLowerCase(java.util.Locale.ROOT);
        String arg = inner == null ? "" : inner.trim();
        return switch (kind) {
            case "head" -> tabHead(arg);
            case "seq" -> tabSequence(arg);
            case "anim" -> tabAnimation(arg, false);
            case "sprite" -> tabAtlasSprite(arg);
            default -> "<" + name + ":" + inner + ">";
        };
    }

    private static String tabHead(String arg) {
        SpriteManager manager = SpriteManager.getInstance();
        if (manager != null) {
            SpriteManager.Sprite stored = manager.getSprite(arg);
            if (stored != null) {
                return manager.tabHeadTag(stored);
            }
            String anim = tabAnimation(arg, true);
            if (!anim.isBlank()) {
                return anim;
            }
        }
        if (looksLikeSpriteKey(arg)) {
            return "";
        }
        UUID uuid = tryParseUuid(arg);
        Player online = uuid != null ? Bukkit.getPlayer(uuid) : matchOnline(arg);
        if (online != null) {
            return tabTextureTag(online);
        }
        return "";
    }

    private static String tabTextureTag(Player player) {
        ProfileProperty skin = texturesOf(player);
        if (skin == null) {
            return "";
        }
        String hash = SpriteManager.textureHashFromValue(skin.getValue());
        return hash == null ? "" : "<head_texture:" + hash + ">";
    }

    private static String tabSequence(String name) {
        SpriteManager manager = SpriteManager.getInstance();
        if (manager == null) {
            return "";
        }
        List<String> heads = manager.getSequence(name);
        if (heads == null || heads.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String head : heads) {
            SpriteManager.Sprite sprite = manager.getSprite(head);
            if (sprite != null) {
                out.append(manager.tabHeadTag(sprite));
            }
        }
        return out.toString();
    }

    private static String tabAnimation(String name, boolean firstFrame) {
        SpriteManager manager = SpriteManager.getInstance();
        if (manager == null) {
            return "";
        }
        SpriteManager.Animation animation = manager.getAnimation(name);
        if (animation == null || animation.frames().isEmpty()) {
            return "";
        }
        int index = firstFrame ? 0 : manager.currentFrameIndex(animation);
        List<String> row = animation.frames().get(index);
        StringBuilder out = new StringBuilder();
        for (String head : row) {
            SpriteManager.Sprite sprite = manager.getSprite(head);
            if (sprite != null) {
                out.append(manager.tabHeadTag(sprite));
            }
        }
        return out.toString();
    }

    private static String tabAtlasSprite(String inner) {
        if (inner.indexOf('"') >= 0) {
            return "<sprite:" + inner + ">";
        }
        String[] parts = inner.split(":", 2);
        try {
            if (parts.length == 1) {
                return "<sprite:\"" + Key.key(parts[0]).asString() + "\">";
            }
            return "<sprite:\"" + Key.key(parts[0]).asString() + "\":\""
                    + Key.key(parts[1]).asString() + "\">";
        } catch (Exception ignored) {
            return "<sprite:\"" + inner + "\">";
        }
    }

    public static TagResolver resolver() {
        return resolver(false);
    }

    public static TagResolver chatResolver() {
        return resolver(true);
    }

    private static TagResolver resolver(boolean chatSnapshot) {
        return TagResolver.resolver(head(), sprite(), seq(), anim(chatSnapshot));
    }

    private static TagResolver head() {
        return TagResolver.resolver("head", SpriteTags::resolveHead);
    }

    private static TagResolver sprite() {
        return TagResolver.resolver("sprite", SpriteTags::resolveSprite);
    }

    private static TagResolver seq() {
        return TagResolver.resolver("seq", SpriteTags::resolveSeq);
    }

    private static TagResolver anim(boolean chatSnapshot) {
        return TagResolver.resolver("anim", (args, ctx) -> resolveAnim(args, ctx, chatSnapshot));
    }

    private static Tag resolveAnim(ArgumentQueue args, Context ctx, boolean chatSnapshot) {
        String name = args.popOr("anim tag requires an animation name").value();
        SpriteManager manager = SpriteManager.getInstance();
        if (manager == null) {
            throw ctx.newException("Sprite system is not initialized.");
        }
        Component frame = chatSnapshot ? manager.buildAnimationFirstFrame(name) : manager.buildAnimation(name);
        if (frame == null) {
            throw ctx.newException("Unknown or empty animation: '" + name + "'");
        }
        return Tag.selfClosingInserting(sanitizeHeads(frame));
    }

    private static Tag resolveSeq(ArgumentQueue args, Context ctx) {
        String name = args.popOr("seq tag requires a sequence name").value();
        SpriteManager manager = SpriteManager.getInstance();
        if (manager == null) {
            throw ctx.newException("Sprite system is not initialized.");
        }
        Component chained = manager.buildSequence(name);
        if (chained == null) {
            throw ctx.newException("Unknown or empty sequence: '" + name + "'");
        }
        return Tag.selfClosingInserting(sanitizeHeads(chained));
    }

    private static Tag resolveHead(ArgumentQueue args, Context ctx) {
        String arg = args.popOr("head tag requires a sprite name, username, or UUID").value();
        return Tag.selfClosingInserting(resolveHeadArgument(arg));
    }

    public static Component resolveHeadArgument(String arg) {
        if (arg == null || arg.isBlank()) {
            return UNKNOWN_HEAD;
        }
        SpriteManager manager = SpriteManager.getInstance();
        if (manager != null) {
            SpriteManager.Sprite stored = manager.getSprite(arg);
            if (stored != null) {
                return manager.buildHead(stored);
            }
            Component firstFrame = manager.buildAnimationFirstFrame(arg);
            if (firstFrame != null) {
                return sanitizeHeads(firstFrame);
            }
        }
        if (looksLikeSpriteKey(arg)) {
            return UNKNOWN_HEAD;
        }
        UUID uuid = tryParseUuid(arg);
        if (uuid != null) {
            return staticHead(uuid);
        }
        Player online = matchOnline(arg);
        if (online != null) {
            return staticHead(online);
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(arg);
        if (cached != null) {
            return staticHead(cached.getUniqueId());
        }
        return UNKNOWN_HEAD;
    }

    public static Component staticHead(Player player) {
        if (player == null) {
            return UNKNOWN_HEAD;
        }
        ProfileProperty skin = texturesOf(player);
        Component textured = texturedHead(player.getUniqueId(), skin);
        return textured != null ? textured : uuidHead(player.getUniqueId());
    }

    public static Component staticHead(UUID uuid) {
        if (uuid == null) {
            return UNKNOWN_HEAD;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return staticHead(online);
        }
        return uuidHead(uuid);
    }

    /**
     * Rewrite any name-only player heads so the 1.21.11 client never iterates the tab list.
     */
    public static Component sanitizeHeads(Component component) {
        if (component == null) {
            return Component.empty();
        }
        Component node = component;
        if (component instanceof ObjectComponent object
                && object.contents() instanceof PlayerHeadObjectContents head) {
            node = rewriteHead(head).style(component.style());
        }
        List<Component> children = component.children();
        if (children.isEmpty()) {
            return node;
        }
        List<Component> rewritten = new ArrayList<>(children.size());
        for (Component child : children) {
            rewritten.add(sanitizeHeads(child));
        }
        return node.children(rewritten);
    }

    private static Component rewriteHead(PlayerHeadObjectContents head) {
        PlayerHeadObjectContents.ProfileProperty textures = texturesProperty(head);
        UUID id = head.id();
        if (textures != null) {
            PlayerHeadObjectContents.Builder builder = ObjectContents.playerHead()
                    .hat(head.hat())
                    .profileProperty(textures);
            if (id != null) {
                builder.id(id);
            }
            if (head.texture() != null) {
                builder.texture(head.texture());
            }
            return Component.object(builder.build());
        }
        if (id != null) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                return staticHead(online);
            }
            return uuidHead(id, head.hat());
        }
        String name = head.name();
        if (name != null && !name.isBlank()) {
            return resolveHeadArgument(name);
        }
        return UNKNOWN_HEAD;
    }

    private static PlayerHeadObjectContents.ProfileProperty texturesProperty(PlayerHeadObjectContents head) {
        for (PlayerHeadObjectContents.ProfileProperty property : head.profileProperties()) {
            if ("textures".equalsIgnoreCase(property.name())
                    && property.value() != null && !property.value().isBlank()) {
                return property;
            }
        }
        return null;
    }

    private static ProfileProperty texturesOf(Player player) {
        return player.getPlayerProfile().getProperties().stream()
                .filter(property -> "textures".equals(property.getName()))
                .findFirst()
                .orElse(null);
    }

    private static Component texturedHead(UUID uuid, ProfileProperty skin) {
        if (skin == null || skin.getValue() == null || skin.getValue().isBlank()) {
            return null;
        }
        PlayerHeadObjectContents.Builder builder = ObjectContents.playerHead()
                .hat(false)
                .profileProperty(PlayerHeadObjectContents.property(
                        "textures", skin.getValue(), skin.getSignature()));
        if (uuid != null) {
            builder.id(uuid);
        }
        return Component.object(builder.build());
    }

    private static Component uuidHead(UUID uuid) {
        return uuidHead(uuid, false);
    }

    private static Component uuidHead(UUID uuid, boolean hat) {
        return Component.object(ObjectContents.playerHead()
                .id(uuid)
                .hat(hat)
                .build());
    }

    private static Tag resolveSprite(ArgumentQueue args, Context ctx) {
        String first = args.popOr("sprite tag requires a sprite key").value();
        Key spriteKey;
        Key atlasKey = null;
        if (args.hasNext()) {
            atlasKey = parseKey(first, ctx);
            spriteKey = parseKey(args.pop().value(), ctx);
        } else {
            spriteKey = parseKey(first, ctx);
        }
        ObjectContents contents = atlasKey != null
                ? ObjectContents.sprite(atlasKey, spriteKey)
                : ObjectContents.sprite(spriteKey);
        return Tag.selfClosingInserting(Component.object(contents));
    }

    private static Key parseKey(String raw, Context ctx) {
        if (!Key.parseable(raw)) {
            throw ctx.newException("Invalid key for sprite tag: '" + raw + "'");
        }
        return Key.key(raw);
    }

    private static boolean looksLikeSpriteKey(String raw) {
        if (raw == null) {
            return false;
        }
        String key = raw.toLowerCase(java.util.Locale.ROOT);
        return key.startsWith("u") && key.length() > 10 && key.indexOf('_') == 9;
    }

    private static Player matchOnline(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    private static UUID tryParseUuid(String raw) {
        String candidate = raw;
        if (raw.length() == 32 && raw.indexOf('-') < 0) {
            candidate = raw.replaceFirst(
                    "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                    "$1-$2-$3-$4-$5");
        }
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
