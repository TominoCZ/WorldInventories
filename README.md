# World-Bound

A Hytale server plugin that changes player data storage from universe-scoped to world-scoped.
By default, Hytale saves player data (inventory, stats, etc.) once per universe, meaning the same player data is shared
across all worlds.
World-Bound modifies this behavior to save separate player data for each world, allowing players to have different
inventories and progression in different worlds.

## Features

- **Per-World Player Data**: Each world maintains its own player data files
- **Automatic Migration**: Copies existing universe-level player data to the default world on first startup
- **Thread-Safe**: Uses locking mechanisms to prevent data corruption during concurrent access
- **Seamless Transitions**: Automatically loads and saves appropriate player data when switching between worlds

## How It Works

When a player joins or switches worlds, the plugin:

1. Loads their player data specific to that world
2. Applies the inventory and other stored data to the player
3. Saves the data back when they leave the world or disconnect

Player data files are stored in `<world>/players/<uuid>.json` instead of `<universe>/players/<uuid>.json`.
