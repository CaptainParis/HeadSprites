package paris.headsprites.util;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Text {
    private static final Pattern HEX_PATTERN = Pattern.compile("#([A-Fa-f0-9]{6})");

    private Text() {
    }

    public static String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', translateHexColorCodes(message));
    }

    private static String translateHexColorCodes(String message) {
        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuffer buffer = new StringBuffer(message.length() + 32);
        while (matcher.find()) {
            String group = matcher.group(1);
            matcher.appendReplacement(buffer, "\u00a7x"
                    + "\u00a7" + group.charAt(0)
                    + "\u00a7" + group.charAt(1)
                    + "\u00a7" + group.charAt(2)
                    + "\u00a7" + group.charAt(3)
                    + "\u00a7" + group.charAt(4)
                    + "\u00a7" + group.charAt(5));
        }
        return matcher.appendTail(buffer).toString();
    }
}
