# Aeternum Seasons
*A dynamic and realistic season system for Minecraft Survival | Plug & Play*

English | [中文](README_zh.md)

> **Unofficial rebuild notice**: This repository is a **decompiled, buildable rebuild** of the plugin
> `AeternumSeasons-4.7.jar`, created for personal learning and research only.
> **All rights to Aeternum Seasons belong to the original authors — [Kinkin](https://github.com/KinkinxD12/Aeternum-Seasons) & MD (salvador).**
> Original project: https://github.com/KinkinxD12/Aeternum-Seasons
> If you enjoy the plugin, please support the original author: https://www.paypal.com/paypalme/KinkinxD

---

## 🌱 About

Aeternum Seasons brings a cozy, Stardew-Valley-style year cycle to vanilla Minecraft – **without killing your TPS**.

Watch your world slowly change through **Spring, Summer, Autumn and Winter**:
- Biome colors shift smoothly
- Snow covers the ground in winter
- Autumn forests turn golden
- Sky and weather follow the season

All fully optimized and spread over time to avoid lag spikes.

---

## 🔥❄️ Two Optional Adventure Realms

- **Frost Realm** – Glowstone portal. Blizzards, low visibility, frozen terrain, and icy mob variants that slow and freeze you.
- **Heat / Infernal Realm** – Nether Wart Block portal. Scorching heat, dangerous terrain, and fiery mobs that burn and hit harder.

Each realm has its own risks, atmosphere, and progression – turning your survival world into a mini adventure RPG, while still feeling vanilla-friendly.

---

## ✨ Features

- **Dynamic, vanilla-style seasons**: weather, sky, and biome tints react to the current season
- **Autumn forests**: taiga and birch leaves turn warm acacia-like colors in autumn
- **Highly optimized**: changes are spread across days to avoid lag spikes
- **Two optional dimensions**: Frost & Heat, with themed terrain and mob variants
- **Fully configurable**: toggle features, dimensions, weather rules, and more
- **Plug & Play**: for Paper / Spigot 1.21.x
- **PlaceholderAPI support**: for HUDs, scoreboards, menus, etc.
- **15 languages included** (`lang/`): en_US, zh_CN, zh_TW, de_DE, es_ES, es_MX, fr_FR, id_ID, it_IT, pl_PL, pt_BR, ru_RU, tr_TR, vi_VN

---

## 🛠 Building this rebuild

- **JDK 21** (`--release 21`), Maven, target server **Paper/Spigot >= 1.21.11**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'   # adapt to your JDK 21
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn clean package
# output: target/AeternumSeasons-4.7.jar
```

Dependency version constraints (JDK 21 cannot read Java-25 bytecode):

| Dependency | Version | Note |
|---|---|---|
| paper-api | 1.21.11-R0.1-SNAPSHOT | provided |
| worldguard-bukkit | **7.0.17** | 7.0.18+ ships Java 25 bytecode (class 69) |
| worldedit-bukkit | **7.4.2** | 7.4.3+ ships Java 25 bytecode |
| placeholderapi | 2.11.7 | provided |
| custom-crops | 3.6.40 | provided |

---

## 🧩 Rebuild notes (decompilation fixes)

The sources were recovered with IntelliJ Fernflower, then fixed to compile again:

1. Removed illegal `super();` from 36 record compact constructors.
2. `Attribute.GENERIC_*` constants renamed (removed in 1.21.3+).
3. Restored generics erased by the decompiler (raw collections, sort lambdas, casts).
4. `Biome` switch replaced with `==` chains — `Biome` is an interface (OldEnum) in 1.21.11.
5. Duplicated locals, non-final lambda captures, `orElse((Object)null)` and similar artifacts cleaned up.

The untouched Fernflower output is kept as `AeternumSeasons-4.7-sources.jar` for reference.
The built jar differs from the original only by one synthetic switch class (`FrostBiomeFixer$1`) and Maven metadata.

---

## 📄 Credits & Copyright

- **Aeternum Seasons** © **Kinkin** & **MD (salvador)** — all rights reserved by the original authors.
- Original project & updates: https://github.com/KinkinxD12/Aeternum-Seasons
- Support the author: https://www.paypal.com/paypalme/KinkinxD
- This rebuild is for **personal learning/research only**, not for commercial use. If the original author objects to this repository, it will be removed upon request.
