# End Void Death Protection

A Minecraft Paper plugin that protects ordinary items when a player dies in the void in The End.

## Features

- Keeps ordinary items after an End void death.
- Clears the player's level and experience and disables vanilla item and experience drops.
- Removes blacklisted items, including elytra, shulker shells, ender chests, obsidian, ender pearls, and end crystals.
- Removes non-curse enchantments while keeping Binding Curse and Vanishing Curse.
- Recursively processes shulker box contents up to 5 levels deep.
- Drops processed items around the respawn location after the player respawns.
- Stores pending drops in `pending-drops.yml` so they survive a player reconnect.

## Build

Requirements:

- Java 21
- Maven

Run:

```text
mvn clean package
```

Output:

```text
target/EndVoidRescue-1.0.0.jar
```

## Installation

Copy the built JAR into the Paper server's `plugins` directory, then start the server.

After the first start, edit:

```text
plugins/EndVoidRescue/config.yml
```

## Configuration

Main options:

```yaml
trigger-world: THE_END
trigger-cause: VOID
remove-non-curse-enchant: true
shulker:
  max-depth: 5
burst:
  radius: 2.0
  merge-similar: true
  pickup-delay-ticks: 10
  particle: EXPLOSION
  sound: ENTITY_GENERIC_EXPLODE
```

See `src/main/resources/config.yml` for the complete default configuration.
