package paris.headsprites.sprite;

import paris.headsprites.HeadSprites;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static paris.headsprites.util.Text.colorize;

public final class SpriteCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "generate" -> handleGenerate(sender, args);
            case "list" -> handleList(sender);
            case "reload" -> handleReload(sender);
            default -> sendUsage(sender);
        }
        return true;
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
        sender.sendMessage(colorize("&7- &f/sprite reload"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String sub : List.of("generate", "list", "reload")) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(sub);
                }
            }
            return options;
        }
        return List.of();
    }
}
