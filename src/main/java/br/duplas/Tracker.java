package br.duplas;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.GameMode;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Detecta invasoes e registra o que o invasor fez dentro da estrutura. */
public class Tracker {

    private record Session(Dupla owner, Structures.Def def, Alert alert) {}

    private final DuplasPlugin pl;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Map<Material, Integer>> snaps = new HashMap<>();

    public Tracker(DuplasPlugin pl) {
        this.pl = pl;
    }

    public void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                check(p);
            } catch (Exception ex) {
                pl.getLogger().warning("Erro ao checar " + p.getName() + ": " + ex);
            }
        }
    }

    private boolean allowed(Player p, Dupla owner) {
        return owner.members.contains(p.getUniqueId()) && !pl.renegados.contains(p.getUniqueId());
    }

    private boolean inside(GeneratedStructure gs, Location l) {
        double m = pl.getConfig().getDouble("margem", 1.0);
        for (StructurePiece pc : gs.getPieces()) {
            if (pc.getBoundingBox().clone().expand(m).contains(l.getX(), l.getY(), l.getZ())) return true;
        }
        return false;
    }

    private void check(Player p) {
        UUID id = p.getUniqueId();
        World w = p.getWorld();
        if (p.hasPermission("duplas.ignorar") || p.getGameMode() == GameMode.SPECTATOR) {
            end(id);
            return;
        }
        Location l = p.getLocation();
        int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
        if (!w.isChunkLoaded(cx, cz)) return;

        Dupla hitOwner = null;
        Structures.Def hitDef = null;
        for (GeneratedStructure gs : w.getStructures(cx, cz)) {
            Structures.Def def = Structures.byKey(gs.getStructure().getKey().getKey());
            if (def == null) continue;
            Dupla owner = pl.ownerOfStructure(def.id());
            if (owner == null || allowed(p, owner)) continue;
            if (!inside(gs, l)) continue;
            hitOwner = owner;
            hitDef = def;
            break;
        }

        Session s = sessions.get(id);
        if (hitOwner == null) {
            if (s != null) end(id);
            return;
        }
        if (s != null && s.owner() == hitOwner && s.def() == hitDef) return;
        if (s != null) end(id);
        start(p, hitOwner, hitDef);
    }

    private void start(Player p, Dupla owner, Structures.Def def) {
        Alert a = new Alert();
        a.intruder = p.getName();
        Dupla mine = pl.duplaOf(p.getUniqueId());
        a.intruderDupla = mine != null ? mine.name : "sem dupla";
        a.structure = def.display();
        Location l = p.getLocation();
        a.world = l.getWorld().getName();
        a.x = l.getBlockX();
        a.y = l.getBlockY();
        a.z = l.getBlockZ();
        a.enter = System.currentTimeMillis();

        owner.alerts.add(a);
        while (owner.alerts.size() > 200) owner.alerts.remove(0);
        sessions.put(p.getUniqueId(), new Session(owner, def, a));
        pl.markDirty();

        pl.notifyDupla(owner, "§c§l⚠ §c" + a.intruder + " §7(dupla §f" + a.intruderDupla
                + "§7) está invadindo sua estrutura §e" + def.display() + " §7em §f" + a.x + " " + a.y + " " + a.z);
    }

    public void end(UUID id) {
        Session s = sessions.remove(id);
        snaps.remove(id);
        if (s == null) return;
        s.alert().exit = System.currentTimeMillis();
        pl.markDirty();
        pl.notifyDupla(s.owner(), "§7Resumo da invasão:");
        for (String line : s.alert().lines()) pl.notifyDupla(s.owner(), line);
    }

    public void endAll() {
        for (UUID id : new ArrayList<>(sessions.keySet())) end(id);
    }

    // ---------- registro de acoes ----------

    private static Map<Material, Integer> count(ItemStack[] items) {
        Map<Material, Integer> m = new HashMap<>();
        for (ItemStack it : items) {
            if (it != null && !it.getType().isAir()) m.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        return m;
    }

    public void onOpen(Player p, ItemStack[] contents) {
        if (sessions.containsKey(p.getUniqueId())) snaps.put(p.getUniqueId(), count(contents));
    }

    public void onClose(Player p, ItemStack[] contents) {
        Map<Material, Integer> before = snaps.remove(p.getUniqueId());
        Session s = sessions.get(p.getUniqueId());
        if (before == null || s == null) return;
        Map<Material, Integer> after = count(contents);
        for (Map.Entry<Material, Integer> e : before.entrySet()) {
            int diff = e.getValue() - after.getOrDefault(e.getKey(), 0);
            if (diff > 0) s.alert().taken.merge(e.getKey().name(), diff, Integer::sum);
        }
        pl.markDirty();
    }

    public void onPickup(Player p, ItemStack st) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        s.alert().taken.merge(st.getType().name(), st.getAmount(), Integer::sum);
        pl.markDirty();
    }

    public void onBreak(Player p, Material m) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        s.alert().broken.merge(m.name(), 1, Integer::sum);
        pl.markDirty();
    }
}
