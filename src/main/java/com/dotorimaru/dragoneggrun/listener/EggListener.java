package com.dotorimaru.dragoneggrun.listener;

import com.dotorimaru.dragoneggrun.DragonEggRunPlugin;
import com.dotorimaru.dragoneggrun.game.GameManager;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public class EggListener implements Listener {

    private final DragonEggRunPlugin plugin;

    public EggListener(DragonEggRunPlugin plugin) {
        this.plugin = plugin;
    }

    private GameManager g() { return plugin.game(); }

    // 소지자(머리 위 알) 우클릭 → 강탈
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return; // 양손 중복 방지
        Entity target = e.getRightClicked();
        if (!(target instanceof Player victim)) return;
        Player holder = g().getHolder();
        if (holder == null || !holder.getUniqueId().equals(victim.getUniqueId())) return;
        g().steal(e.getPlayer(), victim);
    }

    // 맵/떨어진 알 블록 우클릭 → 줍기
    @EventHandler
    public void onInteractBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.DRAGON_EGG) return;
        if (g().phase() != GameManager.Phase.RUNNING) return; // 게임 중에만 개입
        e.setCancelled(true); // 바닐라 텔레포트 방지
        g().grabFromBlock(e.getPlayer(), b);
    }

    // 소지자 사망 → 그 자리에 알 떨굼
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Player holder = g().getHolder();
        if (holder == null || !holder.getUniqueId().equals(p.getUniqueId())) return;
        e.getDrops().removeIf(it -> it.getType() == Material.DRAGON_EGG);
        g().dropToBlock(p.getLocation());
    }

    // 소지자 접속종료 → 알 떨굼
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player holder = g().getHolder();
        if (holder != null && holder.getUniqueId().equals(e.getPlayer().getUniqueId())) {
            g().dropToBlock(e.getPlayer().getLocation());
        }
    }

    // 게임 중 접속자 → 술래 기본 숨김 + 보스바 추가
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (g().phase() == GameManager.Phase.RUNNING) {
            g().onViewerJoin(e.getPlayer());
        }
    }

    // 빙결: 이동 잠금 (시선 회전은 허용)
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!g().isFrozen(e.getPlayer().getUniqueId())) return;
        Location to = e.getTo();
        if (to == null) return;
        Location lock = g().frozenLocation(e.getPlayer().getUniqueId());
        if (lock == null) return;
        if (to.getX() != lock.getX() || to.getY() != lock.getY() || to.getZ() != lock.getZ()) {
            Location fixed = lock.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            e.setTo(fixed);
        }
    }

    // 게임 중 드래곤알 아이템 버리기 방지 (평상시 서버엔 영향 없음)
    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (g().phase() == GameManager.Phase.IDLE) return;
        if (e.getItemDrop().getItemStack().getType() == Material.DRAGON_EGG) {
            e.setCancelled(true);
        }
    }
}
