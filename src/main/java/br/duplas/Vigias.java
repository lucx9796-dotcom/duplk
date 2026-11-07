package br.duplas;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.raid.RaidTriggerEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.*;

/**
 * Avisos por estrutura: o dono da estrutura e avisado quando outro jogador ter/usar/craftar
 * certos itens, preparar pocoes, iniciar raid ou matar certos mobs.
 * As regras ficam no config.yml (vigias.regras.<id_da_estrutura>).
 */
public class Vigias implements Listener {

    public enum Modo { TER, USAR, CRAFTAR, PREPARAR, RAID, MATAR }

    public record Regra(Modo modo, Set<Material> itens, Set<EntityType> entidades, String aviso) {}

    private final DuplasPlugin pl;
    private final Map<String, List<Regra>> regras = new LinkedHashMap<>();
    private final Set<String> ativos = new HashSet<>();
    private final Map<String, Long> ultimo = new HashMap<>();
    private final Map<String, UUID> abridorBrewing = new HashMap<>();

    public Vigias(DuplasPlugin pl) {
        this.pl = pl;
        load();
    }

    // ---------------- config ----------------

    public void load() {
        regras.clear();
        ConfigurationSection sec = pl.getConfig().getConfigurationSection("vigias.regras");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            if (Structures.get(id) == null) {
                pl.getLogger().warning("vigias.regras: estrutura desconhecida '" + id + "'");
                continue;
            }
            List<Regra> lista = new ArrayList<>();
            for (Map<?, ?> m : pl.getConfig().getMapList("vigias.regras." + id)) {
                Regra r = parse(id, m);
                if (r != null) lista.add(r);
            }
            if (!lista.isEmpty()) regras.put(id.toLowerCase(Locale.ROOT), lista);
        }
    }

    private Regra parse(String id, Map<?, ?> m) {
        Object mo = m.get("modo");
        Modo modo;
        try {
            modo = Modo.valueOf(String.valueOf(mo).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            pl.getLogger().warning("vigias.regras." + id + ": modo invalido '" + mo + "'");
            return null;
        }
        Set<Material> itens = resolveItens(listOf(m.get("itens")));
        Set<EntityType> ents = resolveEntidades(listOf(m.get("entidades")));
        boolean precisaItem = modo == Modo.TER || modo == Modo.USAR || modo == Modo.CRAFTAR;
        if (precisaItem && itens.isEmpty()) {
            pl.getLogger().warning("vigias.regras." + id + ": regra " + modo + " sem itens validos");
            return null;
        }
        if (modo == Modo.MATAR && ents.isEmpty()) {
            pl.getLogger().warning("vigias.regras." + id + ": regra MATAR sem entidades validas");
            return null;
        }
        Object av = m.get("aviso");
        String aviso = av != null ? String.valueOf(av) : "acionou uma regra de vigia";
        return new Regra(modo, itens, ents, aviso);
    }

    private static List<?> listOf(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private Set<Material> resolveItens(List<?> nomes) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (Object o : nomes) {
            String n = String.valueOf(o).trim().toUpperCase(Locale.ROOT);
            if (n.isEmpty()) continue;
            boolean achou = false;
            if (n.contains("*")) { // curinga: NETHERITE_*, *SHULKER_BOX ...
                String regex = "\\Q" + n.replace("*", "\\E.*\\Q") + "\\E";
                for (Material m : Material.values()) {
                    if (!m.isLegacy() && m.name().matches(regex)) {
                        out.add(m);
                        achou = true;
                    }
                }
            } else {
                Material m = Material.getMaterial(n);
                if (m != null && !m.isLegacy()) {
                    out.add(m);
                    achou = true;
                }
            }
            if (!achou) pl.getLogger().warning("vigias.regras: item desconhecido '" + n + "'");
        }
        return out;
    }

    private Set<EntityType> resolveEntidades(List<?> nomes) {
        Set<EntityType> out = new HashSet<>();
        for (Object o : nomes) {
            String n = String.valueOf(o).trim().toUpperCase(Locale.ROOT);
            if (n.isEmpty()) continue;
            try {
                out.add(EntityType.valueOf(n));
            } catch (IllegalArgumentException ex) {
                pl.getLogger().warning("vigias.regras: entidade desconhecida '" + n + "'");
            }
        }
        return out;
    }

    // ---------------- varredura (TER / USAR) ----------------

    private boolean ignorado(Player p) {
        return p.hasPermission("duplas.ignorar") || p.getGameMode() == GameMode.SPECTATOR;
    }

    private static void add(Set<Material> s, ItemStack it) {
        if (it != null && !it.getType().isAir()) s.add(it.getType());
    }

    private static Set<Material> materiais(Player p) {
        Set<Material> s = EnumSet.noneOf(Material.class);
        for (ItemStack it : p.getInventory().getContents()) add(s, it);
        add(s, p.getItemOnCursor());
        return s;
    }

    private static Set<Material> equipados(Player p) {
        Set<Material> s = EnumSet.noneOf(Material.class);
        PlayerInventory inv = p.getInventory();
        add(s, inv.getItemInMainHand());
        add(s, inv.getItemInOffHand());
        for (ItemStack it : inv.getArmorContents()) add(s, it);
        return s;
    }

    private static Material primeiro(Set<Material> alvo, Set<Material> tem) {
        for (Material m : alvo) if (tem.contains(m)) return m;
        return null;
    }

    private static String bonito(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public void tick() {
        if (regras.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                varre(p);
            } catch (Exception ex) {
                pl.getLogger().warning("Erro nos vigias (" + p.getName() + "): " + ex);
            }
        }
    }

    private void varre(Player p) {
        UUID id = p.getUniqueId();
        boolean ign = ignorado(p);
        Set<Material> tem = null, usa = null;
        for (Map.Entry<String, List<Regra>> e : regras.entrySet()) {
            Dupla owner = pl.ownerOfStructure(e.getKey());
            Structures.Def def = Structures.get(e.getKey());
            boolean vigia = !ign && owner != null && def != null && !pl.pertence(id, owner);
            List<Regra> lista = e.getValue();
            for (int i = 0; i < lista.size(); i++) {
                Regra r = lista.get(i);
                if (r.modo() != Modo.TER && r.modo() != Modo.USAR) continue;
                String key = id + ":" + e.getKey() + ":" + i;
                Material achado = null;
                if (vigia) {
                    if (r.modo() == Modo.TER) {
                        if (tem == null) tem = materiais(p);
                        achado = primeiro(r.itens(), tem);
                    } else {
                        if (usa == null) usa = equipados(p);
                        achado = primeiro(r.itens(), usa);
                    }
                }
                if (achado == null) {
                    ativos.remove(key);
                    continue;
                }
                if (ativos.contains(key)) continue; // ja avisado enquanto continuar com o item
                if (disparar(p, owner, def, key, r.aviso(), bonito(achado))) ativos.add(key);
            }
        }
    }

    // ---------------- eventos (CRAFTAR / PREPARAR / RAID / MATAR) ----------------

    private void evento(Player p, Modo modo, Material mat, EntityType et) {
        if (p == null || ignorado(p)) return;
        UUID id = p.getUniqueId();
        for (Map.Entry<String, List<Regra>> e : regras.entrySet()) {
            Dupla owner = pl.ownerOfStructure(e.getKey());
            Structures.Def def = Structures.get(e.getKey());
            if (owner == null || def == null || pl.pertence(id, owner)) continue;
            List<Regra> lista = e.getValue();
            for (int i = 0; i < lista.size(); i++) {
                Regra r = lista.get(i);
                if (r.modo() != modo) continue;
                String detalhe = null;
                if (modo == Modo.CRAFTAR) {
                    if (mat == null || !r.itens().contains(mat)) continue;
                    detalhe = bonito(mat);
                } else if (modo == Modo.MATAR) {
                    if (et == null || !r.entidades().contains(et)) continue;
                    detalhe = et.name().toLowerCase(Locale.ROOT).replace('_', ' ');
                }
                disparar(p, owner, def, id + ":" + e.getKey() + ":" + i, r.aviso(), detalhe);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        evento(p, Modo.CRAFTAR, e.getRecipe().getResult().getType(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmith(SmithItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack r = e.getCurrentItem();
        if (r == null || r.getType().isAir()) return;
        evento(p, Modo.CRAFTAR, r.getType(), null);
    }

    private static String chaveBloco(Location l) {
        return l.getWorld().getName() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getType() != InventoryType.BREWING) return;
        Location l = e.getInventory().getLocation();
        if (l == null || l.getWorld() == null) return;
        abridorBrewing.put(chaveBloco(l), e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent e) {
        UUID u = abridorBrewing.get(chaveBloco(e.getBlock().getLocation()));
        if (u == null) return;
        evento(Bukkit.getPlayer(u), Modo.PREPARAR, null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRaid(RaidTriggerEvent e) {
        evento(e.getPlayer(), Modo.RAID, null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null) return;
        evento(k, Modo.MATAR, null, e.getEntityType());
    }

    // ---------------- aviso ----------------

    private boolean disparar(Player p, Dupla owner, Structures.Def def, String key, String aviso, String detalhe) {
        long now = System.currentTimeMillis();
        long cd = pl.getConfig().getLong("vigias.cooldown-segundos", 20) * 1000L;
        Long last = ultimo.get(key);
        if (last != null && now - last < cd) return false;
        ultimo.put(key, now);

        Alert a = new Alert();
        a.note = aviso + (detalhe == null ? "" : " (" + detalhe + ")");
        a.intruder = p.getName();
        Dupla mine = pl.duplaOf(p.getUniqueId());
        a.intruderDupla = mine != null ? mine.name : "sem dupla";
        a.structure = def.display();
        Location l = p.getLocation();
        a.world = l.getWorld().getName();
        a.x = l.getBlockX();
        a.y = l.getBlockY();
        a.z = l.getBlockZ();
        a.enter = now;
        a.exit = now;
        pl.addAlert(owner, a);
        for (String line : a.lines()) pl.notifyDupla(owner, line);
        return true;
    }
}
