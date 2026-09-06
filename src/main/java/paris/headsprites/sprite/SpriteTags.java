package paris.headsprites.sprite;

import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.event.HoverEvent;
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
import paris.headsprites.HeadSprites;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpriteTags {
    private static final Pattern CUSTOM_TAG = Pattern.compile(
            "<(head_texture|head|seq|anim|sprite)\\s*:\\s*([^<>]+?)\\s*/?>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_HEAD = Pattern.compile(
            "<head\\s+([^<>]+?)\\s*/?>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_ASSIGN = Pattern.compile(
            "(?i)\\b(texture|id|name|uuid)\\s*[=:]\\s*[\"']?([^\"'\\s>]+)[\"']?");
    private static final Pattern TAB_NAME_HEAD = Pattern.compile(
            "(?i)<head(?!_texture)(?:[:;][^>]*)?\\s*/?>");
    private static final Pattern TEXTURE_HASH = Pattern.compile("^[a-f0-9]{32,64}$");
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

    public static Component parse(String mini) {
        if (mini == null || mini.isBlank()) {
            return Component.empty();
        }
        try {
            return sanitizeHeads(MINI.deserialize(mini));
        } catch (Exception ignored) {
            return Component.text(mini);
        }
    }

    /**
     * Rewrite sprite tags into TAB {@code head_texture} syntax. Never emits a
     * name-only {@code <head>} tag, which would crash 1.21.11 clients on join.
     */
    public static String toTabMini(String mini) {
        if (mini == null || mini.isBlank()) {
            return mini == null ? "" : mini;
        }
        try {
            return stripTabNameHeads(rewriteKnownTags(mini));
        } catch (Exception e) {
            HeadSprites.warnOnce("tab-rewrite", "Failed to rewrite sprite tags for TAB: " + mini, e);
            return stripTabNameHeads(mini);
        }
    }

    private static String stripTabNameHeads(String mini) {
        if (mini == null || mini.isBlank()) {
            return mini == null ? "" : mini;
        }
        return TAB_NAME_HEAD.matcher(mini).replaceAll("");
    }

    private static String rewriteKnownTags(String mini) {
        String colon = replaceAllTags(CUSTOM_TAG, mini, true);
        return replaceAllTags(NAMED_HEAD, colon, false);
    }

    private static String replaceAllTags(Pattern pattern, String mini, boolean colonForm) {
        Matcher matcher = pattern.matcher(mini);
        StringBuilder out = new StringBuilder(mini.length() + 32);
        while (matcher.find()) {
            String replacement = colonForm
                    ? rewriteTabTag(matcher.group(1), matcher.group(2))
                    : rewriteNamedHead(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String rewriteTabTag(String name, String inner) {
        String kind = name.toLowerCase(Locale.ROOT);
        String arg = inner == null ? "" : stripWrappingQuotes(inner.trim());
        return switch (kind) {
            case "head_texture" -> tabTexture(arg);
            case "head" -> tabHead(arg);
            case "seq" -> tabSequence(arg);
            case "anim" -> tabAnimation(arg, false);
            case "sprite" -> tabAtlasSprite(arg);
            default -> "<" + name + ":" + inner + ">";
        };
    }

    private static String rewriteNamedHead(String attrs) {
        Matcher assign = NAMED_ASSIGN.matcher(attrs == null ? "" : attrs);
        String texture = null;
        String id = null;
        String name = null;
        while (assign.find()) {
            String key = assign.group(1).toLowerCase(Locale.ROOT);
            String value = assign.group(2);
            switch (key) {
                case "texture" -> texture = value;
                case "id", "uuid" -> id = value;
                case "name" -> name = value;
                default -> {
                }
            }
        }
        if (texture != null) {
            return tabTexture(texture);
        }
        if (id != null) {
            return tabHead(id);
        }
        if (name != null) {
            return tabHead(name);
        }
        return tabHead(attrs == null ? "" : attrs.trim());
    }

    private static String stripWrappingQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static String tabTexture(String arg) {
        String hash = SpriteManager.normalizeLookup(arg);
        if (TEXTURE_HASH.matcher(hash).matches()) {
            return headTextureMini(hash);
        }
        return tabHead(hash);
    }

    private static String tabHead(String arg) {
        String cleaned = SpriteManager.normalizeLookup(stripWrappingQuotes(arg));
        int compact = cleaned.indexOf(';');
        if (compact >= 0) {
            cleaned = cleaned.substring(0, compact).trim();
        }
        if (cleaned.endsWith(":true") || cleaned.endsWith(":false")) {
            cleaned = cleaned.substring(0, cleaned.lastIndexOf(':')).trim();
        }
        if (cleaned.equals("texture")) {
            return "";
        }
        if (cleaned.startsWith("texture:")) {
            String hash = SpriteManager.normalizeLookup(stripWrappingQuotes(cleaned.substring("texture:".length())));
            if (TEXTURE_HASH.matcher(hash).matches()) {
                return headTextureMini(hash);
            }
            cleaned = hash;
        }
        if (TEXTURE_HASH.matcher(cleaned).matches()) {
            return headTextureMini(cleaned);
        }
        SpriteManager manager = SpriteManager.getInstance();
        if (manager != null) {
            SpriteManager.Sprite stored = manager.getSprite(cleaned);
            if (stored != null) {
                return manager.tabHeadTag(stored);
            }
            String anim = tabAnimation(cleaned, true);
            if (!anim.isBlank()) {
                return anim;
            }
        }
        if (looksLikeSpriteKey(cleaned)) {
            return "";
        }
        UUID uuid = tryParseUuid(cleaned);
        Player online = uuid != null ? Bukkit.getPlayer(uuid) : matchOnline(cleaned);
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
        return hash == null ? "" : headTextureMini(hash);
    }

    public static String tabTextureMini(String hash) {
        return headTextureMini(hash);
    }

    private static String headTextureMini(String hash) {
        if (hash == null || hash.isBlank()) {
            return "";
        }
        return "<head_texture:" + hash + ">";
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
        return TagResolver.resolver(head(), headTexture(), sprite(), seq(), anim(chatSnapshot));
    }

    private static TagResolver head() {
        return TagResolver.resolver("head", SpriteTags::resolveHead);
    }

    private static TagResolver headTexture() {
        return TagResolver.resolver("head_texture", (args, ctx) -> {
            String hash = args.popOr("head_texture tag requires a texture hash").value();
            return Tag.selfClosingInserting(headFromTextureHash(hash));
        });
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
        String first = args.popOr("head tag requires a sprite name, username, or UUID").value();
        String arg = first;
        if ("texture".equalsIgnoreCase(first) && args.hasNext()) {
            arg = "texture:" + args.pop().value();
        } else if (("name".equalsIgnoreCase(first) || "id".equalsIgnoreCase(first) || "uuid".equalsIgnoreCase(first))
                && args.hasNext()) {
            arg = args.pop().value();
        }
        return Tag.selfClosingInserting(resolveHeadArgument(arg));
    }

    public static Component resolveHeadArgument(String arg) {
        if (arg == null || arg.isBlank()) {
            return UNKNOWN_HEAD;
        }
        String cleaned = SpriteManager.normalizeLookup(stripWrappingQuotes(arg.trim()));
        if (cleaned.startsWith("texture:")) {
            cleaned = SpriteManager.normalizeLookup(stripWrappingQuotes(cleaned.substring("texture:".length())));
        }
        if (TEXTURE_HASH.matcher(cleaned).matches()) {
            return headFromTextureHash(cleaned);
        }
        SpriteManager manager = SpriteManager.getInstance();
        if (manager != null) {
            SpriteManager.Sprite stored = manager.getSprite(cleaned);
            if (stored != null) {
                return stored.head();
            }
            Component firstFrame = manager.buildAnimationFirstFrame(cleaned);
            if (firstFrame != null) {
                return sanitizeHeads(firstFrame);
            }
        }
        if (looksLikeSpriteKey(cleaned) || cleaned.equals("texture")) {
            return UNKNOWN_HEAD;
        }
        UUID uuid = tryParseUuid(cleaned);
        if (uuid != null) {
            return staticHead(uuid);
        }
        Player online = matchOnline(cleaned);
        if (online != null) {
            return staticHead(online);
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(cleaned);
        if (cached != null) {
            return staticHead(cached.getUniqueId());
        }
        return UNKNOWN_HEAD;
    }

    private static Component headFromTextureHash(String hash) {
        String cleaned = SpriteManager.normalizeLookup(stripWrappingQuotes(hash == null ? "" : hash));
        if (!TEXTURE_HASH.matcher(cleaned).matches()) {
            return UNKNOWN_HEAD;
        }
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"
                + cleaned + "\"}}}";
        String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return Component.object(ObjectContents.playerHead()
                .hat(false)
                .profileProperty(PlayerHeadObjectContents.property("textures", value))
                .build());
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
     * Rewrite any name-only player heads (including inside hover text) so the
     * 1.21.11 client never iterates the tab list.
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
        HoverEvent<?> hover = node.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT
                && hover.value() instanceof Component hoverText) {
            node = node.hoverEvent(HoverEvent.showText(sanitizeHeads(hoverText)));
        }
        List<Component> children = node.children();
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
            Component resolved = resolveHeadArgument(name);
            if (resolved instanceof ObjectComponent object
                    && object.contents() instanceof PlayerHeadObjectContents resolvedHead
                    && resolvedHead.name() != null
                    && texturesProperty(resolvedHead) == null
                    && resolvedHead.id() == null) {
                return UNKNOWN_HEAD;
            }
            return resolved;
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
        String key = raw.toLowerCase(Locale.ROOT);
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
        if (raw == null) {
            return null;
        }
        if (raw.length() == 32 && raw.indexOf('-') < 0) {
            return SpriteManager.parseUndashedUuid(raw);
        }
        if (raw.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
