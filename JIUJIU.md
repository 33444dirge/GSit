# GSit jiujiu 分支

基于原上游 [gecolay/GSit](https://github.com/gecolay/GSit) **3.8.0**，提交 `04b792026acf95b63f198691d85218a1023e2f92`。本分支版本为 `3.8.0-jiujiu.1`，保留原插件名 GSit、API、数据目录与作者署名。

此次将本地 `3.7.0-jiujiu.6` 的维护源码移植到完整上游源码树，不再要求旧的已打补丁 JAR，不再使用 PowerShell 或字节码合并构建器。原始本地工程已在工作区外备份。

## 上游 3.8.0 更新

- 新增 `crawltoggle` PlaceholderAPI 占位符和 Paper 更新通知可点击链接。
- 改善姿势、方块事件、偏好查询、MiniMessage 中的旧颜色与 RGB 解析。
- 修复重复药水效果下姿势玩家可见、`/crawl toggle` 误执行动作、GriefPrevention 领地判定和同一方块多人座椅/姿势追踪。
- 修复 Folia 实体清理的线程归属；完整保留上游各版本 NMS/数据包适配源码。

## 保留的定制

- `/dongzuo`、`/dz` 动作菜单、动作自动触发偏好以及座位切换限制。
- `/carry`、`/sq`、`/jiujiuqi`、`/baobao` 抱起、投掷、双 Shift 放下、互动开关、状态及配置重载。
- 原生传送前拆乘、区域归属校验、去重、冷却、容量限制与生命周期清理。
- 异步偏好存储、旧数据库表、UUID 校验、JDBC 资源释放与连接替换时的关闭处理。
- 更新检查的限时请求、响应上限、成功/失败缓存、查询合并、停用清理及实体调度通知，并合入 3.8.0 的可点击更新链接。
- 保留普通箱子动作菜单；互动菜单转接 `JiuJiuGUI` 的 `jiujiugui:jjgui`，配套配置位于 `external-config/plugins/JiuJiuGUI/menus/interaction.yml`。菜单不可用时保留帮助及互动控制命令。

自定义互动命令、权限和联动名称已统一为 JiuJiu 前缀。GSit 已有偏好表与玩家数据继续沿用；旧外部菜单的命令入口应改为 `/carry`。原主配置、数据库和自定义菜单不由构建覆盖。

## 构建

使用 JDK 25、项目自带的 Gradle wrapper。针对当前使用的 26.2、26.3 适配构建：

```bat
gradlew.bat build -PjiujiuVersions=v26_2,v26_3 --console=plain --max-workers=2 --no-configuration-cache
```

依赖缓存齐全后可加 `--offline`。成品为 `build/libs/GSit-3.8.0-jiujiu.1.jar`；子目录中为上游各发布渠道的描述文件变体。指定版本列表的成品只包含所选适配，不能当作覆盖上游全部 Minecraft 版本的通用包。

不指定 `jiujiuVersions` 时默认构建 26.2、26.3。完整上游各版本源码仍保留在 `mcv`，本轮没有验证旧 Minecraft 版本；本定制版要求 Java 25。26.2/26.3 的 Paper 输入在 `gradle.properties` 中固定版本，以便重建。

`check` 会运行维护行为回归与新增的 3.8.0 对齐回归，合计 **183 项检查通过**。另已核验插件描述、入口、两个版本适配、全部核心 class 与源码构建输出一致，且服务端 API 和测试库没有混入成品。`compile-libs` 和 `native-api` 均为仅编译/测试依赖，不打入最终 JAR；来源和 SHA256 见各目录的 `SOURCES.md`。

## 运行边界

Folia 骑人和抱起仍要求已有的 Luminol/Shiroha/Mizuki `EntityTeleportAsyncEvent` 能力；缺失时安全关闭这两项功能，保留其他动作。普通 Paper 路径使用 Paper 传送监听。升级插件请停服更换 JAR 并完整重启；`/carry reload` 仅重载抱起配置。此次是本地构建和行为回归，未在正式服或真实客户端重新验收。
