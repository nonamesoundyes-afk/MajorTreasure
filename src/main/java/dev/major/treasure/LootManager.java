package dev.major.treasure;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LootManager {

    private record Entry(Material material, int min, int max, int weight, String name,
                         Map<Enchantment, Integer> enchants) {}

    private final MajorTreasurePlugin plugin;
    private List<Entry> guaranteed = new ArrayList<>();
    private List<Entry> pool = new ArrayList<>();
    private int rollsMin = 7, rollsMax = 11;

    public LootManager(MajorTreasurePlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        var c = plugin.getConfig();
        rollsMin = Math.max(0, c.getInt("loot.rolls-min", 7));
        rollsMax = Math.max(rollsMin, c.getInt("loot.rolls-max", 11));
        guaranteed = parse(c.getMapList("loot.guaranteed"));
        pool = parse(c.getMapList("loot.pool"));
    }

    private List<Entry> parse(List<Map<?, ?>> raw) {
        List<Entry> out = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
            if (mat == null || !mat.isItem()) {
                plugin.getLogger().warning("Лут: неизвестный предмет " + m.get("material"));
                continue;
            }
            int min = toInt(m.get("min"), 1);
            int max = Math.max(min, toInt(m.get("max"), min));
            int weight = Math.max(1, toInt(m.get("weight"), 1));
            String name = m.get("name") == null ? null : String.valueOf(m.get("name"));

            Map<Enchantment, Integer> ench = new HashMap<>();
            if (m.get("enchants") instanceof Map<?, ?> em) {
                for (Map.Entry<?, ?> en : em.entrySet()) {
                    String key = String.valueOf(en.getKey()).toLowerCase(Locale.ROOT);
                    Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
                    if (e == null) {
                        plugin.getLogger().warning("Лут: неизвестное зачарование " + key);
                        continue;
                    }
                    ench.put(e, toInt(en.getValue(), 1));
                }
            }
            out.add(new Entry(mat, min, max, weight, name, ench));
        }
        return out;
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return def;
        try { return Integer.parseInt(String.valueOf(o)); } catch (NumberFormatException e) { return def; }
    }

    public void fill(Inventory inv) {
        List<ItemStack> items = new ArrayList<>();
        for (Entry e : guaranteed) items.add(create(e));

        int rolls = rollsMin + plugin.rnd().nextInt(rollsMax - rollsMin + 1);
        List<Entry> bag = new ArrayList<>(pool);
        for (int i = 0; i < rolls && !bag.isEmpty(); i++) {
            int total = 0;
            for (Entry e : bag) total += e.weight();
            int pick = plugin.rnd().nextInt(total);
            Entry chosen = bag.get(bag.size() - 1);
            for (Entry e : bag) {
                pick -= e.weight();
                if (pick < 0) { chosen = e; break; }
            }
            bag.remove(chosen);
            items.add(create(chosen));
        }

        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots, plugin.rnd());
        for (int i = 0; i < items.size() && i < slots.size(); i++) {
            inv.setItem(slots.get(i), items.get(i));
        }
    }

    private ItemStack create(Entry e) {
        int amount = e.min() + plugin.rnd().nextInt(e.max() - e.min() + 1);
        amount = Math.max(1, Math.min(amount, e.material().getMaxStackSize()));
        ItemStack it = new ItemStack(e.material(), amount);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;

        if (e.name() != null) meta.displayName(plugin.mm().deserialize("<!italic>" + e.name()));
        for (Map.Entry<Enchantment, Integer> en : e.enchants().entrySet()) {
            if (meta instanceof EnchantmentStorageMeta esm) {
                esm.addStoredEnchant(en.getKey(), en.getValue(), true);
            } else {
                meta.addEnchant(en.getKey(), en.getValue(), true);
            }
        }
        it.setItemMeta(meta);
        return it;
    }
}
