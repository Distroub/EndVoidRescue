# 末地虚空救援

一个 Minecraft Paper 插件，为 [CrCraft](https://github.com/LHM056awa/CrCraft-Info) 服务器实现**末地虚空救援方案**。

玩家在末地因虚空死亡时，插件会保留背包中的普通物品，并在玩家重生后将待返还物品散落在重生点附近。

## 适用环境

- Java 25
- Maven
- Paper 26.1.2

## 功能

- 仅在配置的世界和死亡原因匹配时触发，默认是末地（`THE_END`）和虚空伤害（`VOID`）。
- 保留玩家背包，并清空原版物品掉落列表。
- 丢弃黑名单物品，包括鞘翅、潜影壳、末影箱、黑曜石、末影珍珠和末影水晶。
- 展开背包中的第一层潜影盒，丢弃潜影盒本身，将其内容加入待返还列表。
- 保留背包中的收纳袋，并递归清理其中物品；黑名单物品会被移除，带经验修补的物品会执行砂轮式祛魔。
- 收纳袋最多递归处理 15 层；潜影盒内的收纳袋也会按相同规则处理。
- 不递归展开嵌套潜影盒；嵌套潜影盒按普通物品处理。
- 仅对带有经验修补（`MENDING`）的物品执行砂轮式祛魔：移除所有非诅咒附魔，保留绑定诅咒和消失诅咒，并将 `repair_cost` 重置为 `0`。
- 带有经验修补的附魔书在祛魔后不再包含任何附魔时，会退化为普通书。
- 按配置合并相同的可堆叠物品。
- 玩家重生 1 tick 后，在重生点所在方块内随机散落物品，并设置拾取延迟。
- 待返还物品保存在 `pending-drops.yml`，玩家重连后仍可继续领取。

## 构建

运行：

```bash
mvn clean package
```

## 安装

将构建好的 JAR 复制到服务器的 `plugins` 目录，然后启动服务器。

首次启动后编辑：

```text
plugins/EndVoidRescue/config.yml
```

修改配置后需要重启插件或服务器才能生效。

## 配置

完整默认配置：

```yaml
trigger-world: THE_END
trigger-cause: VOID
blacklist:
  - ELYTRA
  - SHULKER_SHELL
  - ENDER_CHEST
  - OBSIDIAN
  - ENDER_PEARL
  - END_CRYSTAL
remove-non-curse-enchant: true
burst:
  radius: 0.5
  merge-similar: true
  pickup-delay-ticks: 10
```

### 选项说明

- `trigger-world`：触发救援的世界环境，使用 Bukkit 的 `World.Environment` 名称。
- `trigger-cause`：触发救援的死亡原因，使用 Bukkit 的 `DamageCause` 名称。
- `blacklist`：死亡时直接丢弃的材料名称列表。
- `remove-non-curse-enchant`：是否启用经验修补触发的砂轮式祛魔规则。
- `burst.radius`：重生点所在格内的水平散落程度（格）。有效上限 `0.5`，更大的值会被截断，避免物品落到邻格卡墙。
- `burst.merge-similar`：是否在发放前合并相同的可堆叠物品。
- `burst.pickup-delay-ticks`：生成物品实体后的拾取延迟，单位为 tick。

等级和经验不在本插件中显式修改，按服务器默认的死亡逻辑处理。

## 持久化

待返还物品写入：

```text
plugins/EndVoidRescue/pending-drops.yml
```

物品使用 Bukkit 的完整 `ItemStack` 序列化方式保存，因此会保留名称、lore、附魔和其他物品元数据。空的玩家记录会在清理时从 YAML 中删除。

## 文档

- [插件原理图](docs/plugin-diagram.md)
