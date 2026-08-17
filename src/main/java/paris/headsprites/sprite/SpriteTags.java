package paris.headsprites.sprite;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.UUID;

public final class SpriteTags {
    private SpriteTags() {
    }

    public static TagResolver resolver() {
        return TagResolver.resolver(head(), sprite(), seq(), anim());
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

    private static TagResolver anim() {
        return TagResolver.resolver("anim", SpriteTags::resolveAnim);
    }

    private static Tag resolveAnim(ArgumentQueue args, Context ctx) {
        String name = args.popOr("anim tag requires an animation name").value();

        SpriteManager manager = SpriteManager.getInstance();
        if (manager == null) {
            throw ctx.newException("Sprite system is not initialized.");
        }
        Component frame = manager.buildAnimation(name);
        if (frame == null) {
            throw ctx.newException("Unknown or empty animation: '" + name + "'");
        }
        return Tag.selfClosingInserting(frame);
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
        return Tag.selfClosingInserting(chained);
    }

    private static Tag resolveHead(ArgumentQueue args, Context ctx) {
        String arg = args.popOr("head tag requires a sprite name, username, or UUID").value();

        SpriteManager manager = SpriteManager.getInstance();
        if (manager != null) {
            SpriteManager.Sprite stored = manager.getSprite(arg);
            if (stored != null) {
                return Tag.selfClosingInserting(manager.buildHead(stored));
            }
            Component firstFrame = manager.buildAnimationFirstFrame(arg);
            if (firstFrame != null) {
                return Tag.selfClosingInserting(firstFrame);
            }
        }

        UUID uuid = tryParseUuid(arg);
        Component head = uuid != null
                ? Component.object(ObjectContents.playerHead(uuid))
                : Component.object(ObjectContents.playerHead(arg));
        return Tag.selfClosingInserting(head);
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
