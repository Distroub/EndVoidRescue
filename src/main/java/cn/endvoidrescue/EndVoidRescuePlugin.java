package cn.endvoidrescue;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class EndVoidRescuePlugin extends JavaPlugin implements Listener {
    private final Random random = new Random();
    private PendingDropStore store;
    private Set<Material> blacklist;
    private int maxDepth;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new PendingDropStore(getDataFolder());
        reloadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("EndVoidRescue enabled");
    }

    private void reloadSettings() {
        blacklist = EnumSet.noneOf(Material.class);
        for (String name : getConfig().getStringList("blacklist")) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                blacklist.add(material);
            } else {
                getLogger().warning("Unknown blacklist material: " + name);
            }
        }
        maxDepth = Math.max(0, getConfig().getInt("shulker.max-depth", 5));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!isTriggerDeath(player)) {
            return;
        }

        event.setKeepInventory(true);
        event.setKeepLevel(false);
        event.setDroppedExp(0);
        event.getDrops().clear();
        player.setLevel(0);
        player.setExp(0.0f);

        // 先清理玩家背包，再把潜影盒内容交给重生事件处理。
        List<ItemStack> pending = new ArrayList<>();
        processInventory(player.getInventory(), pending);
        if (pending.isEmpty()) {
            store.put(player.getUniqueId(), List.of());
        } else {
            store.put(player.getUniqueId(), mergeIfConfigured(pending));
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        List<ItemStack> pending = store.get(player.getUniqueId());
        if (pending.isEmpty()) {
            return;
        }
        Location respawnLocation = event.getRespawnLocation().clone();
        // 延迟一 tick，确保玩家已经传送到最终重生位置。
        Bukkit.getScheduler().runTaskLater(this, () -> {
            burst(player, respawnLocation, pending);
            store.remove(player.getUniqueId());
        }, 1L);
    }

    private boolean isTriggerDeath(Player player) {
        if (player.getWorld().getEnvironment() != configuredEnvironment()) {
            return false;
        }
        if (player.getLastDamageCause() == null) {
            return false;
        }
        return player.getLastDamageCause().getCause() == configuredCause();
    }

    private World.Environment configuredEnvironment() {
        try {
            String value = getConfig().getString("trigger-world", "THE_END");
            return World.Environment.valueOf(value == null ? "THE_END" : value);
        } catch (IllegalArgumentException exception) {
            return World.Environment.THE_END;
        }
    }

    private org.bukkit.event.entity.EntityDamageEvent.DamageCause configuredCause() {
        try {
            String value = getConfig().getString("trigger-cause", "VOID");
            return org.bukkit.event.entity.EntityDamageEvent.DamageCause.valueOf(value == null ? "VOID" : value);
        } catch (IllegalArgumentException exception) {
            return org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID;
        }
    }

    private void processInventory(Inventory inventory, List<ItemStack> pending) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemStack processed = processItem(item, 0, pending);
            inventory.setItem(slot, processed);
        }
    }

    private ItemStack processItem(ItemStack original, int depth, List<ItemStack> pending) {
        ItemStack item = original.clone();
        if (isBlacklisted(item.getType())) {
            return null;
        }
        if (isShulker(item)) {
            if (!hasContents(item)) {
                return null;
            }
            // 达到上限后，当前潜影盒及其剩余内容全部丢弃。
            if (depth >= maxDepth) {
                return null;
            }
            extractShulkerContents(item, depth + 1, pending);
            return null;
        }
        stripNonCurseEnchantments(item);
        return item;
    }

    private void extractShulkerContents(ItemStack shulkerItem, int depth, List<ItemStack> pending) {
        BlockStateMeta meta = (BlockStateMeta) shulkerItem.getItemMeta();
        if (meta == null || !(meta.getBlockState() instanceof ShulkerBox box)) {
            return;
        }
        // 这里递归处理盒中盒，普通物品直接加入待爆出列表。
        for (ItemStack content : box.getInventory().getContents()) {
            if (content == null || content.getType().isAir()) {
                continue;
            }
            ItemStack processed = processItem(content, depth, pending);
            if (processed != null) {
                pending.add(processed);
            }
        }
    }

    private boolean hasContents(ItemStack item) {
        ItemMeta itemMeta = item.getItemMeta();
        if (!(itemMeta instanceof BlockStateMeta meta) || !(meta.getBlockState() instanceof ShulkerBox box)) {
            return false;
        }
        for (ItemStack content : box.getInventory().getContents()) {
            if (content != null && !content.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    private boolean isShulker(ItemStack item) {
        return item.getType().name().endsWith("SHULKER_BOX");
    }

    private boolean isBlacklisted(Material material) {
        return blacklist.contains(material);
    }

    private void stripNonCurseEnchantments(ItemStack item) {
        if (!getConfig().getBoolean("remove-non-curse-enchant", true)) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        if (meta instanceof EnchantmentStorageMeta storedMeta) {
            for (Enchantment enchantment : new HashSet<>(storedMeta.getStoredEnchants().keySet())) {
                if (!isCurse(enchantment)) {
                    storedMeta.removeStoredEnchant(enchantment);
                }
            }
        }
        for (Enchantment enchantment : new HashSet<>(meta.getEnchants().keySet())) {
            if (!isCurse(enchantment)) {
                meta.removeEnchant(enchantment);
            }
        }
        item.setItemMeta(meta);
    }

    private boolean isCurse(Enchantment enchantment) {
        String name = enchantment.getKey().getKey();
        return name.equals("binding_curse") || name.equals("vanishing_curse");
    }

    private List<ItemStack> mergeIfConfigured(List<ItemStack> items) {
        if (!getConfig().getBoolean("burst.merge-similar", true)) {
            return items;
        }
        // 合并到已有堆叠后，再按最大堆叠数量拆分剩余物品。
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack item : items) {
            int remaining = item.getAmount();
            for (ItemStack existing : merged) {
                if (!existing.isSimilar(item)) {
                    continue;
                }
                int capacity = existing.getMaxStackSize() - existing.getAmount();
                int added = Math.min(capacity, remaining);
                existing.setAmount(existing.getAmount() + added);
                remaining -= added;
                if (remaining == 0) {
                    break;
                }
            }
            while (remaining > 0) {
                ItemStack part = item.clone();
                int amount = Math.min(item.getMaxStackSize(), remaining);
                part.setAmount(amount);
                merged.add(part);
                remaining -= amount;
            }
        }
        return merged;
    }

    private void burst(Player player, Location location, List<ItemStack> items) {
        if (!location.getChunk().isLoaded()) {
            location.getChunk().load();
        }
        double radius = Math.max(0.0, getConfig().getDouble("burst.radius", 2.0));
        int pickupDelay = Math.max(0, getConfig().getInt("burst.pickup-delay-ticks", 10));
        // 使用均匀面积分布，避免物品集中在圆心附近。
        for (ItemStack item : items) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(random.nextDouble()) * radius;
            Location dropLocation = location.clone().add(Math.cos(angle) * distance, 0.25,
                    Math.sin(angle) * distance);
            Item entity = location.getWorld().dropItem(dropLocation, item);
            entity.setPickupDelay(pickupDelay);
            entity.setVelocity(new Vector((random.nextDouble() - 0.5) * 0.15, 0.18,
                    (random.nextDouble() - 0.5) * 0.15));
        }
        playEffects(location);
        getLogger().info("Released " + items.size() + " pending item stack(s) for " + player.getName());
    }

    private void playEffects(Location location) {
        try {
            String particleName = getConfig().getString("burst.particle", "EXPLOSION");
            Particle particle = Particle.valueOf(particleName == null ? "EXPLOSION" : particleName);
            location.getWorld().spawnParticle(particle, location, 24, 0.8, 0.5, 0.8, 0.05);
        } catch (IllegalArgumentException exception) {
            getLogger().warning("Unknown particle configured");
        }
        try {
            String soundName = getConfig().getString("burst.sound", "ENTITY_GENERIC_EXPLODE");
            Sound sound = Sound.valueOf(soundName == null ? "ENTITY_GENERIC_EXPLODE" : soundName);
            location.getWorld().playSound(location, sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException exception) {
            getLogger().warning("Unknown sound configured");
        }
    }
}
