# EndVoidRescue 插件原理图

```mermaid
flowchart TD
    subgraph 死亡阶段["死亡阶段 (PlayerDeathEvent)"]
        A["玩家在末地虚空死亡"] --> B{"世界==THE_END<br/>且死因==VOID?"}
        B -- 否 --> Z["不做处理"]
        B -- 是 --> C["keepInventory=true<br/>getDrops().clear()"]
        C --> D["逐槽位处理背包 processItem<br/>返回值写回原槽位"]
        D --> E{"黑名单物品?"}
        E -- 是 --> X["返回 null，槽位清空"]
        E -- 否 --> F{"潜影盒?"}
        F -- 是且非空 --> G["提取内容逐件净化<br/>盒本身丢弃"]
        F -- 是且空 --> X
        F -- 否 --> H{"带经验修补?"}
        H -- 是 --> H2["砂轮祛魔: 移除所有非诅咒附魔<br/>重置 repair_cost 为 0"]
        H -- 否 --> I2["原样保留，写回槽位"]
        H2 --> H3{"附魔书祛魔后为空?"}
        H3 -- 是 --> H4["退化为普通书"]
        H3 -- 否 --> I2
        H4 --> I2
        G --> I["加入 pending 列表"]
        I --> J["mergeIfConfigured 合并同类堆叠"]
        J --> K["PendingDropStore.put<br/>写入 pending-drops.yml"]
    end

    subgraph 重生阶段["重生阶段 (PlayerRespawnEvent)"]
        K --> L["玩家重生"]
        L --> L2{"pending 是否为空?"}
        L2 -- 是 --> Q["store.remove 清理空记录"]
        L2 -- 否 --> M["store.get 读取待爆出物品"]
        M --> N["延迟 1 tick 确保传送完成"]
        N --> O["burst 在重生点散落物品"]
        O --> P["拾取延迟 + 随机散落"]
        O --> Q["try/finally: store.remove 清理记录<br/>burst 异常也不残留"]
    end
```

## 潜影盒处理原理图

```mermaid
flowchart TD
    A["背包槽位中的潜影盒 ItemStack"] --> B{"isShulker?<br/>类型名以 SHULKER_BOX 结尾"}
    B -- 否 --> Z["按普通物品处理"]
    B -- 是 --> C{"hasContents?<br/>盒内是否有物品"}
    C -- 否 --> X["丢弃空潜影盒"]
    C -- 是 --> D["extractShulkerContents<br/>读取 BlockStateMeta 中的 ShulkerBox"]
    D --> E["遍历盒内 27 格内容"]
    E --> F{"内容为空?"}
    F -- 是 --> G["跳过"]
    F -- 否 --> H{"黑名单物品?"}
    H -- 是 --> I["丢弃该内容"]
    H -- 否 --> J["clone 克隆副本"]
    J --> K{"带经验修补?"}
    K -- 否 --> L["原样保留"]
    K -- 是 --> M["stripNonCurseEnchantments<br/>移除非诅咒附魔<br/>重置 repair_cost 为 0"]
    M --> N{"附魔书祛魔后<br/>不再有附魔?"}
    N -- 是 --> O["退化为普通书"]
    N -- 否 --> P["保留处理后的物品"]
    O --> Q["加入 pending 列表"]
    P --> Q
    L --> Q
    G --> E
    I --> E
    Q --> E
    E --> R["盒本身丢弃, 不保留"]
    R --> S["所有槽位处理完毕后<br/>统一 mergeIfConfigured 合并 -> store.put 写入 yml"]
```

## 潜影盒物理效果流程图

```mermaid
flowchart TD
    A["burst() 开始<br/>读取 radius / pickup-delay"] --> B["确保区块已加载<br/>world.getChunkAt(location)"]
    B --> C["遍历每个待爆出 ItemStack"]
    C --> D["生成随机角度<br/>θ ~ U(0, 2π)"]
    D --> E["均匀面积分布半径<br/>r = sqrt(u) * radius, u ~ U(0,1)"]
    E --> F["计算掉落坐标<br/>x = x0 + cos(θ)·r<br/>y = y0 + 1<br/>z = z0 + sin(θ)·r"]
    F --> G["dropItem 生成 Item 实体"]
    G --> H["setPickupDelay<br/>防止立刻被拾取"]
    H --> I["setVelocity<br/>vx = (u1-0.5)·0.15<br/>vy = 0<br/>vz = (u2-0.5)·0.15"]
    I --> J["受重力与阻力影响<br/>自然下落"]
    J --> K{"还有物品?"}
    K -- 是 --> C
    K -- 否 --> L["记录日志<br/>Released N item stack(s)"]
    L --> M["结束"]
```

### 符号含义

| 符号 | 含义 | 取值范围 / 说明 |
|------|------|----------------|
| $\theta$ | 随机角度 | $\theta \sim U(0, 2\pi)$，决定物品散落的水平方向 |
| $u, u_1, u_2$ | 均匀随机数 | $\sim U(0, 1)$，由 `random.nextDouble()` 生成 |
| $r$ | 散落半径 | $r = \sqrt{u} \cdot R$，使用平方根实现**均匀面积分布**，避免物品集中在圆心 |
| $R$ | 配置半径 | 来自 `burst.radius`，默认 `2.0` |
| $x_0, y_0, z_0$ | 重生点坐标 | 玩家最终重生位置 |
| $x, y, z$ | 物品生成坐标 | $x = x_0 + \cos(\theta) \cdot r$<br>$y = y_0 + 1$<br>$z = z_0 + \sin(\theta) \cdot r$ |
| $v_x, v_y, v_z$ | 初速度分量 | $v_x = (u_1 - 0.5) \cdot 0.15$<br>$v_y = 0$<br>$v_z = (u_2 - 0.5) \cdot 0.15$ |
| $N$ | 物品堆叠数 | 本次爆出的 `ItemStack` 数量 |
