package cn.jiujiu.gsit.carry;

import dev.geco.gsit.GSitMain;
import dev.geco.gsit.api.event.PrePlayerPlayerSitEvent;
import dev.geco.gsit.model.StopReason;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

/** GSit-owned carry extension. No second plugin, riding implementation, or inventory UI. */
public final class CarryModule implements Listener, CommandExecutor, TabCompleter {
    private static volatile CarryModule instance;
    private final GSitMain plugin;
    final TaskScheduler scheduler;
    final CarryService service;
    private volatile Settings settings;
    private final InteractionGate inputs = new InteractionGate();
    private final AtomicBoolean loading = new AtomicBoolean();
    private final Set<UUID> positionSync = ConcurrentHashMap.newKeySet();
    private final Set<UUID> teleportDetach = ConcurrentHashMap.newKeySet();
    private final List<Listener> bridges = new ArrayList<>();
    private final java.util.function.LongSupplier clock = System::nanoTime;
    private final NamespacedKey legacyPreference = Objects.requireNonNull(NamespacedKey.fromString("jiujiuqi:accept-interaction"));
    private final NamespacedKey migrated = Objects.requireNonNull(NamespacedKey.fromString("gsit:jiujiu-interaction-migrated"));
    private volatile boolean active = true;
    private final boolean nativeTeleportHook;
    private static final LegacyComponentSerializer COLORS = LegacyComponentSerializer.legacyAmpersand();

    CarryModule(GSitMain plugin, boolean nativeTeleportHook) throws Exception {
        this.plugin = plugin;
        this.nativeTeleportHook = nativeTeleportHook;
        scheduler = new TaskScheduler(plugin);
        if (!new File(plugin.getDataFolder(), "jiujiu-carry.yml").exists()) plugin.saveResource("jiujiu-carry.yml", false);
        settings = readSettings();
        service = new CarryService(this, scheduler, clock);
    }

    public static void enable(GSitMain plugin) {
        if (!plugin.isEnabled()) return;
        if (plugin.getServer().getPluginManager().getPlugin("JiuJiuqi") != null
                || plugin.getServer().getPluginManager().getPlugin("JiuJiuCarry") != null) {
            throw new IllegalStateException("请先移出旧 JiuJiuqi / JiuJiuCarry JAR，完整重启后再使用合并版。");
        }
        boolean nativeHook;
        try { Class.forName("me.earthme.luminol.api.entity.EntityTeleportAsyncEvent"); nativeHook = true; }
        catch (ClassNotFoundException e) { nativeHook = false; }
        try {
            CarryModule module = new CarryModule(plugin, nativeHook);
            instance = module;
            plugin.getServer().getPluginManager().registerEvents(module, plugin);
            if (nativeHook) module.bridges.add(new NativeTeleportListener(module));
            else if (!plugin.isFoliaServer()) module.bridges.add(new PaperTeleportListener(module));
            else plugin.getLogger().severe("当前 Folia 核心缺少已核验的传送前事件，已安全禁用骑人和抱起；坐下等原动作不受影响。");
            module.bridges.forEach(listener -> plugin.getServer().getPluginManager().registerEvents(listener, plugin));
            PluginCommand command = Objects.requireNonNull(plugin.getCommand("jiujiuqi"));
            command.setExecutor(module); command.setTabCompleter(module);
            plugin.getLogger().info("已合并抱起与投掷，普通骑乘由 GSit 处理。");
        } catch (Exception e) {
            disable();
            throw new IllegalStateException("抱起模块启动失败", e);
        }
    }

    public static void disable() {
        CarryModule m = instance; instance = null;
        if (m == null) return;
        m.active = false;
        m.service.close(); m.scheduler.cancelAll();
        m.inputs.clear(); m.positionSync.clear(); m.teleportDetach.clear();
        HandlerList.unregisterAll(m);
        m.bridges.forEach(HandlerList::unregisterAll); m.bridges.clear();
    }

    public static boolean owns(Entity entity) {
        CarryModule m = instance;
        return m != null && m.active && m.scheduler.owns(entity);
    }
    public static boolean blockUnsafeReload(GSitMain plugin, CommandSender sender) {
        if (!plugin.isFoliaServer()) return false;
        sender.sendMessage("§e请完整重启；抱起配置用 /carry reload。");
        return true;
    }
    public static boolean allowGSit(Player rider, Player target) {
        CarryModule m = instance;
        if (m == null || !m.active || !m.teleportSafe() || !m.scheduler.owns(rider) || !m.scheduler.owns(target)) return false;
        if (rider.isSneaking() || m.busy(rider) || m.busy(target)
                || m.mountProtected(rider.getUniqueId()) || m.mountProtected(target.getUniqueId())) return false;
        return m.accepts(rider) && m.accepts(target);
    }
    public static boolean blockInteraction(Player player) {
        CarryModule m = instance;
        return m != null && (m.busy(player) || m.mountProtected(player.getUniqueId()));
    }
    public static void beginSafetyDetach(Set<UUID> ids) {
        CarryModule m = instance; if (m != null) { m.teleportDetach.addAll(ids); ids.forEach(m::protectMounts); }
    }
    public static void endSafetyDetach(Set<UUID> ids) {
        CarryModule m = instance; if (m != null) m.teleportDetach.removeAll(ids);
    }
    private boolean teleportSafe() { return nativeTeleportHook || !plugin.isFoliaServer(); }
    private boolean busy(Player p) { return service.held(p) || service.tracked(p); }
    Settings settings() { return settings; }
    Logger getLogger() { return plugin.getLogger(); }
    private Settings readSettings() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File(plugin.getDataFolder(), "jiujiu-carry.yml"));
        return new Settings(yaml, s -> getLogger().warning(s));
    }
    void message(Player p, String key) {
        String text = settings.text(key);
        if (!text.isBlank()) p.sendMessage(COLORS.deserialize(settings.yaml.getString("messages.prefix", "") + text));
    }
    private boolean legacyAllows(Player p) {
        Byte old = p.getPersistentDataContainer().get(legacyPreference, PersistentDataType.BYTE);
        return p.getPersistentDataContainer().has(migrated) || old == null || old != 0;
    }
    boolean accepts(Player p) {
        return legacyAllows(p) && plugin.getToggleService().canPlayerUsePlayerSit(p.getUniqueId());
    }
    private void migratePreference(Player p) {
        migratePreference(p, 0);
    }
    private void migratePreference(Player p, int attempt) {
        if (!active || !p.isOnline()) return;
        if (p.getPersistentDataContainer().has(migrated)) return;
        Byte old = p.getPersistentDataContainer().get(legacyPreference, PersistentDataType.BYTE);
        // Old opt-out is retained; never turn on a GSit opt-out as a side effect of migration.
        if (old != null && old == 0) {
            if (!plugin.getToggleService().isReady()) {
                if (attempt < 200) scheduler.later(p, () -> migratePreference(p, attempt + 1), () -> {});
                return;
            }
            if (!plugin.getToggleService().trySetPlayerCanUsePlayerSit(p.getUniqueId(), false)) return;
        }
        p.getPersistentDataContainer().set(migrated, PersistentDataType.BYTE, (byte)1);
    }
    private void acceptance(Player p, boolean value) {
        if (!plugin.getToggleService().trySetPlayerCanUsePlayerSit(p.getUniqueId(), value)) { message(p, "storage-wait"); return; }
        p.getPersistentDataContainer().set(migrated, PersistentDataType.BYTE, (byte)1);
        if (!value) { service.endParticipant(p); plugin.getPlayerSitService().stopPlayerSit(p, StopReason.PLUGIN); }
        message(p, value ? "player-on" : "player-off");
    }
    void pickupConsumed(Player p) { inputs.mark(p.getUniqueId(), clock.getAsLong(), p.getTicksLived()); }
    void protectMounts(UUID id) { inputs.protectMounts(id, clock.getAsLong(), settings.remountGuardMs); }
    boolean mountProtected(UUID id) { return inputs.mountProtected(id, clock.getAsLong()); }
    void holdingHint(Player p) { /* Successful pickup already sends one short hint. */ }
    void beginPositionSync(Player p) { positionSync.add(p.getUniqueId()); }
    void endPositionSync(UUID id) { positionSync.remove(id); }

    public void beforeTeleport(Entity entity, Location destination) {
        if (!active || !(entity instanceof Player p) || !scheduler.owns(p)) return;
        if (positionSync.contains(p.getUniqueId()) && service.syncingPassenger(p.getUniqueId())
                && !p.isInsideVehicle() && p.getPassengers().isEmpty()
                && destination.getWorld() == p.getWorld() && p.getLocation().distanceSquared(destination) < 0.000001) return;
        if (!busy(p) && !plugin.getPlayerSitService().isPlayerInPlayerSitStack(p)) return;
        UUID id = p.getUniqueId(); teleportDetach.add(id);
        try {
            boolean detached = service.beforeTeleport(p);
            boolean split = plugin.getPlayerSitService().splitStackForTeleport(p);
            protectMounts(id);
            if (!detached || !split) getLogger().severe("传送拆乘未完成：" + id + "，请检查区域归属和第三方下乘监听器。");
        } finally { teleportDetach.remove(id); }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void beforeCommand(PlayerCommandPreprocessEvent event) {
        if (!settings.yaml.getBoolean("safety.detach-before-commands", true)) return;
        String root = event.getMessage().split("\\s+", 2)[0].replaceFirst("^/", "").toLowerCase(Locale.ROOT);
        if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);
        if (Set.of("carry", "baobao", "jiujiuqi", "sq", "jiujiucarry", "hr", "ride", "headride").contains(root)) return;
        Player p = event.getPlayer(); if (scheduler.owns(p)) beforeTeleport(p, p.getLocation());
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void beforeInventory(InventoryOpenEvent event) {
        if (settings.yaml.getBoolean("safety.detach-before-menus", true) && event.getPlayer() instanceof Player p && scheduler.owns(p)) beforeTeleport(p, p.getLocation());
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void beforeInventoryClick(InventoryClickEvent event) {
        if (settings.yaml.getBoolean("safety.detach-before-menus", true) && event.getWhoClicked() instanceof Player p && scheduler.owns(p)) beforeTeleport(p, p.getLocation());
    }

    // AtEntity shares its HandlerList with InteractEntity; register exactly one carry listener.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent event) {
        Player p = event.getPlayer();
        if (!active || !teleportSafe() || !scheduler.owns(p) || event.getHand() != EquipmentSlot.HAND) return;
        Entity target = event.getRightClicked();
        if (!(target instanceof Player) && !(target instanceof Mob) && !service.held(p)) return;
        // Ordinary player riding remains GSit's gesture; mobs honor the configured sneak switch.
        if (!service.held(p) && !p.isSneaking() && (target instanceof Player || settings.sneakPickup)) return;
        if (!service.held(p) && (!settings.enabled || !settings.playerHandsAllowed(p, true))) return;
        if (duplicate(p)) { event.setCancelled(true); return; }
        if (service.held(p)) { pickupConsumed(p); service.throwHeld(p); event.setCancelled(true); return; }
        pickupConsumed(p);
        if (target instanceof Player other) {
            // Reserve this gesture, including a denied pickup, so GSit never interprets it as ordinary riding.
            event.setCancelled(true);
            if (plugin.getPlayerSitService().isPlayerInPlayerSitStack(p)
                    || plugin.getPlayerSitService().isPlayerInPlayerSitStack(other)) { message(p, "player-busy"); return; }
            service.mountPlayers(p, other);
        } else if (target instanceof Mob && (!settings.sneakPickup || p.isSneaking())) {
            if (service.pickup(p, target)) event.setCancelled(true);
        }
    }
    private boolean duplicate(Player p) {
        boolean repeat = inputs.recent(p.getUniqueId(), clock.getAsLong(), p.getTicksLived(), settings.clickDebounceMs);
        if (repeat) pickupConsumed(p);
        return repeat;
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void click(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        if (!scheduler.owns(p) || !service.held(p) || event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && event.useInteractedBlock() == Event.Result.DENY) return;
        event.setCancelled(true);
        if (!duplicate(p)) { pickupConsumed(p); service.throwHeld(p); }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void sneak(PlayerToggleSneakEvent event) { if (event.isSneaking() && scheduler.owns(event.getPlayer())) service.sneak(event.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preSit(PrePlayerPlayerSitEvent event) {
        if (!allowGSit(event.getPlayer(), event.getTarget())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void mount(EntityMountEvent event) {
        // Only protect active carry participants and their short release window; never reject a free GSit stack.
        if (event.getEntity() instanceof Player rider && event.getMount() instanceof Player target) {
            String reason = service.mountBlockReason(rider, target);
            if (reason != null) event.setCancelled(true);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void safetyDismount(EntityDismountEvent event) {
        // The native pre-teleport event is informational. A narrowly scoped safety detach must finish before it returns.
        if (teleportDetach.isEmpty() || !scheduler.owns(event.getEntity()) || !scheduler.owns(event.getDismounted())) return;
        UUID rider = event.getEntity().getUniqueId(), vehicle = event.getDismounted().getUniqueId();
        if (teleportDetach.contains(rider) || teleportDetach.contains(vehicle)) event.setCancelled(false);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void dismount(EntityDismountEvent event) {
        CarryService.Session s = service.carried.get(event.getEntity().getUniqueId());
        if (s != null && !s.exiting && event.getDismounted() == s.player) scheduler.later(s.mob, () -> {
            if (!s.closed.get() && s.mob.getVehicle() != s.player) service.dismounted(s);
        }, () -> service.forget(s));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent event) {
        if (!settings.protectCarried) return;
        Entity source = event.getDamageSource().getCausingEntity();
        if (!(event.getDamager() instanceof Player) && service.tracked(event.getDamager())
                || source != null && !(source instanceof Player) && service.tracked(source)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void injured(EntityDamageEvent event) {
        if (event.getFinalDamage() > 0 && settings.releaseDamage && event.getEntity() instanceof Player p) service.drop(p, "auto-released");
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void target(EntityTargetLivingEntityEvent event) { if (settings.protectCarried && service.tracked(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void prime(ExplosionPrimeEvent event) { if (service.tracked(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void projectile(ProjectileLaunchEvent event) {
        if (settings.protectCarried && event.getEntity().getShooter() instanceof Entity source && !(source instanceof Player) && service.tracked(source)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spell(EntitySpellCastEvent event) { if (settings.protectCarried && service.tracked(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void mobTeleport(EntityTeleportEvent event) { if (!(event.getEntity() instanceof Player) && service.tracked(event.getEntity())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        service.endParticipant(event.getPlayer()); service.removed(event.getPlayer());
        UUID id = event.getPlayer().getUniqueId(); inputs.remove(id); positionSync.remove(id);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void death(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player p) service.endParticipant(p);
        CarryService.Session s = service.carried.get(event.getEntity().getUniqueId());
        if (s != null) service.release(s, null);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { migratePreference(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void remove(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Player p && service.syncingPassenger(p.getUniqueId())) return;
        service.removed(event.getEntity());
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!active) return true;
        if (sender instanceof Player p && !scheduler.owns(p)) {
            String[] copy = args.clone(); scheduler.later(p, () -> onCommand(sender, command, label, copy), () -> {}); return true;
        }
        String action = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("reload")) {
            if (!settings.adminPermission.isBlank() && !sender.hasPermission(settings.adminPermission)) { sender.sendMessage("§c没有权限。"); return true; }
            reload(sender); return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("用法：carry reload"); return true; }
        if (!settings.playerPermission(p, "menu")) { message(p, "no-permission"); return true; }
        switch (action) {
            case "menu" -> openMenu(p);
            case "on", "off" -> acceptance(p, action.equals("on"));
            case "toggle" -> acceptance(p, !accepts(p));
            case "drop" -> service.drop(p, "put-down");
            case "down" -> { service.down(p); plugin.getPlayerSitService().stopPlayerSit(p, StopReason.GET_UP); }
            case "status" -> p.sendMessage(COLORS.deserialize(settings.yaml.getString("messages.status", "&b互动：{state} &7| 抱起：{holding}")
                    .replace("{state}", accepts(p) ? "开" : "关").replace("{holding}", service.held(p) ? "有" : "无")));
            default -> message(p, "help");
        }
        return true;
    }
    private void openMenu(Player player) {
        if (!settings.menuEnabled) { message(player, "menu-unavailable"); return; }
        var gui = plugin.getServer().getPluginManager().getPlugin("JiuJiuLib");
        PluginCommand entry = plugin.getServer().getPluginCommand("jiujiulib:jjgui");
        if (gui == null || !gui.isEnabled() || entry == null || entry.getPlugin() != gui
                || !player.performCommand("jiujiulib:jjgui open " + settings.menuId)) {
            message(player, "menu-unavailable");
        }
    }
    private void reload(CommandSender sender) {
        if (!loading.compareAndSet(false, true)) return;
        scheduler.async(() -> {
            String key;
            try { Settings next = readSettings(); if (active) settings = next; key = "reloaded"; }
            catch (Exception e) { getLogger().warning("配置重载失败：" + e.getMessage()); key = "reload-failed"; }
            finally { loading.set(false); }
            if (!active) return;
            if (sender instanceof Player p) { String result = key; scheduler.later(p, () -> message(p, result), () -> {}); }
            else sender.sendMessage(key.equals("reloaded") ? "抱起配置已重载。" : "配置有误，保留旧配置。");
        });
    }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String label, String[] args) {
        if (args.length != 1) return List.of();
        return List.of("menu", "help", "on", "off", "toggle", "drop", "down", "status", "reload").stream()
                .filter(v -> v.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}
