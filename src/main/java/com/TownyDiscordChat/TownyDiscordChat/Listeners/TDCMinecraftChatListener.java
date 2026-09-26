package com.TownyDiscordChat.TownyDiscordChat.Listeners;

import com.TownyDiscordChat.TownyDiscordChat.Main;
import com.TownyDiscordChat.TownyDiscordChat.TDCManager.ItemPreview;
import com.TownyDiscordChat.TownyDiscordChat.TDCMessages;
import com.TownyDiscordChat.TownyDiscordChat.TDCPlaceholders;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.Location;
import org.bukkit.Sound;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Paper 1.21 chat listener: relays a town resident's message to their town Discord channel. */
public final class TDCMinecraftChatListener implements Listener {
    private static final Pattern INTERACTIVE_CHAT_TOKEN = Pattern.compile("<chat=[^:<>]+:(.*?):>");
    private final Main plugin;
    private final Map<UUID, String> channelHints = new ConcurrentHashMap<>();

    public TDCMinecraftChatListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (text.isBlank()) return;
        java.util.UUID playerId = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();
        List<ItemPreview> items = interactiveChatItems(event.message());
        // Towny is not thread-safe: only read its model back on the server thread.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) return;
            String channel = currentTownyChatChannel(player);
            if (isConfiguredChannel(channel, "townychat.TownChannelNames")) {
                Set<UUID> recipients = new HashSet<>();
                for (Audience viewer : event.viewers()) {
                    if (viewer instanceof Player recipient) recipients.add(recipient.getUniqueId());
                }
                // A stale channel hint must never route a global message into
                // the town channel. TownyChat's actual viewer set is authoritative.
                if (!recipients.isEmpty() && !plugin.manager().isTownChatAudience(playerId, recipients)) return;
                String discordText = restoreInteractiveChatPlaceholders(text);
                boolean delegated = plugin.manager().relayMinecraftMessageThroughDiscordSRV(player, discordText);
                if (!delegated) {
                    plugin.manager().relayMinecraftMessage(playerId, playerName, discordText);
                    plugin.manager().relayInteractiveChatItems(playerId, playerName, items);
                }
            } else if (isConfiguredChannel(channel, "townychat.NationChannelNames")) {
                String discordText = restoreInteractiveChatPlaceholders(text);
                if (!plugin.manager().relayNationMinecraftMessageThroughDiscordSRV(player, discordText)) {
                    plugin.manager().relayNationMinecraftMessage(player, playerName, discordText);
                }
            } else if (isConfiguredChannel(channel, "townychat.LocalNoNearbyPlayersWarning.ChannelNames")) {
                warnLocalChatIfAlone(player);
            }
        });
    }

    /** Reads TownyChat's PlaceholderAPI channel value, with command tracking as a no-hook fallback. */
    private String currentTownyChatChannel(Player player) {
        if (!plugin.configuration().getBoolean("townychat.Enabled", true)) return "";
        String placeholder = plugin.configuration().getString("townychat.ChannelPlaceholder", "%townychat_channel_name%");
        String resolved = TDCPlaceholders.resolve(plugin, player, placeholder);
        if (resolved != null && !resolved.isBlank() && !resolved.equals(placeholder) && !resolved.contains("%")) {
            String channel = TDCMessages.strip(resolved).trim();
            if (!channel.isBlank()) {
                channelHints.put(player.getUniqueId(), channel);
                return channel;
            }
        }
        return channelHints.getOrDefault(player.getUniqueId(), "");
    }

    /** Used by the DiscordSRV listener to suppress its second, global copy of town chat. */
    public boolean isCurrentTownChannel(Player player) {
        return player != null && isConfiguredChannel(currentTownyChatChannel(player), "townychat.TownChannelNames");
    }

    /** InteractiveChat stores placeholders in chat components as <chat=uuid:[item]:>. */
    private String restoreInteractiveChatPlaceholders(String text) {
        Matcher matcher = INTERACTIVE_CHAT_TOKEN.matcher(text);
        StringBuffer restored = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(restored, Matcher.quoteReplacement(matcher.group(1)));
        }
        matcher.appendTail(restored);
        return restored.toString();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onTownyChatCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.configuration().getBoolean("townychat.Enabled", true)
                || !plugin.configuration().getBoolean("townychat.CommandListener.Enabled", true)) return;
        String raw = event.getMessage().trim();
        if (raw.length() < 2) return;
        String command = raw.substring(1).split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        int namespace = command.indexOf(':');
        if (namespace >= 0) command = command.substring(namespace + 1);
        UUID playerId = event.getPlayer().getUniqueId();
        String[] parts = raw.substring(1).split("\\s+");
        if (matchesCommand(command, "townychat.CommandListener.TownCommands")
                || channelArgumentMatches(parts, "townychat.TownChannelNames")) {
            channelHints.put(playerId, configuredPrimaryName("townychat.TownChannelNames", "town"));
        } else if (matchesCommand(command, "townychat.CommandListener.NationCommands")
                || channelArgumentMatches(parts, "townychat.NationChannelNames")) {
            channelHints.put(playerId, configuredPrimaryName("townychat.NationChannelNames", "nation"));
        } else if (matchesCommand(command, "townychat.CommandListener.LocalCommands")
                || channelArgumentMatches(parts, "townychat.LocalNoNearbyPlayersWarning.ChannelNames")) {
            channelHints.put(playerId, configuredPrimaryName("townychat.LocalNoNearbyPlayersWarning.ChannelNames", "local"));
        } else if (matchesCommand(command, "townychat.CommandListener.OtherCommands")
                || channelArgumentMatches(parts, "townychat.CommandListener.OtherCommands")) {
            channelHints.remove(playerId);
        }
    }

    /** Supports channel-switch commands such as /ch town, /chat global and /channel local. */
    private boolean channelArgumentMatches(String[] parts, String configPath) {
        if (parts.length < 2) return false;
        String command = parts[0].toLowerCase(java.util.Locale.ROOT);
        if (!(command.equals("ch") || command.equals("channel") || command.equals("chat") || command.equals("townychat"))) return false;
        String argument = parts[1].toLowerCase(java.util.Locale.ROOT);
        return plugin.configuration().getStringList(configPath).stream().anyMatch(value -> value.equalsIgnoreCase(argument));
    }

    private boolean matchesCommand(String command, String configPath) {
        return plugin.configuration().getStringList(configPath).stream().anyMatch(value -> value.equalsIgnoreCase(command));
    }

    private boolean isConfiguredChannel(String channel, String configPath) {
        return !channel.isBlank() && plugin.configuration().getStringList(configPath).stream()
                .anyMatch(value -> value.equalsIgnoreCase(channel));
    }

    private String configuredPrimaryName(String configPath, String fallback) {
        List<String> names = plugin.configuration().getStringList(configPath);
        return names.isEmpty() ? fallback : names.getFirst();
    }

    private void warnLocalChatIfAlone(Player player) {
        if (!plugin.configuration().getBoolean("townychat.LocalNoNearbyPlayersWarning.Enabled", true)) return;
        double radius = Math.max(1D, plugin.configuration().getDouble("townychat.LocalNoNearbyPlayersWarning.Radius", 100D));
        Location origin = player.getLocation();
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other.equals(player) || !other.getWorld().equals(origin.getWorld())) continue;
            if (other.getLocation().distanceSquared(origin) < radius * radius) return;
        }
        String message = plugin.configuration().getString("townychat.LocalNoNearbyPlayersWarning.Message", "");
        player.sendMessage(TDCMessages.colour(TDCPlaceholders.resolve(plugin, player, message)));
        String title = plugin.configuration().getString("townychat.LocalNoNearbyPlayersWarning.Title", "");
        String subtitle = plugin.configuration().getString("townychat.LocalNoNearbyPlayersWarning.Subtitle", "");
        String resolvedTitle = TDCMessages.colour(TDCPlaceholders.resolve(plugin, player, title));
        String resolvedSubtitle = TDCMessages.colour(TDCPlaceholders.resolve(plugin, player, subtitle));
        int fadeIn = Math.max(0, plugin.configuration().getInt("townychat.LocalNoNearbyPlayersWarning.TitleFadeIn", 10));
        int stay = Math.max(0, plugin.configuration().getInt("townychat.LocalNoNearbyPlayersWarning.TitleStay", 50));
        int fadeOut = Math.max(0, plugin.configuration().getInt("townychat.LocalNoNearbyPlayersWarning.TitleFadeOut", 20));
        if (!resolvedTitle.isBlank() || !resolvedSubtitle.isBlank()) {
            player.sendTitle(resolvedTitle, resolvedSubtitle, fadeIn, stay, fadeOut);
        }
        if (plugin.configuration().getBoolean("townychat.LocalNoNearbyPlayersWarning.SoundEnabled", true)) {
            String soundName = plugin.configuration().getString("townychat.LocalNoNearbyPlayersWarning.Sound", "BLOCK_NOTE_BLOCK_BELL");
            try {
                Sound sound = Sound.valueOf(soundName.toUpperCase(java.util.Locale.ROOT));
                float volume = (float) Math.max(0D, plugin.configuration().getDouble("townychat.LocalNoNearbyPlayersWarning.SoundVolume", 1D));
                float pitch = (float) Math.max(0D, plugin.configuration().getDouble("townychat.LocalNoNearbyPlayersWarning.SoundPitch", 1D));
                player.playSound(player.getLocation(), sound, volume, pitch);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Invalid sound for the local chat warning: " + soundName);
            }
        }
    }

    private List<ItemPreview> interactiveChatItems(Component message) {
        if (!plugin.configuration().getBoolean("interactivechat.Enabled", true)
                || !plugin.getServer().getPluginManager().isPluginEnabled("InteractiveChat")) {
            return List.of();
        }
        int maxItems = Math.max(1, Math.min(10, plugin.configuration().getInt("interactivechat.MaxItemsPerMessage", 3)));
        List<ItemPreview> items = new ArrayList<>();
        collectItemHovers(message, items, new HashSet<>(), maxItems);
        return items;
    }

    private void collectItemHovers(Component component, List<ItemPreview> items, Set<String> seen, int maximum) {
        if (items.size() >= maximum) return;
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_ITEM && hover.value() instanceof HoverEvent.ShowItem showItem) {
            String key = showItem.item().asString();
            String displayName = PlainTextComponentSerializer.plainText().serialize(component).trim();
            if (displayName.isBlank()) displayName = prettifyItemName(key);
            String identity = key + ":" + showItem.count() + ":" + displayName;
            if (seen.add(identity)) items.add(new ItemPreview(key, Math.max(1, showItem.count()), displayName));
        }
        for (Component child : component.children()) {
            collectItemHovers(child, items, seen, maximum);
            if (items.size() >= maximum) return;
        }
    }

    private String prettifyItemName(String key) {
        String value = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        String[] words = value.replace('_', ' ').split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
