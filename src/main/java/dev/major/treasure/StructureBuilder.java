package dev.major.treasure;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Random;

import static org.bukkit.Material.*;

/** Строит метеоритный кратер с золотом вокруг центра. */
public final class StructureBuilder {

    private final MajorTreasurePlugin plugin;
    private int floorY;
    private int depth;
    private int radius;

    public StructureBuilder(MajorTreasurePlugin plugin) {
        this.plugin = plugin;
    }

    private int topAt(double t) {
        return floorY + (int) Math.round(depth * t * t);
    }

    private static void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    public void build(World w) {
        int cx = plugin.centerX();
        int cz = plugin.centerZ();
        int surf = plugin.surfaceY();
        radius = plugin.radius();
        depth = plugin.depth();
        floorY = surf - depth;
        Random r = new Random(w.getSeed() ^ 0x4D414A4F52L);

        // 1) Чаша кратера
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius) continue;
                double t = d / radius;
                int x = cx + dx, z = cz + dz;
                int top = topAt(t);

                for (int y = top + 1; y <= surf + 14; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() != AIR) b.setType(AIR, false);
                }
                for (int y = top; y >= top - 2; y--) set(w, x, y, z, pick(t, r));
                // подпорка, чтобы кратер не висел над пустотой
                for (int y = top - 3; y >= top - 14; y--) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType().isAir() || b.isLiquid() || b.isPassable()) b.setType(BLACKSTONE, false);
                    else break;
                }

                // декор: куски золота и светящиеся блоки
                if (t > 0.15 && t < 0.95) {
                    double p = r.nextDouble();
                    if (p < 0.045) {
                        set(w, x, top + 1, z, r.nextBoolean() ? GOLD_BLOCK : RAW_GOLD_BLOCK);
                        if (r.nextInt(3) == 0) set(w, x, top + 2, z, RAW_GOLD_BLOCK);
                    } else if (p < 0.06) {
                        set(w, x, top + 1, z, GILDED_BLACKSTONE);
                    } else if (p < 0.07) {
                        set(w, x, top + 1, z, SHROOMLIGHT);
                    }
                }
            }
        }

        // 2) Выбросы породы по краю кратера
        int ring = 3;
        for (int dx = -(radius + ring); dx <= radius + ring; dx++) {
            for (int dz = -(radius + ring); dz <= radius + ring; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d <= radius || d > radius + ring) continue;
                double k = 1.0 - (d - radius) / ring;
                if (r.nextDouble() > 0.55 * k + 0.1) continue;
                int x = cx + dx, z = cz + dz;
                int ground = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                int h = 1 + (k > 0.5 && r.nextInt(3) == 0 ? 1 : 0);
                for (int i = 1; i <= h; i++) set(w, x, ground + i, z, rim(r));
            }
        }

        // 3) Пьедестал в центре
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(w, cx + dx, floorY, cz + dz, GOLD_BLOCK);
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) == 2 && Math.abs(dz) == 2) set(w, cx + dx, floorY + 1, cz + dz, SHROOMLIGHT);
            }
        }

        // 4) Четыре золотых столба
        for (int k = 0; k < 4; k++) {
            double ang = Math.toRadians(45 + 90 * k);
            int px = cx + (int) Math.round(Math.cos(ang) * radius * 0.62);
            int pz = cz + (int) Math.round(Math.sin(ang) * radius * 0.62);
            double dd = Math.sqrt((px - cx) * (px - cx) + (pz - cz) * (pz - cz));
            int y0 = topAt(dd / radius) + 1;
            set(w, px, y0, pz, POLISHED_BLACKSTONE_BRICKS);
            set(w, px, y0 + 1, pz, GOLD_BLOCK);
            set(w, px, y0 + 2, pz, GOLD_BLOCK);
            set(w, px, y0 + 3, pz, GOLD_BLOCK);
            set(w, px, y0 + 4, pz, SHROOMLIGHT);
        }
    }

    private Material pick(double t, Random r) {
        int n = r.nextInt(100);
        if (t < 0.25) return n < 70 ? GOLD_BLOCK : RAW_GOLD_BLOCK;
        if (t < 0.5) {
            if (n < 30) return RAW_GOLD_BLOCK;
            if (n < 50) return GOLD_BLOCK;
            if (n < 62) return OCHRE_FROGLIGHT;
            if (n < 80) return GILDED_BLACKSTONE;
            return BLACKSTONE;
        }
        if (t < 0.8) {
            if (n < 30) return BLACKSTONE;
            if (n < 45) return GILDED_BLACKSTONE;
            if (n < 65) return RAW_GOLD_BLOCK;
            if (n < 73) return OCHRE_FROGLIGHT;
            if (n < 88) return SMOOTH_BASALT;
            return DEEPSLATE_GOLD_ORE;
        }
        if (n < 40) return BLACKSTONE;
        if (n < 55) return TUFF;
        if (n < 67) return GILDED_BLACKSTONE;
        if (n < 77) return RAW_GOLD_BLOCK;
        if (n < 87) return SMOOTH_BASALT;
        return DEEPSLATE_GOLD_ORE;
    }

    private Material rim(Random r) {
        int n = r.nextInt(100);
        if (n < 40) return BLACKSTONE;
        if (n < 60) return GILDED_BLACKSTONE;
        if (n < 75) return RAW_GOLD_BLOCK;
        if (n < 90) return TUFF;
        return DEEPSLATE_GOLD_ORE;
    }
}
