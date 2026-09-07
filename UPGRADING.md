# RoyalItems 0.2.0

Requires Java 25 and Paper 26.2 (built against build 121). Replace the jar and restart the server for
the code upgrade. Subsequent configuration edits use `/royalitems reload`.

## Changes

- Dressing preserves the original stack's state, including container contents and tool damage.
- Definition refresh preserves player-edited names/lore and externally changed tooltip styles.
- Removed identity keys are reconciled; foreign PDC namespaces remain untouched.
- Legacy items migrate conservatively: unknown presentation is retained, not overwritten.
- Automatic formatting honors the master switch. A world-aware API supports world exclusions.
- Reload replaces listeners/timers and rejects malformed YAML without clearing the live catalog.
- API definitions and collections are immutable; reserved identity keys cannot be overwritten in config.
- Rarity detection accepts decorated standalone labels such as `[EPIC]`.

The companion RoyalMinions change checks RoyalItems' settings before delivering output. It works
with the existing `worldEnabled(World)` API; no hard dependency is introduced.

## Configuration behavior

`item_id`, `def_hash` and keys starting with `_ri_` are reserved. Extra tag names must be lowercase
valid namespaced-key paths. Invalid configured item materials, reserved tags and malformed files
reject the reload. Fix the error and reload again; the erroneous file is not rewritten.

Legacy stacks without ownership stamps keep their current name, lore and border. The current
definition becomes the reference for future ownership comparisons. Legacy RoyalItems-namespaced
identity keys absent from the new definition are removed; other namespaces are preserved.

Disabling automatic formatting does not undo decoration already stored on items. The explicit
`give`/`format(id, amount)` factory remains usable. Existing and freshly migrated stacks can have
different metadata until they pass through formatting.

## Staging verification before rollout

1. Use a backup copy of representative player inventories. Check a filled unnamed shulker box,
   damaged tool, lodestone compass and charged crossbow before/after join and container close.
2. Rename a newly dressed sword, change its configured lore, reload, and confirm the name survives.
3. Remove a fuel/extra tag and verify existing stacks lose it while foreign PDC remains intact.
4. Toggle the master switch and world exclusions; collect RoyalMinions output in each world.
5. Change the sweep interval, then turn it off; reload each time. Test tooltip borders with
   PacketEvents installed and absent, and with the actual resource pack.
6. Introduce malformed YAML, reload, and confirm the previous catalog continues to work.
7. Check shift crafting, smithing, trading and any shop that compares complete item metadata.

Unit tests use Bukkit doubles. They verify service logic and lifecycle behavior; they do not run
a Paper server or validate client packets end to end.
