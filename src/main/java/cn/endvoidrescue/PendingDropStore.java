package cn.endvoidrescue;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PendingDropStore {
    private final File file;
    private final YamlConfiguration data;

    PendingDropStore(File dataFolder) {
        this.file = new File(dataFolder, "pending-drops.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    synchronized void put(UUID playerId, List<ItemStack> items) {
        String path = "players." + playerId;
        // 空列表且玩家无记录时，数据无变化，跳过写盘。
        if (items.isEmpty() && !data.contains(path)) {
            return;
        }
        data.set(path, null);
        for (int index = 0; index < items.size(); index++) {
            data.set(path + "." + index, items.get(index));
        }
        if (items.isEmpty()) {
            // 空列表不留下空记录，否则会被序列化成 "players.<uuid>: {}"。
            pruneEmptyPlayersSection();
        }
    }

    synchronized List<ItemStack> get(UUID playerId) {
        String path = "players." + playerId;
        ConfigurationSection section = data.getConfigurationSection(path);
        if (section == null) {
            return List.of();
        }
        List<ItemStack> result = new ArrayList<>();
        // getKeys(false) 返回 Set，顺序不保证；但本场景中物品最终随机散落，顺序无关紧要。
        for (String key : section.getKeys(false)) {
            ItemStack item = section.getItemStack(key);
            if (item != null && !item.getType().isAir()) {
                result.add(item);
            }
        }
        return result;
    }

    synchronized void remove(UUID playerId) {
        String path = "players." + playerId;
        if (!data.contains(path)) {
            // 没有记录可清理（例如死亡时背包为空），只更新内存中的空壳。
            pruneEmptyPlayersSection();
            return;
        }
        data.set(path, null);
        pruneEmptyPlayersSection();
    }

    /** 所有玩家记录都清空后，把空的 players 段一并删除，避免文件里留下 "players: {}"。返回是否发生了清理。 */
    private boolean pruneEmptyPlayersSection() {
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players != null && players.getKeys(false).isEmpty()) {
            data.set("players", null);
            return true;
        }
        return false;
    }

    synchronized void save() {
        try {
            data.save(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save pending drops", exception);
        }
    }
}
