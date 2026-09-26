package com.TownyDiscordChat.TownyDiscordChat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * For chat plugins that run their own town and nation channels instead of TownyChat (Meridian's MeridianChat): hand
 * a message over and it goes to the player's town or nation channel on Discord. Call on the server thread.
 */
public final class TDCApi {

    private TDCApi() {
    }

    private static Main plugin() {
        Plugin p = Bukkit.getPluginManager().getPlugin("TownyDiscordChat");
        return p instanceof Main main && main.isEnabled() && main.manager() != null ? main : null;
    }

    /** A message the player sent to their town channel in game. */
    public static void townChat(Player player, String message) {
        Main p = plugin();
        if (p == null || player == null || message == null || message.isBlank()) return;
        if (!p.manager().relayMinecraftMessageThroughDiscordSRV(player, message)) {
            p.manager().relayMinecraftMessage(player.getUniqueId(), player.getName(), message);
        }
    }

    /**
     * An item the player showed (e.g. /show, /head) to their town: componentJson is the chat line as Adventure JSON
     * with the item hover, which the InteractiveChat DiscordSRV addon renders as an image; plainText is used without it.
     */
    public static void townShow(Player player, String componentJson, String plainText) {
        Main p = plugin();
        if (p == null || player == null) return;
        if (!p.manager().relayMinecraftMessageThroughDiscordSRV(player, component(componentJson))) {
            p.manager().relayMinecraftMessage(player.getUniqueId(), player.getName(), plainText);
        }
    }

    /** Like {@link #townShow}, for the player's nation channel. */
    public static void nationShow(Player player, String componentJson, String plainText) {
        Main p = plugin();
        if (p == null || player == null) return;
        if (!p.manager().relayNationMinecraftMessageThroughDiscordSRV(player, component(componentJson))) {
            p.manager().relayNationMinecraftMessage(player, player.getName(), plainText);
        }
    }

    private static github.scarsz.discordsrv.dependencies.kyori.adventure.text.Component component(String json) {
        return github.scarsz.discordsrv.dependencies.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(json);
    }

    /** A message the player sent to their nation channel in game. */
    public static void nationChat(Player player, String message) {
        Main p = plugin();
        if (p == null || player == null || message == null || message.isBlank()) return;
        if (!p.manager().relayNationMinecraftMessageThroughDiscordSRV(player, message)) {
            p.manager().relayNationMinecraftMessage(player, player.getName(), message);
        }
    }
}
