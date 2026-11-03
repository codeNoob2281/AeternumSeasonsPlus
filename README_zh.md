# Aeternum Seasons

*一套为 Minecraft 生存打造的动态、拟真季节系统 | 开箱即用*

[English](README.md) | 中文

> **非官方重建声明**：本仓库是插件 `AeternumSeasons-4.7.jar` 的 **反编译可构建重建版**，仅用于个人学习与研究。
> **Aeternum Seasons 的一切权利归原作者 [Kinkin](https://github.com/KinkinxD12/Aeternum-Seasons) 与 MD (salvador)
所有。**
> 原项目：https://github.com/KinkinxD12/Aeternum-Seasons
> 如果你觉得这个插件不错，请支持原作者：https://www.paypal.com/paypalme/KinkinxD

---

## 🌱 关于

Aeternum Seasons 为原版 Minecraft 带来温馨的、星露谷物语式的年度循环—— **并且不拖累 TPS**。

看你的世界随 **春夏秋冬**四季缓慢变化：

- 生物群系颜色平滑过渡
- 冬天积雪覆盖大地
- 秋天森林染成金黄
- 天空与天气跟随季节变化

所有效果都经过充分优化，并分散在时间轴上执行，避免卡顿尖峰。

---

## 🔥❄️ 两个可选冒险位面

- **霜寒位面（Frost Realm）**——萤石传送门。暴风雪、低能见度、冰冻地形，以及会减速并冻结你的冰系变异生物。
- **炽热/炼狱位面（Heat Realm）**——下界疣传送门。灼热高温、危险地形，以及更耐打、攻击附带燃烧的火系生物。

每个位面都有独立的风险、氛围与成长线——让生存世界变成一个迷你冒险 RPG，同时保持原版风味。

---

## ✨ 特性

- **动态原版风格四季**：天气、天空、生物群系着色随季节变化
- **秋季森林**：云杉叶与白桦叶在秋天变为温暖的金合欢色
- **高度优化**：地形变化分散到多天完成，避免卡顿
- **两个可选维度**：霜寒与炽热，带主题地形与变异生物
- **完全可配置**：功能开关、维度、天气规则等均可配置
- **开箱即用**：支持 Paper / Spigot 1.21.x
- **支持 PlaceholderAPI**：可用于 HUD、计分板、菜单等
- **内置 15 种语言**（`lang/`）：en_US、zh_CN、zh_TW、de_DE、es_ES、es_MX、fr_FR、id_ID、it_IT、pl_PL、pt_BR、ru_RU、tr_TR、vi_VN

---

## 🛠 构建本重建版

- **JDK 21**（`--release 21`）、Maven，目标服务器 **Paper/Spigot >= 1.21.11**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'   # 按你的 JDK 21 路径调整
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn clean package
# 产物: target/AeternumSeasons-4.7.jar
```

依赖版本约束（JDK 21 无法读取 Java 25 字节码的依赖）：

| 依赖              | 版本                  | 说明                                  |
|-------------------|-----------------------|---------------------------------------|
| paper-api         | 1.21.11-R0.1-SNAPSHOT | provided                              |
| worldguard-bukkit | **7.0.17**            | 7.0.18+ 是 Java 25 字节码（class 69） |
| worldedit-bukkit  | **7.4.2**             | 7.4.3+ 是 Java 25 字节码              |
| placeholderapi    | 2.11.7                | provided                              |
| custom-crops      | 3.6.40                | provided                              |

---

## 🧩 重建说明（反编译修复）

源码使用 IntelliJ Fernflower 反编译恢复，随后修复使其可重新编译：

1. 删除 36 处 record 紧凑构造器中非法的 `super();`。
2. `Attribute.GENERIC_*` 常量更名（1.21.3+ 已移除旧常量）。
3. 补回反编译器丢失的泛型（raw 集合、sort lambda、强转等）。
4. `Biome` 的 enum switch 改为 `==` 判断——1.21.11 中 `Biome` 是接口（OldEnum）。
5. 清理重复局部变量、lambda 捕获非终态变量、`orElse((Object)null)` 等反编译痕迹。

未经修改的 Fernflower 原始输出留档于 `AeternumSeasons-4.7-sources.jar`。 构建产物与原版 jar 的差异仅为一个 switch 合成类（
`FrostBiomeFixer$1`）和 Maven 元数据。

---

## 📄 版权与致谢

- **Aeternum Seasons** © **Kinkin** & **MD (salvador)** —— 一切权利归原作者所有。
- 原项目与更新：https://github.com/KinkinxD12/Aeternum-Seasons
- 支持原作者：https://www.paypal.com/paypalme/KinkinxD
- 本重建版仅用于 **个人学习/研究**，不得用于商业用途。如原作者对本仓库有异议，收到请求后将立即移除。
