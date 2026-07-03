package com.dotorimaru.dragoneggrun;

import com.dotorimaru.dragoneggrun.command.EggCommand;
import com.dotorimaru.dragoneggrun.game.GameManager;
import com.dotorimaru.dragoneggrun.listener.EggListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class DragonEggRunPlugin extends JavaPlugin {

    private GameManager game;

    public GameManager game() {
        return game;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        game = new GameManager(this);
        game.loadConfig();

        getServer().getPluginManager().registerEvents(new EggListener(this), this);
        var cmd = getCommand("드래곤알");
        if (cmd != null) cmd.setExecutor(new EggCommand(this));

        getServer().getScheduler().runTaskTimer(this, () -> game.tick(), 20L, 2L);
        getServer().getScheduler().runTaskTimer(this, () -> game.freezeTick(), 20L, 20L);

        getLogger().info("DragonEggRun 활성화 (Paper 1.21.8)");
    }

    @Override
    public void onDisable() {
        if (game != null) game.shutdown();
    }

    public void reload() {
        game.loadConfig();
    }
}
