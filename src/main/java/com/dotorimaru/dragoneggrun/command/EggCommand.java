package com.dotorimaru.dragoneggrun.command;

import com.dotorimaru.dragoneggrun.DragonEggRunPlugin;
import com.dotorimaru.dragoneggrun.game.GameManager;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class EggCommand implements TabExecutor {

    private final DragonEggRunPlugin plugin;

    public EggCommand(DragonEggRunPlugin plugin) {
        this.plugin = plugin;
    }

    private GameManager g() { return plugin.game(); }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("eggrun.admin")) return true;
        s.sendMessage("§c권한이 없습니다.");
        return false;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) { help(s); return true; }

        switch (a[0]) {
            case "모집" -> {
                if (!admin(s)) return true;
                g().openLobby();
            }
            case "참가" -> {
                Player target = resolve(s, a);
                if (target == null) return true;
                if (target != s && !admin(s)) return true;
                g().join(target);
            }
            case "퇴장" -> {
                Player target = resolve(s, a);
                if (target == null) return true;
                if (target != s && !admin(s)) return true;
                g().leave(target);
            }
            case "인원" -> {
                List<String> names = g().participantNames();
                s.sendMessage("§e참가자(" + names.size() + "): §f" +
                        (names.isEmpty() ? "없음" : String.join(", ", names)));
            }
            case "모드" -> {
                if (!admin(s)) return true;
                if (a.length < 2) { s.sendMessage("§e/드래곤알 모드 <랜덤|맵>"); return true; }
                if (a[1].equals("랜덤")) { g().setMode(GameManager.Mode.RANDOM); g().send0(s, "mode-set", Map.of("mode", "랜덤")); }
                else if (a[1].equals("맵")) { g().setMode(GameManager.Mode.MAP); g().send0(s, "mode-set", Map.of("mode", "맵")); }
                else s.sendMessage("§e/드래곤알 모드 <랜덤|맵>");
            }
            case "위치설정" -> {
                if (!admin(s)) return true;
                if (!(s instanceof Player p)) { s.sendMessage("§c플레이어만 가능합니다."); return true; }
                g().setMapLocation(p.getLocation());
                g().send0(s, "map-loc-set", Map.of());
            }
            case "준비시간" -> {
                if (!admin(s)) return true;
                Integer sec = parsePositive(a, s);
                if (sec == null) return true;
                g().setPrepSeconds(sec);
                g().send0(s, "prep-set", Map.of("sec", String.valueOf(sec)));
            }
            case "진행시간" -> {
                if (!admin(s)) return true;
                Integer sec = parsePositive(a, s);
                if (sec == null) return true;
                g().setGameSeconds(sec);
                g().send0(s, "game-set", Map.of("sec", String.valueOf(sec)));
            }
            case "시작" -> {
                if (!admin(s)) return true;
                if (g().mode() == GameManager.Mode.MAP && !g().hasMapLocation()) {
                    g().send0(s, "no-map-loc", Map.of()); return true;
                }
                if (!g().start()) { g().send0(s, "need-more", Map.of()); }
            }
            case "중지" -> {
                if (!admin(s)) return true;
                g().stop(false);
            }
            case "새로고침" -> {
                if (!admin(s)) return true;
                plugin.reload();
                s.sendMessage("§a설정을 새로고침했습니다.");
            }
            default -> help(s);
        }
        return true;
    }

    private Player resolve(CommandSender s, String[] a) {
        if (a.length >= 2) {
            Player t = plugin.getServer().getPlayerExact(a[1]);
            if (t == null) s.sendMessage("§c대상 플레이어를 찾을 수 없습니다.");
            return t;
        }
        if (s instanceof Player p) return p;
        s.sendMessage("§c플레이어 이름을 지정하세요.");
        return null;
    }

    /** a[1] 을 양의 정수(초)로 파싱. 실패 시 메시지 출력 후 null */
    private Integer parsePositive(String[] a, CommandSender s) {
        if (a.length < 2) { g().send0(s, "bad-number", Map.of()); return null; }
        try {
            int v = Integer.parseInt(a[1]);
            if (v <= 0) { g().send0(s, "bad-number", Map.of()); return null; }
            return v;
        } catch (NumberFormatException e) {
            g().send0(s, "bad-number", Map.of());
            return null;
        }
    }

    private void help(CommandSender s) {
        s.sendMessage("§d§l[ 드래곤알 들고 도망가기 ]");
        s.sendMessage("§e/드래곤알 모집 §7- 참가 모집 시작");
        s.sendMessage("§e/드래곤알 참가 [닉] §7- 게임 참가");
        s.sendMessage("§e/드래곤알 퇴장 [닉] §7- 게임 퇴장");
        s.sendMessage("§e/드래곤알 인원 §7- 참가자 목록");
        s.sendMessage("§e/드래곤알 모드 <랜덤|맵> §7- 알 지급 방식");
        s.sendMessage("§e/드래곤알 위치설정 §7- (맵 모드) 알 생성 위치 저장");
        s.sendMessage("§e/드래곤알 준비시간 <초> §7- 흩어지는 준비 시간 설정");
        s.sendMessage("§e/드래곤알 진행시간 <초> §7- 게임 진행 시간 설정");
        s.sendMessage("§e/드래곤알 시작 §7- 게임 시작");
        s.sendMessage("§e/드래곤알 중지 §7- 게임 종료");
        s.sendMessage("§e/드래곤알 새로고침 §7- 설정 리로드");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 1) {
            return filter(Arrays.asList("모집", "참가", "퇴장", "인원", "모드", "위치설정", "준비시간", "진행시간", "시작", "중지", "새로고침"), a[0]);
        }
        if (a.length == 2 && a[0].equals("모드")) return filter(Arrays.asList("랜덤", "맵"), a[1]);
        if (a.length == 2 && (a[0].equals("준비시간") || a[0].equals("진행시간"))) {
            return filter(Arrays.asList("30", "60", "120", "180", "300", "600"), a[1]);
        }
        if (a.length == 2 && (a[0].equals("참가") || a[0].equals("퇴장"))) {
            List<String> names = new ArrayList<>();
            plugin.getServer().getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return filter(names, a[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> opts, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.startsWith(prefix)) out.add(o);
        return out;
    }
}
