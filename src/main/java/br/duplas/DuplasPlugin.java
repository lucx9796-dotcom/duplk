package br.duplas;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;

public final class DuplasPlugin extends JavaPlugin {

    public final Map<String, Dupla> duplas = new LinkedHashMap<>();
    public final Set<UUID> renegados = new HashSet<>();
    /** dono da chave -> entidades (itens no chao) com a chave dele; chave no chao ainda e recuperavel. */
    public final Map<UUID, Set<UUID>> droppedKeys = new HashMap<>();
    public NamespacedKey keyOwner;
    public Tracker tracker;
    public Restricoes restricoes;
    public Chaves chaves;
    public Vigias vigias;
    private boolean dirty;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true); // acrescenta no config.yml as opcoes novas
        saveConfig();
        try {
            Alert.zone = ZoneId.of(getConfig().getString("fuso", "America/Sao_Paulo"));
        } catch (Exception ignored) {}
        keyOwner = new NamespacedKey(this, "key_owner");
        loadData();
        tracker = new Tracker(this);
        restricoes = new Restricoes(this);
        chaves = new Chaves(this);
        vigias = new Vigias(this);

        getServer().getPluginManager().registerEvents(new Listeners(this), this);
        getServer().getPluginManager().registerEvents(restricoes, this);
        getServer().getPluginManager().registerEvents(chaves, this);
        getServer().getPluginManager().registerEvents(vigias, this);
        DuplaCommand cmd = new DuplaCommand(this);
        for (String c : new String[]{"dupla", "renegado", "renegar", "desrenegar"}) {
            getCommand(c).setExecutor(cmd);
            getCommand(c).setTabCompleter(cmd);
        }

        getServer().getScheduler().runTaskTimer(this, tracker::tick, 40L, 10L);
        getServer().getScheduler().runTaskTimer(this, restricoes::tick, 20L, 5L);
        getServer().getScheduler().runTaskTimer(this, chaves::tick, 100L, 20L);
        getServer().getScheduler().runTaskTimer(this, vigias::tick, 60L, 20L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (dirty) saveData();
        }, 600L, 600L);

        for (Player p : Bukkit.getOnlinePlayers()) applyLook(p);
    }

    @Override
    public void onDisable() {
        if (tracker != null) tracker.endAll();
        saveData();
    }

    // ---------- consultas ----------

    public Dupla duplaOf(UUID id) {
        for (Dupla d : duplas.values()) if (d.members.contains(id)) return d;
        return null;
    }

    public Dupla ownerOfStructure(String structureId) {
        for (Dupla d : duplas.values()) if (structureId.equals(d.structure)) return d;
        return null;
    }

    public void notifyDupla(Dupla d, String msg) {
        for (UUID u : d.members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(msg);
        }
    }

    /** Membro da dupla e NAO renegado (pode andar na estrutura sem gerar alerta). */
    public boolean pertence(UUID id, Dupla d) {
        return d.members.contains(id) && !renegados.contains(id);
    }

    public void addAlert(Dupla d, Alert a) {
        d.alerts.add(a);
        while (d.alerts.size() > 200) d.alerts.remove(0);
        markDirty();
    }

    /** Dono da chave (se o item for uma chave entregue pelo plugin). */
    public UUID keyOwnerOf(ItemStack st) {
        if (st == null || st.getType().isAir() || !st.hasItemMeta()) return null;
        String s = st.getItemMeta().getPersistentDataContainer().get(keyOwner, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void trackDroppedKey(UUID owner, UUID entity) {
        if (droppedKeys.computeIfAbsent(owner, k -> new HashSet<>()).add(entity)) markDirty();
    }

    public void forgetDroppedKey(UUID owner, UUID entity) {
        Set<UUID> set = droppedKeys.get(owner);
        if (set != null && set.remove(entity)) {
            if (set.isEmpty()) droppedKeys.remove(owner);
            markDirty();
        }
    }

    public String nameOf(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : id.toString().substring(0, 8);
    }

    public void markDirty() {
        dirty = true;
    }

    // ---------- renegados ----------

    private Team renegadoTeam() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = sb.getTeam("renegados");
        if (t == null) t = sb.registerNewTeam("renegados");
        t.color(NamedTextColor.RED);
        return t;
    }

    public void applyLook(OfflinePlayer op) {
        if (op.getName() == null) return;
        Team t = renegadoTeam();
        boolean ren = renegados.contains(op.getUniqueId());
        if (ren) t.addEntry(op.getName());
        else t.removeEntry(op.getName());

        Player p = op.getPlayer();
        if (p != null) {
            if (ren) {
                Component red = Component.text(p.getName(), NamedTextColor.RED);
                p.displayName(red);
                p.playerListName(red);
            } else {
                p.displayName(null);
                p.playerListName(null);
            }
        }
    }

    private static final LegacyComponentSerializer LEG = LegacyComponentSerializer.legacyAmpersand();
    private static final Title.Times TITLE_TIMES =
            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(1));

    public void setRenegado(UUID id, boolean value, String reason) {
        boolean changed = value ? renegados.add(id) : renegados.remove(id);
        if (!changed) return;
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        applyLook(op);
        markDirty();
        String name = nameOf(id);
        Player online = op.getPlayer();
        if (value) {
            String extra = (reason == null || reason.isBlank()) ? "" : " &7(" + reason + ")";
            Bukkit.broadcast(LEG.deserialize("&8[&c!&8] &eO jogador &c" + name + " &efoi renegado!" + extra));
            if (online != null) {
                restricoes.apply(online);
                online.showTitle(Title.title(
                        LEG.deserialize("&c&lVOCÊ FOI RENEGADO!"),
                        LEG.deserialize("&cVocê agora é um renegado."),
                        TITLE_TIMES));
            }
            Dupla d = duplaOf(id);
            if (d != null) notifyDupla(d, "§c" + name + " virou renegado.");
        } else {
            Bukkit.broadcast(LEG.deserialize("&8[&a!&8] &eO jogador &a" + name + " &enão é mais um renegado!"));
            if (online != null) {
                online.showTitle(Title.title(
                        LEG.deserialize("&a&lVOCÊ FOI PERDOADO!"),
                        LEG.deserialize("&eVocê não é mais um renegado."),
                        TITLE_TIMES));
            }
        }
    }

    // ---------- persistencia ----------

    private File dataFile() {
        return new File(getDataFolder(), "data.yml");
    }

    public void loadData() {
        File f = dataFile();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ItemStack legacyKey = y.getItemStack("key"); // versao antiga: chave global
        for (String s : y.getStringList("renegados")) renegados.add(UUID.fromString(s));
        ConfigurationSection dk = y.getConfigurationSection("droppedKeys");
        if (dk != null) {
            for (String o : dk.getKeys(false)) {
                Set<UUID> set = new HashSet<>();
                for (String en : dk.getStringList(o)) set.add(UUID.fromString(en));
                if (!set.isEmpty()) droppedKeys.put(UUID.fromString(o), set);
            }
        }
        ConfigurationSection ds = y.getConfigurationSection("duplas");
        if (ds == null) return;
        for (String n : ds.getKeys(false)) {
            ConfigurationSection c = ds.getConfigurationSection(n);
            if (c == null) continue;
            Dupla d = new Dupla(c.getString("name", n));
            for (String m : c.getStringList("members")) d.members.add(UUID.fromString(m));
            d.structure = c.getString("structure");
            d.keyTemplate = c.getItemStack("key");
            if (d.keyTemplate == null && legacyKey != null) d.keyTemplate = legacyKey.clone();
            for (String m : c.getStringList("keyGiven")) d.keyGiven.add(UUID.fromString(m));
            ConfigurationSection al = c.getConfigurationSection("alerts");
            if (al != null) {
                List<String> ks = new ArrayList<>(al.getKeys(false));
                ks.sort(Comparator.comparingInt(Integer::parseInt));
                for (String k : ks) d.alerts.add(Alert.load(al.getConfigurationSection(k)));
            }
            duplas.put(n.toLowerCase(), d);
        }
    }

    public void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> r = new ArrayList<>();
        for (UUID u : renegados) r.add(u.toString());
        y.set("renegados", r);
        for (Map.Entry<UUID, Set<UUID>> e : droppedKeys.entrySet()) {
            List<String> l = new ArrayList<>();
            for (UUID u : e.getValue()) l.add(u.toString());
            y.set("droppedKeys." + e.getKey(), l);
        }
        for (Map.Entry<String, Dupla> e : duplas.entrySet()) {
            Dupla d = e.getValue();
            ConfigurationSection c = y.createSection("duplas." + e.getKey());
            c.set("name", d.name);
            List<String> ms = new ArrayList<>();
            for (UUID u : d.members) ms.add(u.toString());
            c.set("members", ms);
            c.set("structure", d.structure);
            c.set("key", d.keyTemplate);
            List<String> kg = new ArrayList<>();
            for (UUID u : d.keyGiven) kg.add(u.toString());
            c.set("keyGiven", kg);
            ConfigurationSection al = c.createSection("alerts");
            for (int i = 0; i < d.alerts.size(); i++) d.alerts.get(i).save(al.createSection(String.valueOf(i)));
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile());
            dirty = false;
        } catch (IOException ex) {
            getLogger().severe("Falha ao salvar data.yml: " + ex.getMessage());
        }
    }
}
