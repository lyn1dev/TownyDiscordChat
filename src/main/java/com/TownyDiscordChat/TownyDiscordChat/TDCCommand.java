package com.TownyDiscordChat.TownyDiscordChat;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class TDCCommand implements CommandExecutor {
    private final Main plugin;

    public TDCCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            TDCMessages.sendKey(sender, plugin, "commands.help_check", java.util.Map.of());
            TDCMessages.sendKey(sender, plugin, "commands.help_sync", java.util.Map.of());
            TDCMessages.sendKey(sender, plugin, "commands.help_reload", java.util.Map.of());
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            boolean console = !(sender instanceof Player);
            if (!console && !sender.hasPermission("TownyDiscordChat.Admin")) {
                TDCMessages.send(sender, plugin, TDCMessages.tr(plugin, "commands.no_permission"));
                return true;
            }
            plugin.reloadConfig();
            plugin.getConfig().options().copyDefaults(true);
            plugin.saveConfig();
            plugin.locales().reload();
            plugin.manager().synchroniseAllResources();
            plugin.manager().synchroniseAllLinkedAccounts();
            TDCMessages.sendKey(sender, plugin, "commands.reload_done", java.util.Map.of());
            return true;
        }

        if (args[0].equalsIgnoreCase("sync")) {
            if (!sender.hasPermission("TownyDiscordChat.Sync")) {
                TDCMessages.send(sender, plugin, TDCMessages.tr(plugin, "commands.no_permission"));
                return true;
            }
            plugin.manager().synchroniseAllResources();
            plugin.manager().synchroniseAllLinkedAccounts();
            TDCMessages.sendKey(sender, plugin, "commands.sync_started", java.util.Map.of());
            return true;
        }

        if (args.length >= 3 && (args[0].equalsIgnoreCase("channels") || args[0].equalsIgnoreCase("channel"))
                && args[1].equalsIgnoreCase("delete")) {
            boolean console = !(sender instanceof Player);
            if (!console && !sender.hasPermission("TownyDiscordChat.Admin")) {
                TDCMessages.send(sender, plugin, TDCMessages.tr(plugin, "commands.no_permission"));
                return true;
            }
            String target = args[2];
            if (target.equalsIgnoreCase("all")) {
                int count = plugin.manager().deleteAllTownChannels();
                TDCMessages.send(sender, plugin, "&aDeleting the channels of " + count + " towns. The roles were kept.");
                return true;
            }
            if (!plugin.manager().deleteTownChannels(target)) {
                TDCMessages.send(sender, plugin, "&cTown not found or Discord unavailable: &f" + target);
                return true;
            }
            TDCMessages.send(sender, plugin, "&aDeleting the channels of &f" + target + "&a. The role was kept.");
            return true;
        }

        if (args.length >= 3 && (args[0].equalsIgnoreCase("channels") || args[0].equalsIgnoreCase("channel"))
                && args[1].equalsIgnoreCase("restore")) {
            boolean console = !(sender instanceof Player);
            if (!console && !sender.hasPermission("TownyDiscordChat.Admin")) {
                TDCMessages.send(sender, plugin, TDCMessages.tr(plugin, "commands.no_permission"));
                return true;
            }
            String target = args[2];
            if (target.equalsIgnoreCase("all")) {
                int count = plugin.manager().restoreAllTownChannels();
                TDCMessages.send(sender, plugin, "&aRestoring the channels of " + count + " towns.");
                return true;
            }
            if (!plugin.manager().restoreTownChannels(target)) {
                TDCMessages.send(sender, plugin, "&cTown not found: &f" + target);
                return true;
            }
            TDCMessages.send(sender, plugin, "&aRestoring the channels of &f" + target + "&a.");
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("check") && args[1].equalsIgnoreCase("role")) {
            if (args.length >= 3 && args[2].equalsIgnoreCase("alllinked")) {
                return runAdmin(sender, "TownyDiscordChat.Check.Role.AllLinked", plugin.manager()::synchroniseAllLinkedAccounts);
            }
            if (args.length >= 3 && args[2].equalsIgnoreCase("createalltownsandnations")) {
                return runAdmin(sender, "TownyDiscordChat.Check.Role.CreateAllTownsAndNations", plugin.manager()::synchroniseAllResources);
            }
            if (!(sender instanceof Player player)) {
                TDCMessages.send(sender, plugin, "&cThis check needs a linked player.");
                return true;
            }
            if (!sender.hasPermission("TownyDiscordChat.Check.Role")) {
                TDCMessages.send(sender, plugin, TDCMessages.tr(plugin, "commands.no_permission"));
                return true;
            }
            if (!plugin.manager().isLinked(player.getUniqueId())) {
                TDCMessages.send(player, plugin, "&cLink your Discord first with &f/discord link&c.");
                return true;
            }
            plugin.manager().synchronisePlayer(player.getUniqueId());
            TDCMessages.send(player, plugin, "&7Checking your roles.");
            return true;
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("check") && args[1].equalsIgnoreCase("textchannel")
                && args[2].equalsIgnoreCase("alltownsandnations")) {
            return runAdmin(sender, "TownyDiscordChat.Check.TextChannel.AllTownsAndNations", plugin.manager()::synchroniseAllResources);
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("check") && args[1].equalsIgnoreCase("voicechannel")
                && args[2].equalsIgnoreCase("alltownsandnations")) {
            return runAdmin(sender, "TownyDiscordChat.Check.VoiceChannel.AllTownsAndNations", plugin.manager()::synchroniseAllResources);
        }

        TDCMessages.sendKey(sender, plugin, "commands.invalid_syntax", java.util.Map.of());
        return true;
    }

    private boolean runAdmin(CommandSender sender, String permission, Runnable action) {
        if (!sender.hasPermission(permission) && !sender.hasPermission("TownyDiscordChat.Admin")) {
            TDCMessages.send(sender, plugin, TDCMessages.tr(plugin, "commands.no_permission"));
            return true;
        }
        action.run();
        TDCMessages.sendKey(sender, plugin, "commands.sync_started", java.util.Map.of());
        return true;
    }
}
