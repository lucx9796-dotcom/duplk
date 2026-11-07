package br.duplas;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Alert {
    public static ZoneId zone = ZoneId.of("America/Sao_Paulo");

    public String intruder = "?", intruderDupla = "sem dupla", structure = "?", world = "?";
    public int x, y, z;
    public long enter, exit; // exit = 0 -> ainda la dentro
    public String note; // != null -> aviso de vigia (item/acao), nao invasao de area
    public final Map<String, Integer> taken = new LinkedHashMap<>();
    public final Map<String, Integer> broken = new LinkedHashMap<>();

    private static String fmt(long t) {
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(zone).format(Instant.ofEpochMilli(t));
    }

    private static String pretty(Map<String, Integer> m) {
        if (m.isEmpty()) return "§8nada";
        StringJoiner j = new StringJoiner("§7, §f");
        for (Map.Entry<String, Integer> e : m.entrySet())
            j.add(e.getValue() + "x " + e.getKey().toLowerCase().replace('_', ' '));
        return "§f" + j;
    }

    public List<String> lines() {
        List<String> l = new ArrayList<>();
        if (note != null) {
            l.add("§6§l⚠ AVISO §r§f" + intruder + " §7(dupla §f" + intruderDupla + "§7) §e" + note + " §7— §6" + structure);
            l.add("  §7Quando: §f" + fmt(enter) + " §7em §f" + world + " " + x + " " + y + " " + z);
            return l;
        }
        l.add("§c§l⚠ INVASÃO §r§f" + intruder + " §7(dupla §f" + intruderDupla + "§7) invadiu §e" + structure);
        l.add("  §7Entrou: §f" + fmt(enter) + " §7em §f" + world + " " + x + " " + y + " " + z);
        l.add("  §7Saiu: " + (exit == 0 ? "§cainda lá dentro" : "§f" + fmt(exit)));
        l.add("  §7Pegou: " + pretty(taken));
        if (!broken.isEmpty()) l.add("  §7Quebrou: " + pretty(broken));
        return l;
    }

    public void save(ConfigurationSection s) {
        s.set("intruder", intruder);
        s.set("intruderDupla", intruderDupla);
        s.set("structure", structure);
        s.set("world", world);
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        s.set("enter", enter);
        s.set("exit", exit);
        if (note != null) s.set("note", note);
        s.createSection("taken", taken);
        s.createSection("broken", broken);
    }

    public static Alert load(ConfigurationSection s) {
        Alert a = new Alert();
        a.intruder = s.getString("intruder", "?");
        a.intruderDupla = s.getString("intruderDupla", "sem dupla");
        a.structure = s.getString("structure", "?");
        a.world = s.getString("world", "?");
        a.x = s.getInt("x");
        a.y = s.getInt("y");
        a.z = s.getInt("z");
        a.enter = s.getLong("enter");
        a.exit = s.getLong("exit");
        a.note = s.getString("note");
        ConfigurationSection t = s.getConfigurationSection("taken");
        if (t != null) for (String k : t.getKeys(false)) a.taken.put(k, t.getInt(k));
        ConfigurationSection b = s.getConfigurationSection("broken");
        if (b != null) for (String k : b.getKeys(false)) a.broken.put(k, b.getInt(k));
        return a;
    }
}
