# RoyalItems

**Give every vanilla item a name, lore, rarity, and identity — without turning it into a custom item.**

RoyalItems dresses ordinary Minecraft items (coal, a diamond sword, a raw cod) with the polished look
players expect from a SkyBlock-style server — a coloured name, descriptive lore, a rarity tier — while
**keeping the exact same `Material`**. A dressed diamond sword is still a fully-functional vanilla
diamond sword: it enchants, repairs, and works in every recipe and shop exactly as before. The only
things that change are what the player *sees* (lore) and what a plugin can *read* (a persistent-data
identity).

Built for **Paper 1.21+**, Java 21. Lightweight, config-driven, and safe to run alongside custom-item
plugins like EcoItems — it never touches an item that already has an identity.

---

## Why it's different

| | Plain vanilla | A custom-item plugin | **RoyalItems** |
|---|---|---|---|
| Has lore / rarity | ❌ | ✅ | ✅ |
| Still the same Material | ✅ | ❌ (usually a re-skin) | ✅ |
| Works in vanilla recipes/shops | ✅ | ⚠️ depends | ✅ |
| Readable identity for other plugins | ❌ | ✅ | ✅ (PDC `item_id` / `fuel_id`) |

**The one rule to understand:** *identity lives in the item's persistent data (PDC); lore is only
visual.* Nothing in RoyalItems — or any plugin reading it — should ever parse lore for logic. Ask the
PDC instead.

---

## Features

- **Rule engine** — dress a whole family of items in ~5 lines. One `gear` rule covers every tool and
  armour piece with tier-based rarity, instead of hundreds of entries.
- **Rarity ladder** — Common → Mythic, defined once and reused everywhere.
- **MiniMessage + legacy** — write lore in `&` codes *or* MiniMessage (`<gradient:#55ffff:#5555ff>`,
  `<#ff00ff>`, `<red>`).
- **Formats at the source, never on a timer** — mined blocks, mob drops, harvests, crafting, smithing,
  chest loot, furnace output, and (optionally) a player's inventory on join.
- **Format existing items** — `/royalitems formatinv` dresses what players already own.
- **Non-destructive** — never overwrites an item that already has a name, lore, enchant, or another
  plugin's data.
- **Developer API** — other plugins read a drop's identity through the Bukkit ServicesManager.

---

## Installation

1. Drop `RoyalItems.jar` into `plugins/`.
2. Start the server once — it generates `config.yml`, `rarities.yml`, `rules.yml`, and `items/`.
3. Edit to taste and run `/royalitems reload`.

---

## Configuration

```
plugins/RoyalItems/
├── config.yml     master switch, disabled worlds, format-on-join
├── rarities.yml   the rarity ladder (Common → Mythic)
├── rules.yml      rules that dress whole item families
└── items/         explicit per-item definitions (one file per category)
    ├── mining.yml
    ├── farming.yml
    └── ...
```

The `items/` folder is scanned **recursively**, so you can organise into subfolders however you like.
Explicit items always win over a rule.

### `config.yml`

```yaml
enabled: true              # master switch
disabled-worlds: []        # worlds RoyalItems ignores (by name, case-insensitive)
format-on-join: false      # dress a player's whole inventory when they join
```

### `rarities.yml`

```yaml
rarities:
  common:    { display: "&f&lCOMMON",    color: "&f" }
  uncommon:  { display: "&a&lUNCOMMON",  color: "&a" }
  rare:      { display: "&9&lRARE",       color: "&9" }
  epic:      { display: "&5&lEPIC",       color: "&5" }
  legendary: { display: "&6&lLEGENDARY",  color: "&6" }
  mythic:    { display: "&d&lMYTHIC",     color: "&d" }
```

- `display` — the line shown at the foot of the item's lore.
- `color` — a colour a rule can tint the item's name with (`%rarity_color%`).

### `rules.yml` — the powerful part

A rule matches a family of materials and dresses each one from a template. Rules are expanded into
concrete items at load, so there is **no runtime cost** — it's a plain lookup.

```yaml
rules:
  gear:
    match:                       # material-name globs
      - "*_SWORD"
      - "*_PICKAXE"
      - "*_HELMET"
      - "*_CHESTPLATE"
      # ...
    tier-rarity:                 # rarity from the material's tier prefix
      WOODEN: common
      IRON: uncommon
      DIAMOND: rare
      NETHERITE: epic
    display-name: "%rarity_color%%name%"
    lore:
      - "&7%type%"
      - ""
      - "%rarity%"
    format-on:
      - craft
      - smith
```

**Match by:**
- `match` — material-name globs: `*_SWORD`, `DIAMOND_*`, or `*` for everything.
- `materials` — an explicit list (`[BOW, TRIDENT, SHIELD]`).

**Rarity:**
- `rarity: rare` — a flat rarity, or
- `tier-rarity:` — a material-name prefix → rarity map (so `DIAMOND_*` is Rare, `NETHERITE_*` Epic).

**Placeholders** (usable in `display-name`, `lore`, and `tags`):

| Placeholder | Example (for `DIAMOND_SWORD`) |
|---|---|
| `%name%` | `Diamond Sword` |
| `%type%` | `Sword` |
| `%material%` | `DIAMOND_SWORD` |
| `%rarity%` | `&9&lRARE` |
| `%rarity_color%` | `&9` |
| `%category%` | the rule's `category`, or `%type%` |

### `items/*.yml` — explicit items

For items that need bespoke lore (like resources with a skill line), define them directly:

```yaml
formatted-items:
  coal:
    material: COAL
    display-name: "&fCoal"
    lore:
      - "&7Minion Fuel"
      - "&7+10% Minion Speed"
      - "&7Duration: &aForever"
      - ""
      - "&f&lCOMMON"
    tags:
      item_id: "coal"
      fuel_id: "coal"
    format-on:
      - mined
```

`item_id` defaults to the entry key; add `fuel_id` or any other tag you want other plugins to read.

---

## When items get dressed (`format-on`)

Granular creation events respect each item's `format-on`:

| Source | Event | Notes |
|---|---|---|
| `mined` | block break drops | the default |
| `mob` | mob death drops | for combat drops |
| `harvest` | right-click harvest | sweet berries, cave vines |
| `craft` | crafting table | gear |
| `smith` | smithing table | netherite upgrades |

Acquisition moments dress **any** supported item automatically (no opt-in needed): **chest loot**,
**furnace / blast furnace / smoker output**, and **format-on-join**.

---

## Commands & permissions

| Command | Permission | Description |
|---|---|---|
| `/royalitems give <player> <id> [amount]` | `royalitems.give` | Give a formatted item |
| `/royalitems reload` | `royalitems.reload` | Reload all config |
| `/royalitems info` | `royalitems.admin` | Inspect the held item's identity |
| `/royalitems formatinv <player>` | `royalitems.formatinv` | Dress items a player already owns |

Aliases: `/ritems`, `/rit`. `royalitems.admin` (default: op) grants the rest.

---

## Text formatting

Any name or lore line is rendered as **MiniMessage** if it contains a tag (`<red>`, `<#ff00ff>`,
`<gradient:...>`), otherwise as legacy **`&`** codes. Both are always rendered non-italic, so items read
cleanly. Mix freely across lines.

```yaml
display-name: "<gradient:#f7971e:#ffd200>%name%</gradient>"
lore:
  - "&7A legendary blade."
  - "<rainbow>%rarity%</rainbow>"
```

---

## Developer API

RoyalItems registers a `FormattedItemService` on the Bukkit **ServicesManager**. Identity is stored in
persistent data, so a formatted coal is still `Material.COAL` *and* reads as `item_id: coal`.

```java
RegisteredServiceProvider<FormattedItemService> rsp =
        Bukkit.getServicesManager().getRegistration(FormattedItemService.class);
if (rsp != null) {
    FormattedItemService items = rsp.getProvider();

    boolean formatted = items.isFormattedItem(stack);
    String id   = items.getItemId(stack);   // "coal", or null
    String fuel = items.getFuelId(stack);   // "coal", or null
    ItemStack dressed = items.format("coal", 16);
    ItemStack maybe   = items.formatIfSupported(vanillaStack);
}
```

**Soft dependency (no compile-time link):** read the PDC directly, or look up the service by class name
via reflection — the identity keys are `NamespacedKey(royalitems, "item_id")`, `…"fuel_id"`, etc. Add
`RoyalItems` to your `softdepend` so it loads first.

Because a formatted item keeps its `Material`, a plugin that already matches by material (a minion fuel
slot, a shop price, a recipe ingredient) accepts it **with no changes** — the PDC identity is there when
you want stricter matching.

---

## Compatibility

- **Vanilla:** formatted items keep their `Material`, so recipes, enchanting, repair, trading, and
  shops behave exactly as they did.
- **EcoItems / custom-item plugins:** RoyalItems never dresses an item that already has a name, lore,
  enchant, or another plugin's persistent data — so custom items on a shared base material are left
  untouched.
- **Stacking:** a formatted item does not stack with a plain vanilla one of the same kind (different
  data). For tools and armour this is irrelevant (they never stacked). For resources, format the item
  everywhere it enters play (RoyalItems does this at the source), so stacks stay whole.

---

## License

© joogiebear. All rights reserved.
