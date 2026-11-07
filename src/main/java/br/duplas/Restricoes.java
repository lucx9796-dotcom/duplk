package br.duplas;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Restricoes aplicadas a todo RENEGADO:
 * sem armadura de netherite, sem planar com elytra, totem nao funciona,
 * sem pocoes (beber, arremessar, receber efeitos) e clava (mace) nao causa dano.
 */
public class Restricoes implements Listener {

    private final DuplasPlugin pl;

    public Restricoes(DuplasPlugin pl) {
        this.pl = pl;
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private boolean ren(Entity e) {
        return e instanceof Player p && pl.renegados.contains(p.getUniqueId());
    }

    private static boolean isNetherite(ItemStack item) {
        if (item == null) return false;
        Material m = item.getType();
        return m == Material.NETHERITE_HELMET
                || m == Material.NETHERITE_CHESTPLATE
                || m == Material.NETHERITE_LEGGINGS
                || m == Material.NETHERITE_BOOTS;
    }

    private static boolean isPotionItem(ItemStack item) {
        if (item == null) return false;
        Material m = item.getType();
        return m == Material.POTION || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION;
    }

    private void enforceArmor(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] armor = inv.getArmorContents();
        List<ItemStack> removed = new ArrayList<>();
        for (int i = 0; i < armor.length; i++) {
            if (isNetherite(armor[i])) {
                removed.add(armor[i]);
                armor[i] = null;
            }
        }
        if (removed.isEmpty()) return;
        inv.setArmorContents(armor);
        for (ItemStack it : removed) {
            HashMap<Integer, ItemStack> left = inv.addItem(it);
            for (ItemStack l : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), l);
            }
        }
    }

    /** Aplica as restricoes imediatamente (chamado quando o jogador vira renegado). */
    public void apply(Player p) {
        for (PotionEffect effect : new ArrayList<>(p.getActivePotionEffects())) {
            p.removePotionEffect(effect.getType());
        }
        if (p.isGliding()) p.setGliding(false);
        enforceArmor(p);
    }

    /** Reforco periodico: tira netherite e corta o planar. */
    public void tick() {
        for (UUID id : pl.renegados) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            enforceArmor(p);
            if (p.isGliding()) p.setGliding(false);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (ren(e.getPlayer())) apply(e.getPlayer());
    }

    // ------------------------------------------------------------------
    // Armadura de netherite
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !ren(p)) return;
        PlayerInventory inv = p.getInventory();
        boolean armorSlot = e.getSlotType() == InventoryType.SlotType.ARMOR
                && e.getClickedInventory() == inv;

        if (armorSlot) {
            if (isNetherite(e.getCursor())) {
                e.setCancelled(true);
                return;
            }
            if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0
                    && isNetherite(inv.getItem(e.getHotbarButton()))) {
                e.setCancelled(true);
                return;
            }
            if (e.getClick() == ClickType.SWAP_OFFHAND && isNetherite(inv.getItemInOffHand())) {
                e.setCancelled(true);
                return;
            }
        }

        // Shift+clique para equipar
        if (e.isShiftClick() && !armorSlot && e.getClickedInventory() == inv
                && isNetherite(e.getCurrentItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !ren(p)) return;
        if (!isNetherite(e.getOldCursor())) return;
        for (int raw : e.getRawSlots()) {
            if (e.getView().getSlotType(raw) == InventoryType.SlotType.ARMOR) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onDispenseArmor(BlockDispenseArmorEvent e) {
        if (ren(e.getTargetEntity()) && isNetherite(e.getItem())) {
            e.setCancelled(true);
        }
    }

    // Botao direito com armadura / pocao na mao
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!ren(e.getPlayer())) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (isNetherite(item) || isPotionItem(item)) {
            e.setUseItemInHand(Event.Result.DENY);
        }
    }

    // ------------------------------------------------------------------
    // Elytra (equipa normalmente, mas nao consegue planar)
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (ren(e.getEntity()) && e.isGliding()) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Totem
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent e) {
        if (ren(e.getEntity())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------
    // Pocoes
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (ren(e.getPlayer()) && e.getItem().getType() == Material.POTION) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPotionLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof ThrownPotion potion
                && potion.getShooter() instanceof Player p && ren(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        for (var entity : e.getAffectedEntities()) {
            if (ren(entity)) e.setIntensity(entity, 0);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent e) {
        if (!ren(e.getEntity())) return;
        if (e.getAction() != EntityPotionEffectEvent.Action.ADDED
                && e.getAction() != EntityPotionEffectEvent.Action.CHANGED) return;
        switch (e.getCause()) {
            case POTION_DRINK, POTION_SPLASH, AREA_EFFECT_CLOUD, ARROW, ATTACK -> e.setCancelled(true);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // Clava (Mace)
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onMace(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && ren(p)
                && p.getInventory().getItemInMainHand().getType() == Material.MACE) {
            e.setCancelled(true);
        }
    }
}
