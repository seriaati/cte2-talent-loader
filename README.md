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

## Exporting your gear

The mod also writes down the Mine and Slash gear, jewels and gems you own, so you can hand the list to an AI and ask for build advice that uses what you already have.

It writes two files to the `cte2gear/` folder, named after the server: a `.md` file grouped by slot for pasting into a chat, and a `.json` file with the same items.

- What you're wearing (armor, weapons, Curios jewelry), your inventory, socketed jewels and gem slots are read every time the files are written.
- On a server your client only sees what's in a container while it's open, so the mod records each chest, backpack, Master Bag tab or Refined Storage grid when you close it. Walk past your storage and open each one once. A Refined Storage grid records the whole network, whatever its search box shows.
- `/cte2gear scan` does the walking for you within arm's reach: it opens every chest, barrel, ender chest, Sophisticated Storage block and Refined Storage grid you could click from where you stand, one at a time, and closes each once it's recorded. Run it with an empty main hand, since each container is clicked with that hand. Containers that are locked or claimed by someone else are skipped.
- Opening a container again replaces its record. `/cte2gear` rewrites the files now, and `/cte2gear clear` forgets every recorded container.

## Building

```sh
./gradlew build
```

Gradle needs Java 17. Mine and Slash and Library of Exile are fetched from CurseForge through [Cursemaven](https://www.cursemaven.com), at the versions Craft to Exile 2 ships.
