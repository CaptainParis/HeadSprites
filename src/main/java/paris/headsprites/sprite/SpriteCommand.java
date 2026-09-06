package paris.headsprites.sprite;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import paris.headsprites.HeadSprites;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static paris.headsprites.util.Text.colorize;

public final class SpriteCommand implements CommandExecutor, TabCompleter {
    private static final int MAX_DISPLAYS = 32;

    private static final Map<UUID, AnimatedDisplay> DISPLAYS = new ConcurrentHashMap<>();
    private static BukkitTask ticker;

    private static final class AnimatedDisplay {
        private final TextDisplay display;
        private final String animation;
        private int lastFrame = -1;

        private AnimatedDisplay(TextDisplay display, String animation) {
            this.display = display;
            this.animation = animation;
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        try {
            if (args.length == 0) {
                sendUsage(sender);
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "generate" -> handleGenerate(sender, args);
                case "list" -> handleList(sender);
                case "anim" -> handleAnim(sender, args);
                case "reload" -> handleReload(sender);
                default -> sendUsage(sender);
            }
        } catch (Exception e) {
            String sub = args.length == 0 ? "usage" : args[0].toLowerCase(Locale.ROOT);
            if (!List.of("generate", "list", "anim", "reload", "usage").contains(sub)) {
                sub = "other";
            }
            HeadSprites.warnOnce("command-" + sub,
                    "/sprite " + String.join(" ", args) + " failed for " + sender.getName(), e);
            sender.sendMessage(colorize("&cThat command failed; see the console for details."));
        }
        return true;
    }

    private void handleAnim(CommandSender sender, String[] args) {
        SpriteManager manager = SpriteManager.getInstance();
        if (manager == null) {
            sender.sendMessage(colorize("&cSprite system is not initialized."));
            return;
        }
        String sub = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> handleAnimList(sender, manager);
            case "display" -> handleAnimDisplay(sender, manager, args);
            case "stop" -> handleAnimStop(sender);
            default -> sender.sendMessage(colorize("&cUsage: /sprite anim <list|display|stop>"));
        }
    }

    private void handleAnimList(CommandSender sender, SpriteManager manager) {
        if (manager.getAnimations().isEmpty()) {
            sender.sendMessage(colorize("&7No animations are stored yet."));
            return;
        }
        sender.sendMessage(colorize("&6Stored animations (&f" + manager.getAnimations().size() + "&6):"));
        manager.getAnimations().forEach((name, animation) ->
                sender.sendMessage(colorize("&7- &f" + name + " &8(" + animation.frames().size()
                        + " frames, " + animation.intervalMs() + "ms) &8<anim:" + name + ">")));
    }

    private void handleAnimDisplay(CommandSender sender, SpriteManager manager, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(colorize("&cOnly a player can spawn a text display."));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(colorize("&cUsage: /sprite anim display <name>"));
            return;
        }
        String name = SpriteManager.normalizeLookup(args[2]);
        SpriteManager.Animation animation = manager.getAnimation(name);
        if (animation == null) {
            sender.sendMessage(colorize("&cUnknown animation: '" + name + "'. Use /sprite anim list."));
            return;
        }
        Component firstFrame = manager.buildAnimationFrame(animation, 0);
        if (firstFrame == null) {
            sender.sendMessage(colorize("&cAnimation '" + name + "' has no renderable frames."));
            return;
        }
        if (DISPLAYS.size() >= MAX_DISPLAYS) {
            sender.sendMessage(colorize("&cToo many animated displays (" + MAX_DISPLAYS
                    + "). Use &f/sprite anim stop&c first."));
            return;
        }

        Location where = player.getEyeLocation();
        TextDisplay display = player.getWorld().spawn(where, TextDisplay.class, entity -> {
            entity.setBillboard(TextDisplay.Billboard.CENTER);
            entity.setPersistent(false);
            entity.text(SpriteTags.sanitizeHeads(firstFrame));
        });
        AnimatedDisplay entry = new AnimatedDisplay(display, animation.name());
        entry.lastFrame = 0;
        DISPLAYS.put(display.getUniqueId(), entry);
        ensureTicker();

        sender.sendMessage(colorize("&aSpawned an animated text display for '&f" + name
                + "&a'. Use &f/sprite anim stop&a to remove all displays."));
    }

    private void handleAnimStop(CommandSender sender) {
        int removed = stopAllDisplays();
        if (removed == 0) {
            sender.sendMessage(colorize("&7No animated text displays are running."));
            return;
        }
        sender.sendMessage(colorize("&aRemoved &f" + removed + "&a animated text display(s)."));
    }

    private static void ensureTicker() {
        if (ticker != null && !ticker.isCancelled()) {
            return;
        }
        Plugin plugin = HeadSprites.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, SpriteCommand::tickDisplays, 1L, 1L);
    }

    private static void tickDisplays() {
        SpriteManager manager = SpriteManager.getInstance();
        Iterator<Map.Entry<UUID, AnimatedDisplay>> it = DISPLAYS.entrySet().iterator();
        while (it.hasNext()) {
            AnimatedDisplay entry = it.next().getValue();
            try {
                if (!entry.display.isValid() || manager == null) {
                    it.remove();
                    continue;
                }
                SpriteManager.Animation animation = manager.getAnimation(entry.animation);
                if (animation == null) {
                    it.remove();
                    entry.display.remove();
                    continue;
                }
                int index = manager.currentFrameIndex(animation);
                if (index == entry.lastFrame) {
                    continue;
                }
                Component frame = manager.buildAnimationFrame(animation, index);
                if (frame != null) {
                    entry.display.text(SpriteTags.sanitizeHeads(frame));
                }
                entry.lastFrame = index;
            } catch (Exception e) {
                it.remove();
                HeadSprites.warnOnce("anim-display-tick",
                        "Animated display for '" + entry.animation + "' failed and was dropped", e);
            }
        }
        if (DISPLAYS.isEmpty() && ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    public static int stopAllDisplays() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        int removed = 0;
        for (UUID id : new ArrayList<>(DISPLAYS.keySet())) {
            AnimatedDisplay entry = DISPLAYS.remove(id);
            try {
                Entity entity = entry != null && entry.display.isValid() ? entry.display : Bukkit.getEntity(id);
                if (entity != null) {
                    entity.remove();
                }
            } catch (Exception e) {
                HeadSprites.warnOnce("anim-display-remove", "Failed to remove animated display " + id, e);
            }
            removed++;
        }
        return removed;
    }

    private void handleGenerate(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(colorize("&cUsage: /sprite generate <name> <imageUrl> [fallback]"));
            return;
        }

        SpriteManager manager = SpriteManager.getInstance();
        if (manager == null) {
            sender.sendMessage(colorize("&cSprite system is not initialized."));
            return;
        }

        String apiKey = manager.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            sender.sendMessage(colorize("&cNo MineSkin API key set. Add 'mineskin-api-key' to sprites.yml and /sprite reload."));
            return;
        }

        String name = SpriteManager.normalizeKey(args[1]);
        if (name.isBlank()) {
            sender.sendMessage(colorize("&cInvalid sprite name. Use letters, digits, '_' or '-'."));
            return;
        }
        String imageUrl = args[2];
        String fallback = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "";
        Plugin plugin = HeadSprites.getInstance();
        if (plugin == null) {
            sender.sendMessage(colorize("&cSprite system is not initialized."));
            return;
        }

        sender.sendMessage(colorize("&7Queuing '&f" + name + "&7' with MineSkin, please wait..."));
        new MineSkinClient(apiKey).generateAsync(imageUrl, name).whenComplete((texture, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    if (!(cause instanceof MineSkinClient.MineSkinException)) {
                        HeadSprites.warnOnce("generate-unexpected", "Unexpected error generating '" + name + "'", cause);
                    }
                    sender.sendMessage(colorize("&cGeneration failed: " + cause.getMessage()));
                    return;
                }
                if (!manager.putSprite(name, texture.value(), texture.signature(), fallback)) {
                    sender.sendMessage(colorize("&cGeneration failed: MineSkin returned an unusable texture."));
                    return;
                }
                sender.sendMessage(colorize("&aSaved sprite '&f" + name + "&a'. Use it with &f<head:" + name + ">"));
            });
        });
    }

    private void handleList(CommandSender sender) {
        SpriteManager manager = SpriteManager.getInstance();
        if (manager == null || manager.getSprites().isEmpty()) {
            sender.sendMessage(colorize("&7No sprites are stored yet."));
            return;
        }
        sender.sendMessage(colorize("&6Stored sprites (&f" + manager.getSprites().size() + "&6):"));
        for (SpriteManager.Sprite sprite : manager.getSprites()) {
            sender.sendMessage(colorize("&7- &f" + sprite.name() + " &8(<head:" + sprite.name() + ">)"));
        }
    }

    private void handleReload(CommandSender sender) {
        SpriteManager manager = SpriteManager.getInstance();
        Plugin plugin = HeadSprites.getInstance();
        if (manager == null || plugin == null) {
            sender.sendMessage(colorize("&cSprite system is not initialized."));
            return;
        }
        sender.sendMessage(colorize("&7Reloading sprites.yml..."));
        manager.reloadAsync().whenComplete((ignored, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    HeadSprites.warnOnce("reload", "sprites.yml reload failed", error);
                    sender.sendMessage(colorize("&cReload failed: " + error.getMessage()));
                    return;
                }
                sender.sendMessage(colorize("&aReloaded sprites.yml (" + manager.getSprites().size() + " sprites)."));
            });
        });
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(colorize("&6/sprite &7subcommands:"));
        sender.sendMessage(colorize("&7- &f/sprite generate <name> <imageUrl> [fallback]"));
        sender.sendMessage(colorize("&7- &f/sprite list"));
        sender.sendMessage(colorize("&7- &f/sprite anim list"));
        sender.sendMessage(colorize("&7- &f/sprite anim display <name>"));
        sender.sendMessage(colorize("&7- &f/sprite anim stop"));
        sender.sendMessage(colorize("&7- &f/sprite reload"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        try {
            if (args.length == 1) {
                return prefixed(List.of("generate", "list", "anim", "reload"), args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("anim")) {
                return prefixed(List.of("list", "display", "stop"), args[1]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("anim") && args[1].equalsIgnoreCase("display")) {
                SpriteManager manager = SpriteManager.getInstance();
                return manager == null ? List.of() : prefixed(manager.getAnimations().keySet(), args[2]);
            }
        } catch (Exception e) {
            HeadSprites.warnOnce("tab-complete", "/sprite tab completion failed", e);
        }
        return List.of();
    }

    private static List<String> prefixed(Iterable<String> options, String arg) {
        String prefix = arg.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
