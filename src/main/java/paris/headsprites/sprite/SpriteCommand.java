package paris.headsprites.sprite;

import paris.headsprites.HeadSprites;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static paris.headsprites.util.Text.colorize;

public final class SpriteCommand implements CommandExecutor, TabCompleter {

    private static final Map<UUID, BukkitTask> DISPLAY_TASKS = new ConcurrentHashMap<>();

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
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
        String name = args[2].toLowerCase(Locale.ROOT);
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

        Location where = player.getEyeLocation();
        TextDisplay display = player.getWorld().spawn(where, TextDisplay.class, entity -> {
            entity.setBillboard(TextDisplay.Billboard.CENTER);
            entity.text(firstFrame);
        });
        UUID displayId = display.getUniqueId();

        long periodTicks = Math.max(1L, animation.intervalMs() / 50L);
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!display.isValid()) {
                    stopDisplay(displayId);
                    return;
                }
                Component frame = manager.buildAnimation(name);
                if (frame != null) {
                    display.text(frame);
                }
            }
        }.runTaskTimer(HeadSprites.getInstance(), periodTicks, periodTicks);
        DISPLAY_TASKS.put(displayId, task);

        sender.sendMessage(colorize("&aSpawned an animated text display for '&f" + name
                + "&a'. Use &f/sprite anim stop&a to remove all displays."));
    }

    private void handleAnimStop(CommandSender sender) {
        if (DISPLAY_TASKS.isEmpty()) {
            sender.sendMessage(colorize("&7No animated text displays are running."));
            return;
        }
        int removed = 0;
        for (UUID id : new ArrayList<>(DISPLAY_TASKS.keySet())) {
            stopDisplay(id);
            org.bukkit.entity.Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
            removed++;
        }
        sender.sendMessage(colorize("&aRemoved &f" + removed + "&a animated text display(s)."));
    }

    private static void stopDisplay(UUID displayId) {
        BukkitTask task = DISPLAY_TASKS.remove(displayId);
        if (task != null) {
            task.cancel();
        }
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

        String name = args[1];
        String imageUrl = args[2];
        String fallback = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : "";

        sender.sendMessage(colorize("&7Queuing '&f" + name + "&7' with MineSkin, please wait..."));
        Bukkit.getScheduler().runTaskAsynchronously(HeadSprites.getInstance(), () -> {
            try {
                MineSkinClient client = new MineSkinClient(apiKey);
                MineSkinClient.Texture texture = client.generate(imageUrl, name);
                Bukkit.getScheduler().runTask(HeadSprites.getInstance(), () -> {
                    manager.putSprite(name, texture.value(), texture.signature(), fallback);
                    sender.sendMessage(colorize("&aSaved sprite '&f" + name + "&a'. Use it with &f<head:" + name.toLowerCase(Locale.ROOT) + ">"));
                });
            } catch (MineSkinClient.MineSkinException e) {
                Bukkit.getScheduler().runTask(HeadSprites.getInstance(), () ->
                        sender.sendMessage(colorize("&cGeneration failed: " + e.getMessage())));
            }
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
        if (manager == null) {
            sender.sendMessage(colorize("&cSprite system is not initialized."));
            return;
        }
        manager.reload();
        sender.sendMessage(colorize("&aReloaded sprites.yml (" + manager.getSprites().size() + " sprites)."));
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
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String sub : List.of("generate", "list", "anim", "reload")) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(sub);
                }
            }
            return options;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("anim")) {
            List<String> options = new ArrayList<>();
            for (String sub : List.of("list", "display", "stop")) {
                if (sub.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    options.add(sub);
                }
            }
            return options;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("anim") && args[1].equalsIgnoreCase("display")) {
            SpriteManager manager = SpriteManager.getInstance();
            List<String> options = new ArrayList<>();
            if (manager != null) {
                for (String name : manager.getAnimations().keySet()) {
                    if (name.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                        options.add(name);
                    }
                }
            }
            return options;
        }
        return List.of();
    }
}
