package cn.endvoidrescue;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
import java.util.UUID;

public final class EndVoidRescuePlugin extends JavaPlugin implements Listener {
    private final Random random = new Random();
    private PendingDropStore store;
    private Set<Material> blacklist;
    private boolean loggedBurstRadiusClamp;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new PendingDropStore(getDataFolder());
        reloadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("EndVoidRescue enabled");
    }

    @Override
    public void onDisable() {
        store.save();
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
        // 配置可能从合法值改成超限值，允许下次 burst 再警告一次。
        loggedBurstRadiusClamp = false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!isTriggerDeath(player)) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        // 先清理玩家背包，再把潜影盒内容交给重生事件处理。
        List<ItemStack> pending = new ArrayList<>();
        processInventory(player.getInventory(), pending);
        if (pending.isEmpty()) {
            // 死亡时背包为空（或全为黑名单/空潜影盒），清理可能残留的记录。
            store.remove(player.getUniqueId());
        } else {
            store.put(player.getUniqueId(), mergeIfConfigured(pending));
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        List<ItemStack> pending = store.get(playerId);
        if (pending.isEmpty()) {
            // 死亡时可能留下空记录（或历史版本留下的残留），这里同步清理，不提前 return。
            // 【待确认 3】实测（YamlConfiguration 探针）：put(uuid, List.of()) 在文件里原本没有该玩家时
            // 不会写出 players.<uuid> 条目；真正的残留形态是 remove 后留下的 "players: {}"，
            // 以及旧版可能写过的 "players.<uuid>: {}"（get() 会读成空列表）。
            // 因此 PendingDropStore 里同时做了「清空后连空 players 段一起删」。
            store.remove(playerId);
            return;
        }
        Location respawnLocation = event.getRespawnLocation().clone();
        // 延迟 1 tick，确保玩家已经传送到最终重生位置。
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                burst(player, respawnLocation, pending);
            } finally {
                // burst 即使抛异常也必须清理记录，否则记录会永久残留在 pending-drops.yml。
                // 【待确认 5】代价：burst 真正失败时那批物品会永久丢失（只有日志），
                // 这是「不要残留」目标下的必然取舍，当前没有重试/兜底。
                // 若需要兜底（保留到下次登录再发、或改投到重生点所在世界/主城），需另行设计。
                store.remove(playerId);
            }
        }, 1L);
    }

    private boolean isTriggerDeath(Player player) {
        if (player.getWorld().getEnvironment() != configuredEnvironment() || player.getLastDamageCause() == null) {
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
        for (int slot = 0; slot < inventory.getSize(); ++slot) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemStack processed = processItem(item, pending);
            inventory.setItem(slot, processed);
        }
    }

    private ItemStack processItem(ItemStack original, List<ItemStack> pending) {
        ItemStack item = original.clone();
        if (isBlacklisted(item.getType())) {
            return null;
        }
        if (isShulker(item)) {
            if (!hasContents(item)) {
                return null;
            }
            extractShulkerContents(item, pending);
            return null;
        }
        return stripNonCurseEnchantments(item);
    }

    private void extractShulkerContents(ItemStack shulkerItem, List<ItemStack> pending) {
        BlockStateMeta meta = (BlockStateMeta) shulkerItem.getItemMeta();
        if (meta == null || !(meta.getBlockState() instanceof ShulkerBox box)) {
            return;
        }
        for (ItemStack content : box.getInventory().getContents()) {
            if (content == null) {
                continue;
            }
            Material type = content.getType();
            if (type.isAir() || isBlacklisted(type)) {
                continue;
            }
            ItemStack leaf = content.clone();
            pending.add(stripNonCurseEnchantments(leaf));
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

    /**
     * 对带经验修补的物品做砂轮式祛魔：移除非诅咒附魔，必要时把空附魔书退化成普通书。
     * <p>【待确认 2】附魔书转普通书分支未做运行时实测：本地只有 paper-api，没有
     * paper-server/CraftBukkit 实现，无法验证 {@code setItemMeta(null)} 的真实行为
     * （契约说是「清除 meta」，但 CraftBukkit 历史实现会把堆叠置空）。这里选择直接删掉该调用，
     * 两种解释下都正确。建议测试服实测：带经验修补的附魔书在末地虚空死亡后重生，应掉出 1 本普通书。
     *
     * @return 处理后的物品；附魔书被祛成普通书时会返回一个全新的 {@link Material#BOOK} 堆叠，
     *         调用方必须使用返回值，不能再依赖传入的实例。
     */
    private ItemStack stripNonCurseEnchantments(ItemStack item) {
        if (!getConfig().getBoolean("remove-non-curse-enchant", true)) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        boolean hasMending = meta.getEnchants().containsKey(Enchantment.MENDING);
        if (meta instanceof EnchantmentStorageMeta storedMeta
                && storedMeta.getStoredEnchants().containsKey(Enchantment.MENDING)) {
            hasMending = true;
        }
        if (!hasMending) {
            return item;
        }
        // 砂轮祛魔：移除所有非诅咒附魔，保留绑定诅咒和消失诅咒
        if (meta instanceof EnchantmentStorageMeta storedMeta) {
            for (Enchantment enchantment : new HashSet<>(storedMeta.getStoredEnchants().keySet())) {
                if (!isCurse(enchantment)) {
                    storedMeta.removeStoredEnchant(enchantment);
                }
            }
            // 附魔书祛魔后若不再有附魔，退化为普通书。
            // 这里新建一个干净的书：setType 已废弃（Javadoc 明确不建议改已存在堆叠的类型），
            // 直接新建可确保没有残留的 STORED_ENCHANTMENTS 等组件。
            // 【待确认 4】为此本方法签名从 void 改为返回 ItemStack（仅插件内 2 处调用，无外部 API 影响）。
            // 若希望保留 void 签名，可退回 item.setType(...)，但会带 deprecation 警告。
            // 注意：withType(Material) 不能用，它会保留 item meta，而 EnchantmentStorageMeta 对 BOOK 不适用。
            if (item.getType() == Material.ENCHANTED_BOOK && storedMeta.getStoredEnchants().isEmpty()) {
                return ItemStack.of(Material.BOOK, Math.max(1, item.getAmount()));
            }
        } else {
            for (Enchantment enchantment : new HashSet<>(meta.getEnchants().keySet())) {
                if (!isCurse(enchantment)) {
                    meta.removeEnchant(enchantment);
                }
            }
        }
        item.setItemMeta(meta);
        // 砂轮在祛魔的同时会重置累计惩罚（repair_cost = 0）。
        // Paper 26.1.2 的 ItemMeta 已移除 setRepairCost，只能写数据组件；
        // 必须放在 setItemMeta 之后，否则可能被 meta 里带回来的旧值覆盖。
        item.setData(DataComponentTypes.REPAIR_COST, 0);
        return item;
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
        // 位置没有世界时 getWorld() 返回 null，getChunk()/dropItem 会连锁抛 NPE；
        // 世界已卸载时 getWorld() 甚至抛 IllegalArgumentException("World unloaded")。
        // isWorldLoaded() 两种情况都能安全判定，因此在这里直接退出；
        // 调用方会在 finally 中清理记录，玩家不会因为异常丢掉待发放记录。
        World world = location.isWorldLoaded() ? location.getWorld() : null;
        if (world == null) {
            getLogger().warning("Cannot release " + items.size() + " pending item stack(s) for "
                    + player.getName() + ": respawn location " + location + " has no loaded world");
            return;
        }
        // 掉落前确保区块已加载，未加载时区块内的掉落物会丢失。
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            world.getChunkAt(location);
        }
        // burst.radius 解释为「重生点所在格内的水平散落程度」，不是跨格半径。
        // 硬封顶 0.5：物品中心必须落在该方块开区间内，避免贴边后被挤进邻格卡墙。
        // 配置 > 0.5 时截断而不是改语义；缺省与 config.yml 一致，用 0.5。
        double configuredRadius = Math.max(0.0, getConfig().getDouble("burst.radius", 0.5));
        double radius = Math.min(configuredRadius, 0.5);
        if (configuredRadius > 0.5 && !loggedBurstRadiusClamp) {
            loggedBurstRadiusClamp = true;
            getLogger().warning("burst.radius=" + configuredRadius
                    + " exceeds the in-block limit 0.5 and was clamped to 0.5 "
                    + "so items stay inside the respawn block and do not clip into adjacent walls");
        }
        int pickupDelay = Math.max(0, getConfig().getInt("burst.pickup-delay-ticks", 10));
        // 以重生点所在方块的水平中心为原点。getRespawnLocation() 通常已是脚底中心
        // (blockX+0.5, feetY, blockZ+0.5)，但插件/床/世界出生点也可能给整数角点，
        // 必须先对齐中心，否则 +/-0.5 会立刻跨出该格。
        double centerX = location.getBlockX() + 0.5;
        double centerZ = location.getBlockZ() + 0.5;
        // y 用重生点脚底 +1：玩家站立格通常可通行，物品生成在胸口高度再下落。
        // 不探测上方空气：getBlock() 在未加载/边界处可能引入额外失败路径；
        // 重生点本身已被服务端校验为可站立，+1 是当前最稳的启发式。
        double dropY = location.getY() + 1.0;
        for (ItemStack item : items) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(random.nextDouble()) * radius;
            double offsetX = Math.cos(angle) * distance;
            double offsetZ = Math.sin(angle) * distance;
            Location dropLocation = new Location(world, centerX + offsetX, dropY, centerZ + offsetZ);
            Item entity = world.dropItem(dropLocation, item);
            entity.setPickupDelay(pickupDelay);
            // 水平初速必须很小：物品碰撞箱约 0.25，正负 0.04 在一格内足够散开，
            // 又不会在落地前冲出方块边界。vy=0，靠重力下落。
            entity.setVelocity(new Vector((random.nextDouble() - 0.5) * 0.08, 0, (random.nextDouble() - 0.5) * 0.08));
        }
        getLogger().info("Released " + items.size() + " pending item stack(s) for " + player.getName());
    }
}
