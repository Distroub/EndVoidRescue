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
        data.set(path, null);
        for (int index = 0; index < items.size(); index++) {
            data.set(path + "." + index, items.get(index));
        }
        save();
    }

    synchronized List<ItemStack> get(UUID playerId) {
        String path = "players." + playerId;
        ConfigurationSection section = data.getConfigurationSection(path);
        if (section == null) {
            return List.of();
        }
        List<ItemStack> result = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ItemStack item = section.getItemStack(key);
            if (item != null && !item.getType().isAir()) {
                result.add(item);
            }
        }
        return result;
    }

    synchronized void remove(UUID playerId) {
        data.set("players." + playerId, null);
        save();
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save pending drops", exception);
        }
    }
}
