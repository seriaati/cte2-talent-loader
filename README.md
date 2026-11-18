# CtE2 Talent Loader

A client-side Forge mod for Craft to Exile 2 (Minecraft 1.20.1). It sets up your character from a build made in the [cte2-db build editor](https://cte2.seria.moe), so you don't have to click every point in by hand.

It fills in:

- talent, ascendancy and atlas tree nodes
- attribute points (Strength, Dexterity, Intelligence)
- class spells and passives

The server checks every point the same way it checks a click in the game's own screens, so the mod can't give you anything you couldn't take yourself. It only adds points and never takes any back. Servers don't need it installed.

## Download

**[Download the latest build (cte2talents.jar)](https://github.com/seriaati/cte2-talent-loader/releases/latest/download/cte2talents.jar)**, rebuilt on every push to `master`. Drop it into your instance's `mods` folder.

## Usage

1. Export your build from the editor at [cte2.seria.moe](https://cte2.seria.moe).
2. Put the `.json` file in the `cte2builds/` folder of your Minecraft instance. The mod creates the folder the first time you run the command.
3. In game, run `/cte2build <file>`. Tab completes the file names.

Chat shows what was sent, and a moment later what the server accepted. Anything you can't afford yet is held back, so run the command again after levelling to add the rest.

## Building

```sh
./gradlew build
```

Gradle needs Java 17. Mine and Slash and Library of Exile are fetched from CurseForge through [Cursemaven](https://www.cursemaven.com), at the versions Craft to Exile 2 ships.
