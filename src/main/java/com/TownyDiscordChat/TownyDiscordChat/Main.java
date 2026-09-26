package com.TownyDiscordChat.TownyDiscordChat;

import com.TownyDiscordChat.TownyDiscordChat.Listeners.TDCDiscordChatListener;
import com.TownyDiscordChat.TownyDiscordChat.Listeners.TDCDiscordSRVListener;
import com.TownyDiscordChat.TownyDiscordChat.Listeners.TDCMinecraftChatListener;
import com.TownyDiscordChat.TownyDiscordChat.Listeners.TDCTownyListener;
import github.scarsz.discordsrv.DiscordSRV;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class Main extends JavaPlugin {

    private TDCManager manager;
    private TDCDiscordChatListener discordChatListener;
    private TDCDiscordSRVListener discordSRVListener;
    private TDCLocales locales;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        locales = new TDCLocales(this);

        manager = new TDCManager(this);
        Objects.requireNonNull(getCommand("townydiscordchat")).setExecutor(new TDCCommand(this));
        TDCTownyListener townyListener = new TDCTownyListener(this);
        getServer().getPluginManager().registerEvents(townyListener, this);
        townyListener.registerOptionalBankListener();
        townyListener.registerOptionalLawListeners();
        townyListener.registerOptionalTownChangeListeners();
        TDCMinecraftChatListener minecraftChatListener = new TDCMinecraftChatListener(this);
        getServer().getPluginManager().registerEvents(minecraftChatListener, this);

        discordSRVListener = new TDCDiscordSRVListener(this, minecraftChatListener);
        DiscordSRV.api.subscribe(discordSRVListener);
        discordChatListener = new TDCDiscordChatListener(this);
        // DiscordSRV logs in on its own thread, so Discord may not be ready yet: attach now or once it is
        readyListener = new ReadyListener();
        DiscordSRV.api.subscribe(readyListener);
        if (DiscordSRV.isReady) attachToDiscord();
        getLogger().info("TownyDiscordChat " + getDescription().getVersion() + " enabled for Paper 1.21+/26+.");
    }

    @Override
    public void onDisable() {
        if (discordSRVListener != null && DiscordSRV.api != null) {
            DiscordSRV.api.unsubscribe(discordSRVListener);
            discordSRVListener = null;
        }
        if (readyListener != null && DiscordSRV.api != null) {
            DiscordSRV.api.unsubscribe(readyListener);
            readyListener = null;
        }
        if (discordChatListener != null && DiscordSRV.getPlugin() != null && DiscordSRV.getPlugin().getJda() != null) {
            DiscordSRV.getPlugin().getJda().removeEventListener(discordChatListener);
        }
        getLogger().info("TownyDiscordChat disabled.");
    }

    private ReadyListener readyListener;
    private boolean attached;

    /** Registers the Discord listener and slash commands and runs the first sync, once Discord is connected. */
    private synchronized void attachToDiscord() {
        if (attached || !isEnabled() || DiscordSRV.getPlugin().getJda() == null) return;
        attached = true;
        DiscordSRV.getPlugin().getJda().addEventListener(discordChatListener);
        manager.registerSlashCommands();
        getServer().getScheduler().runTask(this, () -> {
            manager.synchroniseAllResources();
            manager.synchroniseAllLinkedAccounts();
        });
        getLogger().info("Connected to Discord.");
    }

    public final class ReadyListener {
        @github.scarsz.discordsrv.api.Subscribe
        public void onReady(github.scarsz.discordsrv.api.events.DiscordReadyEvent event) {
            getServer().getScheduler().runTask(Main.this, Main.this::attachToDiscord);
        }
    }

    public TDCManager manager() {
        return manager;
    }

    public FileConfiguration configuration() {
        return getConfig();
    }

    public TDCLocales locales() {
        return locales;
    }
}
