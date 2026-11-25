# CombatTechniqueTrainer

A small Spigot / Paper **1.8.8** plugin with practice dummies for 1.8 PvP techniques. Every hit is measured on the
server and explained in chat, so you can see *why* a technique worked or not instead of guessing.

## Techniques

### All in one: a player dummy — `/ctt npc [interval_ms] [level] [distance]`
A dummy that looks like a player (some clients and mods only treat players as opponents). Hit it to practise
displacement: you get the same report as with `/ctt target`, using the last rotation the server received from you
before your attack packet. It also hits you back with a proper arm swing every `interval_ms` (default 1000; `0` = it
never hits) with `level` extra knockback (default 1, a sprint hit) while you are within 3 blocks, and every hit you take
gets the jump-reset report of `/ctt hitme`. It faces you, takes vanilla 1.8 knockback (with the vanilla 0.5 s hurt
cooldown), and is put back on its spot 1.5 s after each hit (`/ctt npc setback <time>` changes that: `40t` in ticks or
`2s` in seconds, 5-200 ticks; kept for your next dummy). `/ctt npc off` removes it.

It exists only as packets (a player entity the server never adds to its world), so it needs Spigot / Paper 1.8.8 and
flat ground: it lands at the height it was spawned at. It shows in the tab list as `Sparring`.

### Knockback displacement — `/ctt target [distance]`
In 1.8 a melee hit pushes the victim 0.4 straight away from the attacker's **position**, plus 0.5 per extra knockback
level (sprinting = 1, each Knockback enchantment level = 1) along the attacker's **yaw**, i.e. the last rotation the
server received before the attack. Turning your look away for the tick of the hit sends the victim off sideways.

`/ctt target` spawns a dummy that cannot walk (default 3 blocks ahead) and gives you a Knockback I stick. For every hit
you get:

- how far your yaw was off the straight line to the target, as the server saw it;
- which way the target actually flew, next to what the physics predicts for that yaw;
- how long your look stayed away from the target, and how far.

The dummy returns to its spot 1.5 s after each hit.

**Moving target** — `/ctt target move [speed] [width] [distance]`: the dummy walks side to side across your line of
sight (default 2.5 blocks/s over 4 blocks; a strafing player moves about 4.3 blocks/s, a sprinting one about 5.6),
so you can no longer flick to a fixed spot. It stops while knocked back and carries on after it is put back.

**Practice partner** — `/ctt partner add <player>`: your hits on a friend are measured like hits on a dummy (their
own movement adds to where they fly, so expect more spread), and so is the knockback you take from them: every hit
they land on you gets the same jump-reset / push report as `/ctt hitme`, with the real vanilla knockback the server
gave. Partners are healed after each of your hits so practice does not kill them. `/ctt partner remove <player>`,
`/ctt partner list`. Unlike the villager dummies, a partner is a real player: their swing is real and clients treat
them like any other player.

Tips: displacement only turns the *extra* knockback, so sprint
and/or use the stick. With one extra level a 90° turn gives about 51° of sideways deflection; more levels give more.

### Taking knockback / jump reset — `/ctt hitme [interval_ms] [level]`
Spawns a sparring dummy 2.5 blocks ahead that hits you every `interval_ms` (default 1000) while you are within 4
blocks, with vanilla 1.8 knockback and `level` extra levels (default 1, like a sprint hit; 0 = plain hit). For every
hit you get:

- whether you jumped on the exact tick the knockback arrived (a **jump reset**), a tick early, or not at all;
- how far you were pushed along the knockback direction (over the next 10 moves) and sideways;
- how long after the hit your client's knockback move arrived, and your ping.

`/ctt hitme stop` removes it. You take no real damage. Survival or adventure mode only (creative takes no knockback).

### Returning fireballs — `/ctt fireball [interval_ms] [distance]`
Throws a fireball at you every `interval_ms` (default 4000; 1000-20000) from a spot `distance` blocks in front of
where you face when you start it (default 12), or from your player dummy if you have one (it swings as it throws). Hit
the fireball back: each one is timed from its throw ("Returned in 412 ms"), and one that reaches you counts as not
returned. The fireballs do not break blocks or set fires, and you are healed after a hit. `/ctt fireball off` stops it.
Logged to `fireballs.csv`; the session sidebar shows returned / thrown and the average reaction.

### Practice sessions — `/ctt session on`
Turns on a sidebar with your practice time, displacement hits / displaced / success rate, and knockback taken / jump
resets / rate / average push. It keeps counting across logins and server restarts until you run `/ctt session reset`
(`/ctt session off` pauses it and hides the sidebar; `/ctt session` prints it in chat). Time only counts while the
session is on and you are online. Stored in `plugins/CombatTechniqueTrainer/sessions.yml`.

## Commands

| Command | |
|---|---|
| `/ctt npc [interval_ms] [level] [distance]`, `/ctt npc setback <40t\|2s>`, `/ctt npc off` | player dummy: displacement target that also hits you (knockback / jump reset) |
| `/ctt target [distance]` | displacement dummy (1.5–6 blocks, default 3) and a Knockback I stick |
| `/ctt target move [speed] [width] [distance]` | a dummy walking side to side (0.5–6 blocks/s, 1–10 blocks wide) |
| `/ctt partner add\|remove <player>`, `/ctt partner list` | measure hits both ways with a friend |
| `/ctt fireball [interval_ms] [distance]`, `/ctt fireball off` | fireballs thrown at you to hit back, timed |
| `/ctt stick [level]` | a Knockback stick (1–5, default 1) |
| `/ctt hitme [interval_ms] [level]` | sparring dummy that hits you (250–5000 ms, level 0–5) |
| `/ctt hitme stop` | remove your sparring dummy |
| `/ctt session on\|off\|reset`, `/ctt session` | practice session on the sidebar (kept until reset) |
| `/ctt stats` | your displacement success rate and knockback results |
| `/ctt reset` | reset your results |
| `/ctt clear` | remove all dummies |

Alias: `/combattrainer`. Permission: `combattechnique.use` (default: op).

Results are also appended to `plugins/CombatTechniqueTrainer/displace.csv` (with the target kind: dummy, moving, partner, npc) and `knockback.csv` (with the source: dummy, or the partner's / player dummy's name) for your own analysis.

## Building

Requires JDK 11+ and Gradle (the jar targets Java 8):

```bash
gradle build
```

The jar is written to `build/libs/CombatTechniqueTrainer-<version>.jar`. Drop it into your test server's `plugins/`
folder.

## License

GPL-3.0, see [LICENSE](LICENSE).
