package br.duplas;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Confere se cada membro de uma dupla esta com a PROPRIA chave no inventario.
 * Para nao renegar ninguem a toa: so vale para quem ja recebeu a chave (/dupla chave dar),
 * ha carencia ao entrar/renascer, tolerancia (contagem) e uma reconferencia antes de renegar.
 * Chave caida no chao (recuperavel) nao conta como "sem chave".
 */
public class Chaves implements Listener {

    /** Inventarios abertos que contam como "estar com o jogador" (grade de craft etc.). */
    private static final Set<String> GRADES = Set.of("CRAFTING", "WORKBENCH", "ANVIL", "ENCHANTING",
            "GRINDSTONE", "LOOM", "CARTOGRAPHY", "STONECUTTER", "SMITHING", "MERCHANT");

    private final DuplasPlugin pl;
    private final Map<UUID, Long> semChave = new HashMap<>();
    private final Map<UUID, Long> carencia = new HashMap<>();

    public Chaves(DuplasPlugin pl) {
        this.pl = pl;
    }

    private boolean ehDono(ItemStack it, UUID dono) {
        return dono.equals(pl.keyOwnerOf(it));
    }

    /** O jogador esta carregando a chave de {@code dono}? (inventario, mao, armadura, cursor, grade de craft) */
    public boolean possui(Player p, UUID dono) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (ehDono(it, dono)) return true;
        }
        if (ehDono(p.getItemOnCursor(), dono)) return true;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (GRADES.contains(top.getType().name())) {
            for (ItemStack it : top.getContents()) {
                if (ehDono(it, dono)) return true;
            }
        }
        if (pl.getConfig().getBoolean("chave.aceitar-enderchest", false)) {
            for (ItemStack it : p.getEnderChest().getContents()) {
                if (ehDono(it, dono)) return true;
            }
        }
        return false;
    }

    private boolean emTerra(UUID dono) {
        Set<UUID> s = pl.droppedKeys.get(dono);
        return s != null && !s.isEmpty();
    }

    public void tick() {
        if (!pl.getConfig().getBoolean("chave.renegado-se-sem-chave", true)) return;
        long now = System.currentTimeMillis();
        long tol = pl.getConfig().getInt("chave.tolerancia-segundos", 30) * 1000L;
        for (Dupla d : pl.duplas.values()) {
            if (d.keyTemplate == null) continue;
            for (UUID u : d.keyGiven) {
                if (pl.renegados.contains(u)) {
                    semChave.remove(u);
                    continue;
                }
                Player p = Bukkit.getPlayer(u);
                if (p == null || !p.isOnline() || p.isDead() || now < carencia.getOrDefault(u, 0L)) {
                    semChave.remove(u);
                    continue;
                }
                if (emTerra(u) || possui(p, u)) {
                    if (semChave.remove(u) != null) p.sendMessage("§aChave recuperada. Você não será renegado.");
                    continue;
                }
                Long desde = semChave.get(u);
                if (desde == null) {
                    semChave.put(u, now);
                    p.sendMessage("§c§l⚠ §cVocê está sem a sua chave! Recupere-a em §f" + (tol / 1000)
                            + "s §cou será renegado.");
                    continue;
                }
                if (now - desde < tol) continue;
                // reconferencia final antes de renegar
                if (emTerra(u) || possui(p, u)) {
                    semChave.remove(u);
                    continue;
                }
                semChave.remove(u);
                pl.setRenegado(u, true, "ficou sem a chave");
            }
        }
    }

    private void darCarencia(Player p, String chave) {
        long seg = pl.getConfig().getInt(chave, 15);
        carencia.put(p.getUniqueId(), System.currentTimeMillis() + seg * 1000L);
        semChave.remove(p.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        darCarencia(e.getPlayer(), "chave.carencia-ao-entrar-segundos");
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        darCarencia(e.getPlayer(), "chave.carencia-ao-renascer-segundos");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        semChave.remove(e.getPlayer().getUniqueId());
    }
}
