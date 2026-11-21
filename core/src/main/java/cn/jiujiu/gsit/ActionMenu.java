package cn.jiujiu.gsit;

import dev.geco.gsit.GSitMain;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.logging.Logger;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

/** GSit 3.7.0 incremental menu extension; no replacement for upstream pose code. */
public final class ActionMenu implements Listener, CommandExecutor {
    private static volatile ActionMenu instance;
    private final GSitMain plugin;
    private final YamlConfiguration config;
    private final Map<UUID, Boolean> preferences = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();
    private final Map<String, Button> buttons = new LinkedHashMap<>();
    private final ThreadPoolExecutor writer;
    private final Path data;
    private final Object settingsLock = new Object();
    private final Scheduler scheduler;
    private volatile boolean active = true;
    private static final Map<String,String> COMMANDS = Map.of("sit","gsit", "lay","glay", "belly","gbellyflop", "crawl","gcrawl", "spin","gspin", "legsup","glegsup");
    private record Button(int slot, Material material, String name, List<String> lore) {}
    private static final class View implements InventoryHolder {
        private Inventory inventory;
        public Inventory getInventory() { return inventory; }
    }
    private ActionMenu(GSitMain plugin) throws IOException {
        this.plugin = plugin;
        if (!new File(plugin.getDataFolder(), "jiujiu-menu.yml").exists()) plugin.saveResource("jiujiu-menu.yml", false);
        config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "jiujiu-menu.yml"));
        data = plugin.getDataFolder().toPath().resolve("jiujiu-auto.properties");
        if (Files.exists(data)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(data)) { p.load(in); }
            for (String key : p.stringPropertyNames()) {
                try { if (!Set.of("true","false").contains(p.getProperty(key))) throw new IllegalArgumentException(); preferences.put(UUID.fromString(key), Boolean.valueOf(p.getProperty(key))); }
                catch (IllegalArgumentException ex) { plugin.getLogger().warning("忽略无效的自动动作偏好：" + key); }
            }
        }
        writer = new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r -> { Thread t=new Thread(r,"JiuJiu-GSit-settings");t.setDaemon(true);return t; },new ThreadPoolExecutor.DiscardOldestPolicy());
        scheduler = new Scheduler(plugin);
        int size = rows() * 9;
        Set<Integer> used = new HashSet<>();
        for (String key : List.of("sit","lay","belly","crawl","spin","legsup","auto-off","auto-on","close")) {
            String path="buttons."+key;
            if (!config.getBoolean(path+".enabled",true)) continue;
            int slot=config.getInt(path+".slot", -1);
            if (slot<0 || slot>=size || !used.add(slot)) { plugin.getLogger().warning("跳过越界或重复槽位："+path); continue; }
            Material material=material(config.getString(path+".material"),Material.LIGHT_BLUE_DYE);
            buttons.put(key,new Button(slot,material,config.getString(path+".name",key),List.copyOf(config.getStringList(path+".lore"))));
        }
    }
    public static void enable(GSitMain p) {
        if (!p.isEnabled()) return;
        try {
            ActionMenu menu = new ActionMenu(p);
            instance=menu;
            Objects.requireNonNull(p.getCommand("dongzuo")).setExecutor(menu);
            p.getServer().getPluginManager().registerEvents(menu,p);
        } catch (Exception ex) { p.getLogger().log(java.util.logging.Level.SEVERE,"JiuJiu动作菜单启动失败",ex); disable(); }
    }
    public static void disable() {
        ActionMenu m=instance; instance=null;
        if(m==null) return;
        synchronized(m.settingsLock) { m.active=false; m.writer.shutdown(); }
        HandlerList.unregisterAll(m);
        // pending writes contain immutable snapshots, paths and logger only
        m.cooldown.clear(); m.preferences.clear();
    }
    /** Hook queried before upstream SQLite/cache path. Null preserves upstream behavior. */
    public static Boolean autoOverride(UUID id) {
        ActionMenu m=instance;
        return m==null || !m.active ? null : m.preferences.get(id);
    }
    /** Called by GSit's interact listener in the player's owning region. */
    public static boolean blockSeatSwitch(GSitMain plugin, Player player) {
        ActionMenu menu=instance;
        if(menu!=null && menu.config!=null && menu.config.getBoolean("allow-seat-switch",false))return false;
        return plugin.getSitService().getSeatByEntity(player)!=null;
    }
    private int rows() { return Math.max(1,Math.min(6,config.getInt("rows",5))); }
    private static Material material(String name,Material fallback) {
        Material m=name==null?null:Material.matchMaterial(name); return m!=null && m.isItem() ? m : fallback;
    }
    private static String color(String text) {
        Matcher matcher=Pattern.compile("&#([0-9a-fA-F]{6})").matcher(text);
        StringBuffer b=new StringBuffer();
        while(matcher.find()) { StringBuilder c=new StringBuilder("§x");for(char x:matcher.group(1).toCharArray())c.append('§').append(x);matcher.appendReplacement(b,Matcher.quoteReplacement(c.toString())); }
        matcher.appendTail(b); return ChatColor.translateAlternateColorCodes('&',b.toString());
    }
    private void message(Player p,String key) { p.sendMessage(color(config.getString("messages."+key,"&b操作完成"))); }
    private boolean allowed(Player p,String permission) { return permission.isBlank() || p.hasPermission(permission); }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player p)) { sender.sendMessage("请在游戏中使用 /dongzuo。");return true; }
        if(!active) return true;
        if(!config.getBoolean("enabled",true)) { message(p,"disabled");return true; }
        if(!allowed(p,config.getString("permission","gsit.menu"))) { message(p,"denied");return true; }
        scheduler.entity(p,()->open(p)); return true;
    }
    private void open(Player p) {
        if(!active || !p.isOnline()) return;
        View view=new View();
        view.inventory=Bukkit.createInventory(view,rows()*9,color(config.getString("title","&bJiuJiu动作")));
        ItemStack filler=new ItemStack(material(config.getString("background"),Material.WHITE_STAINED_GLASS_PANE));
        ItemMeta meta=filler.getItemMeta();meta.setDisplayName(" ");filler.setItemMeta(meta);
        for(int i=0;i<view.inventory.getSize();i++) view.inventory.setItem(i,filler);
        for(Button b:buttons.values()) {
            ItemStack item=new ItemStack(b.material()); ItemMeta im=item.getItemMeta();
            im.setDisplayName(color(b.name())); im.setLore(b.lore().stream().map(ActionMenu::color).toList()); item.setItemMeta(im);
            view.inventory.setItem(b.slot(),item);
        }
        p.openInventory(view.inventory);
    }
    @EventHandler public void click(InventoryClickEvent e) {
        if(!(e.getView().getTopInventory().getHolder() instanceof View)) return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p) || (e.getClick()!=ClickType.LEFT && e.getClick()!=ClickType.RIGHT)) return;
        if(!active || e.getRawSlot()<0 || e.getRawSlot()>=e.getView().getTopInventory().getSize()) return;
        if(!allowed(p,config.getString("permission","gsit.menu"))) { message(p,"denied");return; }
        String key=buttons.entrySet().stream().filter(x->x.getValue().slot()==e.getRawSlot()).map(Map.Entry::getKey).findFirst().orElse(null);
        if(key==null) return;
        long now=System.nanoTime(), delay=Math.max(0,Math.min(60000,config.getLong("cooldown-ms",400)))*1000000L;
        Long last=cooldown.get(p.getUniqueId());if(last!=null && now-last<delay)return;
        cooldown.put(p.getUniqueId(),now);
        Inventory inventory=e.getView().getTopInventory();
        scheduler.entity(p,()->{
            if(!active || !p.isOnline() || p.getOpenInventory().getTopInventory()!=inventory) return;
            p.closeInventory();
            if(key.equals("close"))return;
            if(key.startsWith("auto-")) {
                if(!allowed(p,config.getString("auto-permission","gsit.menu.auto"))) { message(p,"denied");return; }
                synchronized(settingsLock) {
                    if(!active)return;
                    preferences.put(p.getUniqueId(),key.equals("auto-on"));
                    saveSnapshot();
                }
                message(p,key);return;
            }
            String cmd=COMMANDS.get(key);if(cmd!=null)p.performCommand("gsit:"+cmd);
        });
    }
    @EventHandler public void drag(InventoryDragEvent e) { if(e.getView().getTopInventory().getHolder() instanceof View)e.setCancelled(true); }
    @EventHandler public void quit(PlayerQuitEvent e) { cooldown.remove(e.getPlayer().getUniqueId()); }
    private void saveSnapshot() {
        Properties snapshot=new Properties();preferences.forEach((id,on)->snapshot.setProperty(id.toString(),on.toString()));
        Path destination=data;Logger logger=plugin.getLogger();
        writer.execute(()->{
            Path temp=destination.resolveSibling(destination.getFileName()+".tmp");
            try {
                try(OutputStream out=Files.newOutputStream(temp)){snapshot.store(out,"JiuJiu automatic actions: per-player true/false");}
                try { Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch(AtomicMoveNotSupportedException ex){Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING);}
            }catch(IOException ex){logger.log(java.util.logging.Level.SEVERE,"保存自动动作偏好失败",ex);}
        });
    }
    /** Cached upstream Folia detection; only official scheduler APIs, >=1 tick. */
    private static final class Scheduler {
        private final GSitMain plugin;private final boolean folia;
        Scheduler(GSitMain p){plugin=p;folia=p.isFoliaServer();}
        void entity(Player player,Runnable task){
            if(folia)player.getScheduler().runDelayed(plugin,t->task.run(),null,1L);
            else Bukkit.getScheduler().runTaskLater(plugin,task,1L);
        }
    }
}
