# SpawnerProtect+

A standalone [Meteor Client](https://meteorclient.com/) addon for Minecraft 1.21.11.

Collects nearby spawners and disconnects the moment another player shows up. Built with DonutSMP-scale servers in mind.

---

## How it works

When a player entity enters your world, `EntityAddedEvent` fires instantly and kicks off the protection sequence — no waiting for a tick poll. It grabs every spawner in range using a silk touch pickaxe, dumps everything into a nearby ender chest, then disconnects.

A periodic scan runs every 20 ticks as a fallback in case you had the module on before a player was already loaded in.

---

## スタッフ検知 (Ignore Alphabet Prefix)

大規模サーバーでは、モデレーターがスペクターからサバイバルに切り替えて意図的にSpawnerProtect+を発動させようとしてくることがある。この設定はその対策。

プレイヤーのエンティティが出現したとき、ネームタグのプレフィックス（ユーザー名より前の部分）を確認する。そこにアルファベット（A〜Z）が含まれていれば保護を発動しない。`SRMOD`・`SRHELPER`・`DEV`・`OWNER` などのスタッフロールはすべてテキストのプレフィックスを持っている一方、通常のドナーランクは `+` や絵文字だけでアルファベットを含まない。

**Ignore Alphabet Prefix** をオンにしておけば、スタッフが来ても名前をログに残すだけでスルーする。

---

## Settings

| Setting | Default | Description |
|---|---|---|
| `notifications` | on | Chat feedback while running |
| `spawner-range` | 16 | Block radius to search for spawners |
| `spawner-timeout-ms` | 4000 | Skip a spawner if it takes longer than this to mine |
| `rollback-verify-ticks` | 8 | Ticks to wait before confirming a spawner is gone (rollback guard) |
| `deposit-to-echest` | on | Deposit inventory into ender chest before disconnecting |
| `deposit-blacklist` | see below | Items that stay in your inventory |
| `ignore-alphabet-prefix` | on | Skip protection if the player's nametag prefix has letters (staff) |
| `enable-whitelist` | off | Ignore specific player names |

Default deposit blacklist: Ender Pearl, End Crystal, Obsidian, Respawn Anchor, Glowstone, Totem of Undying.

---

## vs Glazed SpawnerProtect

| Feature | Glazed SpawnerProtect | SpawnerProtect+ |
|---|---|---|
| Detection | Tick polling | EntityAddedEvent (instant) + 20t scan |
| Staff skip | AdminList module | Nametag alphabet prefix check |
| Rollback detection | No | Yes — waits N ticks before confirming |
| Emergency disconnect | Distance-based | No |
| Auto movement | Walks to chest | No — assumes you're already near one |
| Player count cap | Disables if 3+ players nearby | No |
| Discord webhook | Yes (with embeds) | No |

---

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.18.1+
- [Meteor Client](https://meteorclient.com/) 1.21.11-SNAPSHOT

---

## Installation

Drop `spawner-protect-1.0.0.jar` into your mods folder. That's it.
