package dev.major.treasure;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class MajorTreasurePlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    public static final String HOLO_TAG = "majortreasure_holo";

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Random random = new Random();

    private NamespacedKey coinKey;
    private File dataFile;
    private YamlConfiguration data;
    private LootManager loot;
    private BukkitTask task;
    private TextDisplay hologram;
    private String lastHoloText = "";
    private boolean ecoWarned = false;

    private long nextRefresh;
    private int cx, cz, surfaceY, radius, depth;

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        coinKey = new NamespacedKey(this, "coin");
        loadData();

        World w = world();
        if (w == null) {
            getLogger().severe("Мир '" + getConfig().getString("world", "world") + "' не найден! Проверь config.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        loadSettings(w);
        loot = new LootManager(this);
        loot.reload();

        getServer().getPluginManager().registerEvents(new TreasureListener(this), this);
        PluginCommand cmd = getCommand("majortreasure");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        forceLoad(w);

        if (!data.getBoolean("built", false) && getConfig().getBoolean("structure.build-on-start", true)) {
            buildStructure();
        }

        nextRefresh = data.getLong("next-refresh", 0L);
        task = getServer().getScheduler().runTaskTimer(this, this::tick, 40L, 20L);
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (hologram != null && hologram.isValid()) hologram.remove();
    }

    private void loadSettings(World w) {
        FileConfiguration c = getConfig();
        cx = c.getInt("center.x", 0);
        cz = c.getInt("center.z", 0);
        radius = Math.max(6, c.getInt("structure.radius", 14));
        depth = Math.max(2, c.getInt("structure.depth", 6));
        if (c.isInt("center.y")) {
            surfaceY = c.getInt("center.y");
        } else if (data.contains("surface-y")) {
            surfaceY = data.getInt("surface-y");
        } else {
            surfaceY = w.getHighestBlockYAt(cx, cz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            data.set("surface-y", surfaceY);
            saveData();
        }
    }

    private void forceLoad(World w) {
        if (!getConfig().getBoolean("structure.forceload-chunks", true)) return;
        int r = radius + 4;
        for (int x = (cx - r) >> 4; x <= (cx + r) >> 4; x++) {
            for (int z = (cz - r) >> 4; z <= (cz + r) >> 4; z++) {
                w.setChunkForceLoaded(x, z, true);
            }
        }
    }

    // ------------------------------------------------------------ data

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить data.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ helpers

    public World world() {
        return Bukkit.getWorld(getConfig().getString("world", "world"));
    }

    public Location shulkerLoc() {
        return new Location(world(), cx, surfaceY - depth + 1, cz);
    }

    public int centerX() { return cx; }
    public int centerZ() { return cz; }
    public int surfaceY() { return surfaceY; }
    public int radius() { return radius; }
    public int depth() { return depth; }
    public NamespacedKey coinKey() { return coinKey; }
    public MiniMessage mm() { return mm; }
    public Random rnd() { return random; }

    public boolean inZone(Location l) {
        World w = world();
        if (w == null || l.getWorld() == null || !l.getWorld().equals(w)) return false;
        double dx = l.getBlockX() - cx;
        double dz = l.getBlockZ() - cz;
        double r = radius + 4.0;
        if (dx * dx + dz * dz > r * r) return false;
        return l.getBlockY() >= surfaceY - depth - 4 && l.getBlockY() <= surfaceY + 16;
    }

    public Economy economy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    private String msg(String key) {
        return getConfig().getString("messages." + key, key);
    }

    private static String fmt(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
    }

    // ------------------------------------------------------------ main loop

    private void tick() {
        World w = world();
        if (w == null) return;

        long now = System.currentTimeMillis();
        if (now >= nextRefresh) refresh(true);
        if (!w.isChunkLoaded(cx >> 4, cz >> 4)) return;

        Location sl = shulkerLoc();
        if (sl.getBlock().getType() != Material.YELLOW_SHULKER_BOX) placeLoot();

        updateHologram(w, sl);
        effects(w, sl);
        dropCoins(w, sl);
    }

    // ------------------------------------------------------------ refresh / loot

    public void refresh(boolean announce) {
        placeLoot();
        scheduleNext();

        World w = world();
        if (w == null) return;
        Location sl = shulkerLoc();
        Location c = sl.clone().add(0.5, 0.5, 0.5);

        w.strikeLightningEffect(c);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1);
        w.spawnParticle(Particle.FIREWORK, c, 150, 2, 2, 2, 0.15);
        w.playSound(c, Sound.BLOCK_BEACON_ACTIVATE, 4f, 1f);

        if (announce) {
            Component m = mm.deserialize(msg("refresh-broadcast")
                    .replace("{x}", String.valueOf(cx))
                    .replace("{y}", String.valueOf(sl.getBlockY()))
                    .replace("{z}", String.valueOf(cz)));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(m);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
        }
    }

    private void scheduleNext() {
        int min = Math.max(1, getConfig().getInt("refresh.min-minutes", 30));
        int max = Math.max(min, getConfig().getInt("refresh.max-minutes", 60));
        long sec = min * 60L + (long) (random.nextDouble() * ((max - min) * 60L));
        nextRefresh = System.currentTimeMillis() + sec * 1000L;
        data.set("next-refresh", nextRefresh);
        saveData();
    }

    public void placeLoot() {
        World w = world();
        if (w == null) return;
        Block b = shulkerLoc().getBlock();

        if (b.getType() == Material.YELLOW_SHULKER_BOX) {
            ShulkerBox live = (ShulkerBox) b.getState();
            for (HumanEntity h : new ArrayList<>(live.getInventory().getViewers())) h.closeInventory();
        } else {
            b.setType(Material.YELLOW_SHULKER_BOX, false);
        }

        ShulkerBox box = (ShulkerBox) b.getState();
        Inventory inv = box.getSnapshotInventory();
        inv.clear();
        loot.fill(inv);
        box.customName(mm.deserialize(msg("shulker-title")));
        box.update(true, false);
    }

    public void buildStructure() {
        World w = world();
        if (w == null) return;
        new StructureBuilder(this).build(w);
        data.set("built", true);
        saveData();
        placeLoot();
    }

    // ------------------------------------------------------------ visuals

    private void updateHologram(World w, Location sl) {
        Location hl = sl.clone().add(0.5, 2.3, 0.5);

        if (hologram == null || !hologram.isValid()) {
            for (Entity e : w.getNearbyEntities(hl, 3, 6, 3)) {
                if (e instanceof TextDisplay && e.getScoreboardTags().contains(HOLO_TAG)) e.remove();
            }
            hologram = w.spawn(hl, TextDisplay.class, td -> {
                td.addScoreboardTag(HOLO_TAG);
                td.setPersistent(false);
                td.setBillboard(Display.Billboard.CENTER);
                td.setAlignment(TextDisplay.TextAlignment.CENTER);
                td.setDefaultBackground(false);
                td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                td.setShadowed(true);
                td.setViewRange(2.0f);
                td.setTransformation(new Transformation(
                        new Vector3f(), new AxisAngle4f(),
                        new Vector3f(1.6f, 1.6f, 1.6f), new AxisAngle4f()));
            });
            lastHoloText = "";
        }

        String text = msg("holo-title") + "\n"
                + msg("holo-timer").replace("{time}", fmt(nextRefresh - System.currentTimeMillis()));
        if (!text.equals(lastHoloText)) {
            hologram.text(mm.deserialize(text));
            lastHoloText = text;
        }
    }

    private void effects(World w, Location sl) {
        double bx = sl.getX() + 0.5, bz = sl.getZ() + 0.5, by = sl.getY();
        for (int i = 1; i <= 24; i++) {
            w.spawnParticle(Particle.END_ROD, bx, by + i, bz, 1, 0.08, 0.0, 0.08, 0.0, null, true);
        }
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(255, 200, 40), 1.8f);
        for (int i = 0; i < 8; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = 0.6 + random.nextDouble() * 2.4;
            w.spawnParticle(Particle.DUST, bx + Math.cos(a) * r, by + 0.3 + random.nextDouble() * 2.5,
                    bz + Math.sin(a) * r, 1, 0, 0, 0, 0, dust, true);
        }
    }

    // ------------------------------------------------------------ coins

    private void dropCoins(World w, Location sl) {
        FileConfiguration c = getConfig();
        if (!c.getBoolean("coins.enabled", true)) return;

        if (economy() == null) {
            if (!ecoWarned) {
                getLogger().warning("Vault-экономика не найдена - монеты не падают. Установи плагин экономики (например EssentialsX).");
                ecoWarned = true;
            }
            return;
        }

        double ar = c.getDouble("coins.active-radius", 64);
        boolean near = false;
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(sl) <= ar * ar) { near = true; break; }
        }
        if (!near) return;

        int count = Math.max(1, c.getInt("coins.per-second", 1));
        double spawnRadius = c.getDouble("coins.spawn-radius", 6);
        double height = c.getDouble("coins.spawn-height", 12);
        long life = Math.max(5, c.getInt("coins.lifetime-seconds", 60)) * 20L;
        double value = c.getDouble("coins.value", 0.5);

        for (int i = 0; i < count; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(random.nextDouble()) * spawnRadius;
            Location l = sl.clone().add(0.5 + Math.cos(a) * r, height, 0.5 + Math.sin(a) * r);

            Item item = w.dropItem(l, coinStack(value));
            item.setVelocity(new Vector(0, 0, 0));
            item.setPickupDelay(10);
            item.setGlowing(true);
            item.setCanMobPickup(false);
            w.spawnParticle(Particle.WAX_ON, l, 3, 0.2, 0.2, 0.2, 0);

            getServer().getScheduler().runTaskLater(this, () -> {
                if (item.isValid()) item.remove();
            }, life);
        }
    }

    private ItemStack coinStack(double value) {
        ItemStack s = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta m = s.getItemMeta();
        m.displayName(mm.deserialize("<!italic><gold>Монета Мажора"));
        m.getPersistentDataContainer().set(coinKey, PersistentDataType.DOUBLE, value);
        s.setItemMeta(m);
        return s;
    }

    // ------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "time" : a[0].toLowerCase(Locale.ROOT);

        if (sub.equals("time")) {
            s.sendMessage(mm.deserialize(msg("next").replace("{time}", fmt(nextRefresh - System.currentTimeMillis()))));
            return true;
        }
        if (!s.hasPermission("majortreasure.admin")) {
            s.sendMessage(mm.deserialize(msg("no-permission")));
            return true;
        }
        switch (sub) {
            case "refresh" -> { refresh(true); s.sendMessage(mm.deserialize(msg("ok"))); }
            case "build" -> { buildStructure(); s.sendMessage(mm.deserialize(msg("ok"))); }
            case "reload" -> {
                reloadConfig();
                World w = world();
                if (w != null) { loadSettings(w); forceLoad(w); }
                loot.reload();
                lastHoloText = "";
                s.sendMessage(mm.deserialize(msg("ok")));
            }
            default -> s.sendMessage(mm.deserialize(msg("usage")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.add("time");
            if (s.hasPermission("majortreasure.admin")) { out.add("refresh"); out.add("build"); out.add("reload"); }
            out.removeIf(x -> !x.startsWith(a[0].toLowerCase(Locale.ROOT)));
        }
        return out;
    }
}
