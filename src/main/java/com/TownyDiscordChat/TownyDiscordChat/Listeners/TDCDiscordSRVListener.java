package com.TownyDiscordChat.TownyDiscordChat.Listeners;

import com.TownyDiscordChat.TownyDiscordChat.Main;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.ListenerPriority;
import github.scarsz.discordsrv.api.events.AccountLinkedEvent;
import github.scarsz.discordsrv.api.events.AccountUnlinkedEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePreProcessEvent;
import github.scarsz.discordsrv.DiscordSRV;

/** Reconciles an account as soon as DiscordSRV completes its link flow. */
public final class TDCDiscordSRVListener {
    private static final String TDC_CHANNEL_PREFIX = "tdc-town-";
    private static final String TDC_NATION_PREFIX = "tdc-nation-";
    private final Main plugin;
    private final TDCMinecraftChatListener minecraftChatListener;

    public TDCDiscordSRVListener(Main plugin, TDCMinecraftChatListener minecraftChatListener) {
        this.plugin = plugin;
        this.minecraftChatListener = minecraftChatListener;
    }

    @Subscribe
    public void onAccountLinked(AccountLinkedEvent event) {
        if (!event.getPlayer().hasPlayedBefore() || event.getUser().isBot()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin,
                () -> plugin.manager().synchronisePlayer(event.getUser().getId(), event.getPlayer().getUniqueId()));
    }

    /**
     * DiscordSRV's generic Paper listener treats town chat as global. Cancel
     * only the duplicate town copy; global and nation chat remain available
     * through DiscordSRV's normal global channel.
     */
    @Subscribe
    public void onAccountUnlinked(AccountUnlinkedEvent event) {
        String discordId = event.getDiscordId();
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.manager().removeAllAccess(discordId));
    }

    @Subscribe(priority = ListenerPriority.LOWEST)
    public void onGameChatMessage(GameChatMessagePreProcessEvent event) {
        if (event.isCancelled() || (event.getChannel() != null &&
                (event.getChannel().startsWith(TDC_CHANNEL_PREFIX) || event.getChannel().startsWith(TDC_NATION_PREFIX)))) return;
        if (minecraftChatListener.isCurrentTownChannel(event.getPlayer())) event.setCancelled(true);
    }

    /**
     * Messages in a town or nation channel are relayed to that town or nation by TDCDiscordChatListener. Stop
     * DiscordSRV's own Discord-to-Minecraft relay for those channels, which without a TownyChat hook would broadcast
     * them to the whole server.
     */
    @Subscribe(priority = ListenerPriority.HIGHEST)
    public void onDiscordMessage(DiscordGuildMessagePreProcessEvent event) {
        String game = DiscordSRV.getPlugin().getDestinationGameChannelNameForTextChannel(event.getChannel());
        if (game != null && (game.startsWith(TDC_CHANNEL_PREFIX) || game.startsWith(TDC_NATION_PREFIX))) event.setCancelled(true);
    }
}
