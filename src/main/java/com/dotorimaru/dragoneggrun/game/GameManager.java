package com.dotorimaru.dragoneggrun.game;

import com.dotorimaru.dragoneggrun.DragonEggRunPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class GameManager {

    public enum Phase { IDLE, LOBBY, PREP, RUNNING }
    public enum Mode { RANDOM, MAP }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final DragonEggRunPlugin plugin;

    private Phase phase = Phase.IDLE;
    private Mode mode = Mode.RANDOM;

    private final LinkedHashSet<UUID> participants = new LinkedHashSet<>();
    private final Map<UUID, Color> colors = new HashMap<>();

    private UUID holderId;
    private UUID displayId;
    private long graceUntil;
    private Location mapEggLoc;          // 진행 중 떨어진/생성된 알 블록 위치

    private final Map<UUID, Long> frozenUntil = new HashMap<>();
    private final Map<UUID, Location> frozenLoc = new HashMap<>();

    // footprint
    private Location lastFootLoc;
    private boolean footSide;
    private final Deque<Footprint> footprints = new ArrayDeque<>();
    private record Footprint(Location loc, Color color, long bornAt) {}

    private int prepTaskId = -1;
    private int gameTaskId = -1;

    // stealth (per-viewer 가시성)
    private final Set<UUID> revealedTo = new HashSet<>();
    private Location campLastLoc;
    private long stillSince;
    private boolean exposed;

    // bossbar
    private BossBar bossBar;

    // ===== config cache =====
    private long nightStart, nightEnd;
    private boolean footEnabled;
    private boolean footNightOnly;
    private double stepDistance, footViewDistance;
    private long footLifetimeTicks;
    private float footSize;
    private List<Color> palette;
    private int prepSeconds, freezeSeconds, graceSeconds, gameSeconds;
    private float dispScale, dispYOffset;
    private double revealRadius;
    private int campSeconds;

    public GameManager(DragonEggRunPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.reloadConfig();
        var c = plugin.getConfig();
        mode = "map".equalsIgnoreCase(c.getString("mode", "random")) ? Mode.MAP : Mode.RANDOM;
        prepSeconds = c.getInt("prep-seconds", 60);
        gameSeconds = c.getInt("game-seconds", 300);
        freezeSeconds = c.getInt("steal.freeze-seconds", 10);
        graceSeconds = c.getInt("steal.grace-seconds", 2);
        dispScale = (float) c.getDouble("display.scale", 0.5);
        dispYOffset = (float) c.getDouble("display.y-offset", 0.7);
        footEnabled = c.getBoolean("footprint.enabled", true);
        stepDistance = c.getDouble("footprint.step-distance", 0.6);
        footLifetimeTicks = c.getLong("footprint.lifetime-ticks", 120);
        footSize = (float) c.getDouble("footprint.size", 0.9);
        footViewDistance = c.getDouble("footprint.view-distance", 48);
        nightStart = c.getLong("night.start", 13000);
        nightEnd = c.getLong("night.end", 23000);
        footNightOnly = c.getBoolean("footprint.night-only", false);
        revealRadius = c.getDouble("stealth.reveal-radius", 5.0);
        campSeconds = c.getInt("stealth.camp-seconds", 3);

        palette = new ArrayList<>();
        for (String h : c.getStringList("footprint.colors")) palette.add(parseHex(h));
        if (palette.isEmpty()) palette.add(Color.WHITE);

        String w = c.getString("map-egg.world", "");
        if (w != null && !w.isEmpty() && Bukkit.getWorld(w) != null) {
            mapEggLoc = new Location(Bukkit.getWorld(w),
                    c.getDouble("map-egg.x"), c.getDouble("map-egg.y"), c.getDouble("map-egg.z"));
        }
    }

    // =================== 모집 / 참가 ===================
    public Phase phase() { return phase; }
    public Mode mode() { return mode; }
    public boolean isParticipant(UUID id) { return participants.contains(id); }

    public void openLobby() {
        stop(true); // 조용히 초기화
        phase = Phase.LOBBY;
        broadcast("lobby-open", Map.of());
    }

    public boolean join(Player p) {
        if (phase != Phase.LOBBY) { send(p, "not-in-lobby", Map.of()); return false; }
        if (!participants.add(p.getUniqueId())) { send(p, "already-joined", Map.of()); return false; }
        assignColor(p.getUniqueId());
        broadcast("joined", Map.of("player", p.getName(), "count", String.valueOf(participants.size())));
        return true;
    }

    public void leave(Player p) {
        if (participants.remove(p.getUniqueId())) {
            colors.remove(p.getUniqueId());
            broadcast("left", Map.of("player", p.getName()));
        } else {
            send(p, "not-joined", Map.of());
        }
    }

    private void assignColor(UUID id) {
        int idx = colors.size();
        colors.put(id, palette.get(idx % palette.size()));
    }

    public List<String> participantNames() {
        List<String> out = new ArrayList<>();
        for (UUID id : participants) {
            var op = Bukkit.getOfflinePlayer(id);
            out.add(op.getName() != null ? op.getName() : id.toString());
        }
        return out;
    }

    public void setMode(Mode m) {
        mode = m;
        plugin.getConfig().set("mode", m == Mode.MAP ? "map" : "random");
        plugin.saveConfig();
    }

    public void setPrepSeconds(int s) {
        prepSeconds = s;
        plugin.getConfig().set("prep-seconds", s);
        plugin.saveConfig();
    }

    public void setGameSeconds(int s) {
        gameSeconds = s;
        plugin.getConfig().set("game-seconds", s);
        plugin.saveConfig();
    }

    public void setMapLocation(Location l) {
        mapEggLoc = l.getBlock().getLocation();
        plugin.getConfig().set("map-egg.world", l.getWorld().getName());
        plugin.getConfig().set("map-egg.x", mapEggLoc.getBlockX());
        plugin.getConfig().set("map-egg.y", mapEggLoc.getBlockY());
        plugin.getConfig().set("map-egg.z", mapEggLoc.getBlockZ());
        plugin.saveConfig();
    }

    public boolean hasMapLocation() { return mapEggLoc != null; }

    // =================== 시작 / 준비 / 지급 ===================
    public boolean start() {
        if (participants.size() < 2) return false;       // need-more
        if (mode == Mode.MAP && mapEggLoc == null) return false; // no-map-loc (구분 위해 호출부 처리)
        phase = Phase.PREP;
        broadcast("prep-start", Map.of("sec", String.valueOf(prepSeconds)));
        final int[] remain = { prepSeconds };
        prepTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remain[0]--;
            if (remain[0] == 30 || remain[0] == 10 || (remain[0] <= 5 && remain[0] > 0)) {
                broadcast("prep-count", Map.of("sec", String.valueOf(remain[0])));
            }
            if (remain[0] <= 0) {
                Bukkit.getScheduler().cancelTask(prepTaskId);
                prepTaskId = -1;
                beginRunning();
            }
        }, 20L, 20L).getTaskId();
        return true;
    }

    private void beginRunning() {
        phase = Phase.RUNNING;
        if (mode == Mode.RANDOM) {
            List<Player> online = new ArrayList<>();
            for (UUID id : participants) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) online.add(p);
            }
            if (online.isEmpty()) { broadcast("no-online", Map.of()); phase = Phase.LOBBY; return; }
            Player chosen = online.get(new Random().nextInt(online.size()));
            setHolder(chosen);
            broadcast("give-random", Map.of("player", chosen.getName()));
        } else {
            spawnMapEgg();
            broadcast("map-spawn", Map.of());
        }
        startGameTimer();
    }

    private void startGameTimer() {
        broadcast("running-info", Map.of("sec", String.valueOf(gameSeconds)));
        createBossBar();
        final int[] remain = { gameSeconds };
        updateBossBar(remain[0]);
        gameTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remain[0]--;
            updateBossBar(remain[0]);
            if (remain[0] == 60 || remain[0] == 30 || remain[0] == 10
                    || (remain[0] <= 5 && remain[0] > 0)) {
                broadcast("game-count", Map.of("sec", String.valueOf(remain[0])));
            }
            if (remain[0] <= 0) {
                Bukkit.getScheduler().cancelTask(gameTaskId);
                gameTaskId = -1;
                endByTime();
            }
        }, 20L, 20L).getTaskId();
    }

    private void createBossBar() {
        removeBossBar();
        bossBar = Bukkit.createBossBar("§e남은 시간", BarColor.YELLOW, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);
    }

    private void updateBossBar(int remain) {
        if (bossBar == null) return;
        if (remain < 0) remain = 0;
        double prog = gameSeconds <= 0 ? 0 : Math.max(0.0, Math.min(1.0, (double) remain / gameSeconds));
        bossBar.setProgress(prog);
        bossBar.setColor(remain <= 30 ? BarColor.RED : BarColor.YELLOW);
        bossBar.setTitle("§e남은 시간 §f" + formatTime(remain));
        // 중간 접속자 보강
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!bossBar.getPlayers().contains(p)) bossBar.addPlayer(p);
        }
    }

    private void removeBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
    }

    private static String formatTime(int sec) {
        int m = sec / 60, s = sec % 60;
        return m + ":" + (s < 10 ? "0" + s : s);
    }

    private void endByTime() {
        Player winner = getHolder();
        if (winner != null) {
            broadcast("win-holder", Map.of("player", winner.getName()));
        } else {
            broadcast("win-none", Map.of());
        }
        stop(true); // 승리 공지로 마무리, "종료" 메시지는 생략
    }

    private void spawnMapEgg() {
        if (mapEggLoc == null) return;
        mapEggLoc.getBlock().setType(Material.DRAGON_EGG);
    }

    // =================== 소지자 / 강탈 ===================
    public Player getHolder() { return holderId == null ? null : Bukkit.getPlayer(holderId); }
    public boolean hasHolder() { return getHolder() != null; }

    private void setHolder(Player p) {
        Player old = getHolder();
        if (old != null) restoreVisibility(old);
        removeDisplay();
        holderId = p.getUniqueId();
        graceUntil = System.currentTimeMillis() + graceSeconds * 1000L;
        lastFootLoc = null;
        resetCamp(p);
        spawnDisplay(p);
        hideFromAll(p);   // 기본은 전원에게 투명, 가까운 사람에게만 매 틱 노출
    }

    /** 맵/떨어진 알 블록 우클릭으로 줍기 */
    public boolean grabFromBlock(Player p, org.bukkit.block.Block block) {
        if (phase != Phase.RUNNING) return false;
        if (hasHolder()) return false;
        if (!isParticipant(p.getUniqueId())) { send(p, "not-participant", Map.of()); return false; }
        block.setType(Material.AIR);
        mapEggLoc = null;
        setHolder(p);
        broadcast("grabbed", Map.of("player", p.getName()));
        return true;
    }

    /** 소지자 우클릭으로 강탈 */
    public void steal(Player thief, Player victim) {
        if (phase != Phase.RUNNING) return;
        Player holder = getHolder();
        if (holder == null || !holder.getUniqueId().equals(victim.getUniqueId())) return;
        if (thief.getUniqueId().equals(victim.getUniqueId())) return;
        if (!isParticipant(thief.getUniqueId())) { send(thief, "not-participant", Map.of()); return; }
        if (System.currentTimeMillis() < graceUntil) return; // 획득 직후 보호
        if (isFrozen(thief.getUniqueId())) return;

        setHolder(thief);
        freeze(victim, freezeSeconds);
        broadcast("stolen", Map.of("thief", thief.getName(), "victim", victim.getName()));
        send(victim, "frozen", Map.of("sec", String.valueOf(freezeSeconds)));
    }

    /** 사망/접속종료 시 알을 블록으로 떨굼 */
    public void dropToBlock(Location loc) {
        Player old = getHolder();
        if (old != null) restoreVisibility(old);
        removeDisplay();
        holderId = null;
        lastFootLoc = null;
        org.bukkit.block.Block b = loc.getBlock();
        if (!b.getType().isAir()) b = b.getRelative(0, 1, 0);
        b.setType(Material.DRAGON_EGG);
        mapEggLoc = b.getLocation();
        broadcast("dropped", Map.of());
    }

    // =================== 머리 위 ItemDisplay ===================
    private void spawnDisplay(Player p) {
        World w = p.getWorld();
        Location at = p.getEyeLocation().add(0, dispYOffset, 0);
        ItemDisplay disp = w.spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.DRAGON_EGG));
            d.setBillboard(Display.Billboard.FIXED);
            d.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new Quaternionf(),
                    new Vector3f(dispScale, dispScale, dispScale),
                    new Quaternionf()));
            d.setTeleportDuration(2);   // 부드러운 따라오기
            d.setPersistent(false);
        });
        displayId = disp.getUniqueId();
    }

    private void removeDisplay() {
        if (displayId == null) return;
        Entity e = Bukkit.getEntity(displayId);
        if (e != null) e.remove();
        displayId = null;
    }

    // =================== 매 2틱: 표시 따라오기 + 발자국 ===================
    public void tick() {
        Player holder = getHolder();

        // 머리 위 알 따라오기
        if (holder != null && displayId != null) {
            Entity e = Bukkit.getEntity(displayId);
            if (e instanceof ItemDisplay disp) {
                Location loc = holder.getEyeLocation().add(0, dispYOffset, 0);
                loc.setYaw(holder.getLocation().getYaw());
                loc.setPitch(0);
                disp.teleport(loc);
            } else {
                displayId = null;
            }
        }

        if (phase == Phase.RUNNING) {
            updateStealth(holder);
            footprintTick(holder);
        }
    }

    // =================== 술래 가시성 (per-viewer 투명 + 캠핑 발광) ===================
    private void updateStealth(Player holder) {
        if (holder == null) return;
        long now = System.currentTimeMillis();

        // 캠핑(정지) 감지 — 수평 이동 기준
        Location cur = holder.getLocation();
        if (campLastLoc == null || campLastLoc.getWorld() == null
                || !campLastLoc.getWorld().equals(cur.getWorld())) {
            campLastLoc = cur.clone();
            stillSince = now;
        } else {
            double dx = cur.getX() - campLastLoc.getX();
            double dz = cur.getZ() - campLastLoc.getZ();
            if (Math.sqrt(dx * dx + dz * dz) > 0.1) {
                stillSince = now;
                campLastLoc = cur.clone();
            }
        }
        exposed = (now - stillSince) >= campSeconds * 1000L;
        holder.setGlowing(exposed);

        // 보여줄 대상 결정
        Set<UUID> desired = new HashSet<>();
        double maxSq = revealRadius * revealRadius;
        for (Player p : holder.getWorld().getPlayers()) {
            if (p.getUniqueId().equals(holder.getUniqueId())) continue;
            if (exposed || p.getLocation().distanceSquared(holder.getLocation()) <= maxSq) {
                desired.add(p.getUniqueId());
            }
        }
        applyVisibility(holder, desired);
    }

    private void applyVisibility(Player holder, Set<UUID> desired) {
        Entity disp = displayEntity();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(holder.getUniqueId())) continue;
            boolean want = desired.contains(p.getUniqueId());
            boolean has = revealedTo.contains(p.getUniqueId());
            if (want && !has) {
                p.showPlayer(plugin, holder);
                if (disp != null) p.showEntity(plugin, disp);
                revealedTo.add(p.getUniqueId());
            } else if (!want && has) {
                p.hidePlayer(plugin, holder);
                if (disp != null) p.hideEntity(plugin, disp);
                revealedTo.remove(p.getUniqueId());
            }
        }
    }

    /** 술래를 전원에게서 숨김(기본 투명 상태) */
    private void hideFromAll(Player holder) {
        Entity disp = displayEntity();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(holder.getUniqueId())) continue;
            p.hidePlayer(plugin, holder);
            if (disp != null) p.hideEntity(plugin, disp);
        }
        revealedTo.clear();
    }

    /** 술래에서 벗어났을 때 전원에게 다시 보이도록 복구 */
    private void restoreVisibility(Player holder) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(holder.getUniqueId())) continue;
            p.showPlayer(plugin, holder);
        }
        holder.setGlowing(false);
        revealedTo.clear();
    }

    /** 게임 중 새로 접속한 플레이어에게 술래를 기본 숨김 처리 + 보스바 추가 */
    public void onViewerJoin(Player p) {
        Player holder = getHolder();
        if (holder != null && !p.getUniqueId().equals(holder.getUniqueId())) {
            p.hidePlayer(plugin, holder);
            Entity disp = displayEntity();
            if (disp != null) p.hideEntity(plugin, disp);
            revealedTo.remove(p.getUniqueId());
        }
        if (bossBar != null) bossBar.addPlayer(p);
    }

    private void resetCamp(Player p) {
        campLastLoc = p.getLocation().clone();
        stillSince = System.currentTimeMillis();
        exposed = false;
    }

    private Entity displayEntity() {
        return displayId == null ? null : Bukkit.getEntity(displayId);
    }

    private void footprintTick(Player holder) {
        long now = System.currentTimeMillis();
        long lifeMs = footLifetimeTicks * 50L;
        Color color = (holder != null) ? colors.getOrDefault(holder.getUniqueId(), Color.WHITE) : Color.WHITE;

        if (holder != null && footEnabled && (!footNightOnly || isNight(holder.getWorld()))) {
            Location cur = holder.getLocation();
            if (lastFootLoc == null || !lastFootLoc.getWorld().equals(cur.getWorld())) {
                lastFootLoc = cur.clone();
            } else {
                double dx = cur.getX() - lastFootLoc.getX();
                double dz = cur.getZ() - lastFootLoc.getZ();
                double horiz = Math.sqrt(dx * dx + dz * dz);
                if (horiz >= stepDistance) {
                    double px = -dz / horiz, pz = dx / horiz;
                    double side = footSide ? 1 : -1; footSide = !footSide;
                    Location fp = cur.clone().add(px * 0.18 * side, 0.05, pz * 0.18 * side);
                    footprints.add(new Footprint(fp, color, now));
                    lastFootLoc = cur.clone();
                }
            }
        }

        Iterator<Footprint> it = footprints.iterator();
        while (it.hasNext()) {
            Footprint f = it.next();
            long age = now - f.bornAt();
            if (age > lifeMs) { it.remove(); continue; }
            double frac = 1.0 - (double) age / lifeMs;
            float size = (float) Math.max(0.25, footSize * frac);
            Particle.DustOptions opt = new Particle.DustOptions(f.color(), size);
            World w = f.loc().getWorld();
            if (w == null) continue;
            double maxSq = footViewDistance * footViewDistance;
            for (Player viewer : w.getPlayers()) {
                if (viewer.getLocation().distanceSquared(f.loc()) <= maxSq) {
                    viewer.spawnParticle(Particle.DUST, f.loc(), 2, 0.04, 0.01, 0.04, 0, opt);
                }
            }
        }
    }

    // =================== 빙결 ===================
    private void freeze(Player p, int seconds) {
        frozenUntil.put(p.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
        frozenLoc.put(p.getUniqueId(), p.getLocation().clone());
        p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        p.setWalkSpeed(0f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, seconds * 20, 6, false, false, false));
    }

    public boolean isFrozen(UUID id) {
        Long until = frozenUntil.get(id);
        return until != null && System.currentTimeMillis() < until;
    }

    public Location frozenLocation(UUID id) { return frozenLoc.get(id); }

    private void unfreeze(Player p) {
        frozenUntil.remove(p.getUniqueId());
        frozenLoc.remove(p.getUniqueId());
        p.setWalkSpeed(0.2f);
        p.removePotionEffect(PotionEffectType.SLOWNESS);
    }

    /** 매 초 호출: 만료 해제 + 액션바 */
    public void freezeTick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = frozenUntil.entrySet().iterator();
        List<Player> toUnfreeze = new ArrayList<>();
        while (it.hasNext()) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) { it.remove(); frozenLoc.remove(e.getKey()); continue; }
            long left = (e.getValue() - now) / 1000L + 1;
            if (now >= e.getValue()) { toUnfreeze.add(p); }
            else { p.sendActionBar(fmt(raw("freeze-actionbar", "&b❄ 빙결 {sec}초"),
                    Map.of("sec", String.valueOf(Math.max(0, left))))); }
        }
        for (Player p : toUnfreeze) unfreeze(p);
    }

    // =================== 종료 / 정리 ===================
    public void stop(boolean silent) {
        if (prepTaskId != -1) { Bukkit.getScheduler().cancelTask(prepTaskId); prepTaskId = -1; }
        if (gameTaskId != -1) { Bukkit.getScheduler().cancelTask(gameTaskId); gameTaskId = -1; }
        Player old = getHolder();
        if (old != null) restoreVisibility(old);
        removeBossBar();
        removeDisplay();
        holderId = null;
        for (UUID id : new ArrayList<>(frozenUntil.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) unfreeze(p);
        }
        frozenUntil.clear();
        frozenLoc.clear();
        footprints.clear();
        lastFootLoc = null;
        if (mapEggLoc != null && mapEggLoc.getBlock().getType() == Material.DRAGON_EGG) {
            mapEggLoc.getBlock().setType(Material.AIR);
        }
        mapEggLoc = null;
        participants.clear();
        colors.clear();
        phase = Phase.IDLE;
        if (!silent) broadcast("stopped", Map.of());
    }

    public void shutdown() { stop(true); }

    // =================== 유틸 ===================
    public boolean isNight(World w) {
        long t = w.getTime() % 24000;
        if (nightStart <= nightEnd) return t >= nightStart && t <= nightEnd;
        return t >= nightStart || t <= nightEnd;
    }

    public String raw(String key, String def) {
        return plugin.getConfig().getString("messages." + key, def);
    }

    public Component fmt(String s, Map<String, String> ph) {
        for (var e : ph.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue());
        return LEGACY.deserialize(s);
    }

    public void send(Player p, String key, Map<String, String> ph) {
        p.sendMessage(fmt(raw(key, key), ph));
    }

    public void send0(org.bukkit.command.CommandSender s, String key, Map<String, String> ph) {
        s.sendMessage(fmt(raw(key, key), ph));
    }

    public void broadcast(String key, Map<String, String> ph) {
        Bukkit.broadcast(fmt(raw(key, key), ph));
    }

    private static Color parseHex(String hex) {
        try {
            String h = hex.replace("#", "");
            return Color.fromRGB(
                    Integer.parseInt(h.substring(0, 2), 16),
                    Integer.parseInt(h.substring(2, 4), 16),
                    Integer.parseInt(h.substring(4, 6), 16));
        } catch (Exception e) { return Color.WHITE; }
    }
}
