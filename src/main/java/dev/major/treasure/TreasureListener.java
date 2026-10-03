package dev.major.treasure;

import com.destroystokyo.paper.event.player.PlayerAttemptPickupItemEvent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;

public final class TreasureListener implements Listener {

    private final MajorTreasurePlugin plugin;

    public TreasureListener(MajorTreasurePlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------- coins

    private Double coinValue(ItemStack st) {
        if (st == null || !st.hasItemMeta()) return null;
        return st.getItemMeta().getPersistentDataContainer().get(plugin.coinKey(), PersistentDataType.DOUBLE);
    }

    @EventHandler
    public void onPickup(PlayerAttemptPickupItemEvent e) {
        Item item = e.getItem();
        ItemStack st = item.getItemStack();
        Double v = coinValue(st);
        if (v == null) return;

        e.setCancelled(true);
        Economy eco = plugin.economy();
        if (eco == null) return;

        double total = v * st.getAmount();
        item.remove();
        Player p = e.getPlayer();
        eco.depositPlayer(p, total);

        String amount = String.format(Locale.US, "%.1f", total);
        String m = plugin.getConfig().getString("messages.coin", "<gold>+{amount} монет");
        p.sendActionBar(plugin.mm().deserialize(m.replace("{amount}", amount)));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
    }

    @EventHandler
    public void onHopperPickup(InventoryPickupItemEvent e) {
        if (coinValue(e.getItem().getItemStack()) != null) e.setCancelled(true);
    }

    // ---------------------------------------------------------- shulker protection

    private boolean isTreasure(Inventory inv) {
        if (inv == null) return false;
        Location l = inv.getLocation();
        if (l == null || l.getWorld() == null) return false;
        Location s = plugin.shulkerLoc();
        return l.getWorld().equals(s.getWorld())
                && l.getBlockX() == s.getBlockX()
                && l.getBlockY() == s.getBlockY()
                && l.getBlockZ() == s.getBlockZ();
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isTreasure(top)) return;

        InventoryAction a = e.getAction();
        if (e.getClickedInventory() == top) {
            if (a == InventoryAction.PLACE_ALL || a == InventoryAction.PLACE_ONE
                    || a == InventoryAction.PLACE_SOME || a == InventoryAction.SWAP_WITH_CURSOR
                    || a == InventoryAction.HOTBAR_SWAP || a == InventoryAction.HOTBAR_MOVE_AND_READD) {
                e.setCancelled(true);
            }
        } else if (a == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isTreasure(top)) return;
        for (int slot : e.getRawSlots()) {
            if (slot < top.getSize()) { e.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onMove(InventoryMoveItemEvent e) {
        if (isTreasure(e.getDestination()) || isTreasure(e.getSource())) e.setCancelled(true);
    }

    // ---------------------------------------------------------- zone protection

    private boolean protect() {
        return plugin.getConfig().getBoolean("structure.protect", true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (protect() && plugin.inZone(e.getBlock().getLocation())
                && !e.getPlayer().hasPermission("majortreasure.admin")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (protect() && plugin.inZone(e.getBlock().getLocation())
                && !e.getPlayer().hasPermission("majortreasure.admin")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (protect() && plugin.inZone(e.getBlock().getLocation())
                && !e.getPlayer().hasPermission("majortreasure.admin")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (protect()) e.blockList().removeIf(b -> plugin.inZone(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (protect()) e.blockList().removeIf(b -> plugin.inZone(b.getLocation()));
    }
}
