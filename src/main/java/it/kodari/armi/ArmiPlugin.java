package it.kodari.armi;

import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.XSound;
import org.bukkit.ChatColor;
import org.bukkit.entity.LlamaSpit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ArmiPlugin extends JavaPlugin implements Listener {
    private static final String MENU_TITLE = ChatColor.DARK_GRAY + "Armi";
    private static final double RANGE = 50.0;

    private final Map<String, Weapon> weapons = Map.of(
            "glock", new Weapon("Glock", "LEATHER_HORSE_ARMOR", 17, 1),
            "92fs", new Weapon("92FS", "LEATHER_HORSE_ARMOR", 15, 2),
            "px4", new Weapon("PX4", "LEATHER_HORSE_ARMOR", 17, 3)
    );
    private final Map<UUID, Long> nextShotAt = new HashMap<>();
    private final Map<UUID, BukkitTask> reloadTasks = new HashMap<>();

    private NamespacedKey weaponKey;
    private NamespacedKey loadedKey;
    private NamespacedKey ammoKey;
    private NamespacedKey magazineKey;
    private NamespacedKey hasMagazineKey;
    private NamespacedKey menuWeaponKey;
    private NamespacedKey armorKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        weaponKey = new NamespacedKey(this, "weapon");
        loadedKey = new NamespacedKey(this, "loaded_rounds");
        ammoKey = new NamespacedKey(this, "ammo_for");
        magazineKey = new NamespacedKey(this, "magazine_for");
        hasMagazineKey = new NamespacedKey(this, "has_magazine");
        menuWeaponKey = new NamespacedKey(this, "menu_weapon");
        armorKey = new NamespacedKey(this, "armor_upgrade");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("armi").setExecutor(this::handleCommand);
        getCommand("gap").setExecutor(this::handleCommand);
    }

    private boolean handleCommand(org.bukkit.command.CommandSender sender,
                                  org.bukkit.command.Command command,
                                  String label,
                                  String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Questo comando può essere usato solo da un giocatore.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("armi")) {
            openWeaponMenu(player);
            return true;
        }

        giveItem(player, createArmor("DIAMOND_CHESTPLATE", "diamond", "&bCorazza di diamante potenziata"));
        giveItem(player, createArmor("GOLDEN_CHESTPLATE", "gold", "&6Corazza d'oro potenziata"));
        player.sendMessage(color("&aHai ricevuto le due chestplate potenziate."));
        return true;
    }

    private void openWeaponMenu(Player player) {
        Inventory inventory = getServer().createInventory(null, 9, MENU_TITLE);
        inventory.setItem(2, createMenuWeapon("glock"));
        inventory.setItem(4, createMenuWeapon("92fs"));
        inventory.setItem(6, createMenuWeapon("px4"));
        player.openInventory(inventory);
    }

    private ItemStack createMenuWeapon(String id) {
        Weapon weapon = weapons.get(id);
        ItemStack item = createItem(weapon.material());
        ItemMeta meta = item.getItemMeta();
        meta.setCustomModelData(weapon.customModelData());
        meta.setDisplayName(color("&e" + weapon.name()));
        meta.setLore(List.of(color("&7Clicca per ricevere l'arma"),
                color("&7Caricatore: " + weapon.magazineSize() + " colpi")));
        meta.getPersistentDataContainer().set(menuWeaponKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!MENU_TITLE.equals(event.getView().getTitle())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        String id = getString(clicked, menuWeaponKey);
        if (id == null || !weapons.containsKey(id)) {
            return;
        }

        Weapon weapon = weapons.get(id);
        giveItem(player, createGun(id, weapon.magazineSize()));
        giveItem(player, createAmmoItem(id, weapon.magazineSize() * 3));
        player.sendMessage(color("&aHai ricevuto " + weapon.name() + " e munizioni."));
        player.closeInventory();
    }

    @EventHandler
    public void onWeaponUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        String magazineId = getString(held, magazineKey);
        if (magazineId != null && weapons.containsKey(magazineId)) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
                startMagazineReload(event.getPlayer(), magazineId);
            }
            return;
        }

        String id = getString(held, weaponKey);
        if (id == null || !weapons.containsKey(id)) {
            return;
        }

        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            shoot(event.getPlayer(), held, id);
        } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            unloadOrReload(event.getPlayer(), held, id);
        }
    }

    private void shoot(Player shooter, ItemStack gun, String id) {
        if (!shooter.isSneaking()) {
            shooter.sendMessage(color("&cDevi mirare tenendo premuto Shift per sparare."));
            return;
        }

        Weapon weapon = weapons.get(id);
        ItemMeta meta = gun.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (data.getOrDefault(hasMagazineKey, PersistentDataType.BYTE, (byte) 0) == 0) {
            shooter.sendMessage(color("&cNon c'è un caricatore nell'arma. Fai clic destro per inserirne uno."));
            return;
        }
        int loaded = data.getOrDefault(loadedKey, PersistentDataType.INTEGER, 0);
        if (loaded <= 0) {
            shooter.sendMessage(color("&cCaricatore vuoto. Fai clic destro per ricaricare."));
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = Math.max(1, getConfig().getInt("weapons." + id + ".fire-rate-ticks", 4)) * 50L;
        long allowedAt = nextShotAt.getOrDefault(shooter.getUniqueId(), 0L);
        if (now < allowedAt) {
            return;
        }
        nextShotAt.put(shooter.getUniqueId(), now + cooldown);

        data.set(loadedKey, PersistentDataType.INTEGER, loaded - 1);
        gun.setItemMeta(meta);
        updateGunLore(gun, id);
        shooter.getInventory().setItemInMainHand(gun);

        Location origin = shooter.getEyeLocation();
        Vector direction = origin.getDirection().normalize();
        LlamaSpit spit = shooter.getWorld().spawn(origin, LlamaSpit.class);
        spit.setShooter(shooter);
        spit.setGravity(false);
        spit.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, id);
        spit.setVelocity(direction.multiply(2.5));
        limitProjectileRange(spit, origin);
        XSound.matchXSound("ENTITY_PLAYER_ATTACK_SWEEP").ifPresent(sound -> sound.play(shooter));
    }

    private void limitProjectileRange(LlamaSpit spit, Location origin) {
        new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (!spit.isValid() || ++ticks > 100
                        || spit.getLocation().distanceSquared(origin) > RANGE * RANGE) {
                    spit.remove();
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    @EventHandler
    public void onLlamaSpitDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof LlamaSpit spit)) {
            return;
        }
        String id = spit.getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
        if (id != null && weapons.containsKey(id)) {
            event.setDamage(getConfig().getDouble("weapons." + id + ".damage", 6.0));
        }
    }

    private void unloadOrReload(Player player, ItemStack gun, String id) {
        ItemMeta meta = gun.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (data.getOrDefault(hasMagazineKey, PersistentDataType.BYTE, (byte) 0) != 0) {
            int loaded = data.getOrDefault(loadedKey, PersistentDataType.INTEGER, 0);
            giveItem(player, createMagazine(id, loaded));
            data.remove(hasMagazineKey);
            data.remove(loadedKey);
            gun.setItemMeta(meta);
            updateGunLore(gun, id);
            player.getInventory().setItemInMainHand(gun);
            player.sendMessage(color("&eCaricatore espulso: " + loaded + " colpi."));
            return;
        }

        int magazineSlot = findMagazineSlot(player, id);
        if (magazineSlot < 0) {
            player.sendMessage(color("&cNon hai un caricatore per questa arma nell'inventario."));
            return;
        }

        ItemStack magazine = player.getInventory().getItem(magazineSlot);
        int capacity = weapons.get(id).magazineSize();
        int loaded = Math.min(capacity, magazine.getItemMeta().getPersistentDataContainer()
                .getOrDefault(loadedKey, PersistentDataType.INTEGER, 0));
        if (magazine.getAmount() > 1) {
            magazine.setAmount(magazine.getAmount() - 1);
        } else {
            player.getInventory().setItem(magazineSlot, null);
        }

        meta = gun.getItemMeta();
        data = meta.getPersistentDataContainer();
        data.set(hasMagazineKey, PersistentDataType.BYTE, (byte) 1);
        data.set(loadedKey, PersistentDataType.INTEGER, loaded);
        gun.setItemMeta(meta);
        updateGunLore(gun, id);
        player.getInventory().setItemInMainHand(gun);
        player.sendMessage(color("&aCaricatore inserito con " + loaded + " colpi."));
    }

    private void startMagazineReload(Player player, String id) {
        UUID playerId = player.getUniqueId();
        if (reloadTasks.containsKey(playerId)) {
            return;
        }

        player.sendMessage(color("&aRicarica del caricatore avviata: un colpo ogni mezzo secondo."));
        BukkitRunnable reload = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    stopReload(playerId);
                    return;
                }

                ItemStack magazine = player.getInventory().getItemInMainHand();
                if (!id.equals(getString(magazine, magazineKey))) {
                    stopReload(playerId);
                    return;
                }

                ItemMeta meta = magazine.getItemMeta();
                PersistentDataContainer data = meta.getPersistentDataContainer();
                int loaded = data.getOrDefault(loadedKey, PersistentDataType.INTEGER, 0);
                int capacity = weapons.get(id).magazineSize();
                if (loaded >= capacity) {
                    stopReload(playerId);
                    return;
                }

                int ammoSlot = findAmmoSlot(player, id);
                if (ammoSlot < 0) {
                    player.sendMessage(color("&eRicarica terminata: non hai altre munizioni."));
                    stopReload(playerId);
                    return;
                }

                ItemStack ammo = player.getInventory().getItem(ammoSlot);
                if (ammo.getAmount() > 1) {
                    ammo.setAmount(ammo.getAmount() - 1);
                } else {
                    player.getInventory().setItem(ammoSlot, null);
                }
                data.set(loadedKey, PersistentDataType.INTEGER, loaded + 1);
                magazine.setItemMeta(meta);
                updateMagazineLore(magazine, id);
                player.getInventory().setItemInMainHand(magazine);

                if (loaded + 1 >= capacity) {
                    stopReload(playerId);
                }
            }
        };
        reloadTasks.put(playerId, reload.runTaskTimer(this, 10L, 10L));
    }

    private void stopReload(UUID playerId) {
        BukkitTask task = reloadTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private int findAmmoSlot(Player player, String id) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (id.equals(getString(contents[slot], ammoKey))) {
                return slot;
            }
        }
        return -1;
    }

    private int findMagazineSlot(Player player, String id) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (id.equals(getString(contents[slot], magazineKey))) {
                return slot;
            }
        }
        return -1;
    }

    private ItemStack createGun(String id, int loaded) {
        Weapon weapon = weapons.get(id);
        ItemStack item = createItem(weapon.material());
        ItemMeta meta = item.getItemMeta();
        meta.setCustomModelData(weapon.customModelData());
        meta.setDisplayName(color("&e" + weapon.name()));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(weaponKey, PersistentDataType.STRING, id);
        data.set(hasMagazineKey, PersistentDataType.BYTE, (byte) 1);
        data.set(loadedKey, PersistentDataType.INTEGER, loaded);
        item.setItemMeta(meta);
        updateGunLore(item, id);
        return item;
    }

    private void updateGunLore(ItemStack gun, String id) {
        ItemMeta meta = gun.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        boolean hasMagazine = data.getOrDefault(hasMagazineKey, PersistentDataType.BYTE, (byte) 0) != 0;
        int loaded = data.getOrDefault(loadedKey, PersistentDataType.INTEGER, 0);
        meta.setLore(List.of(color("&7Sinistro: spara mentre miri"),
                color("&7Shift: mira"), color("&7Destro: espelli/inserisci caricatore"),
                color(hasMagazine ? "&7Colpi: " + loaded + "/" + weapons.get(id).magazineSize() : "&7Caricatore: assente")));
        gun.setItemMeta(meta);
    }

    private ItemStack createMagazine(String id, int loaded) {
        ItemStack item = createItem("IRON_NUGGET");
        ItemMeta meta = item.getItemMeta();
        meta.setMaxStackSize(1);
        meta.setDisplayName(color("&fCaricatore " + weapons.get(id).name()));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(magazineKey, PersistentDataType.STRING, id);
        data.set(loadedKey, PersistentDataType.INTEGER, loaded);
        item.setItemMeta(meta);
        updateMagazineLore(item, id);
        return item;
    }

    private void updateMagazineLore(ItemStack magazine, String id) {
        ItemMeta meta = magazine.getItemMeta();
        int loaded = meta.getPersistentDataContainer().getOrDefault(loadedKey, PersistentDataType.INTEGER, 0);
        meta.setLore(List.of(color("&7Colpi: " + loaded + "/" + weapons.get(id).magazineSize()),
                color("&7Destro: carica un colpo"),
                color("&7Tieni munizioni compatibili nell'inventario")));
        magazine.setItemMeta(meta);
    }

    private ItemStack createAmmoItem(String id, int amount) {
        ItemStack item = createItem("PAPER");
        item.setAmount(amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&fMunizioni " + weapons.get(id).name()));
        meta.setLore(List.of(color("&7Tieni queste munizioni nell'inventario"),
                color("&7Tieni il caricatore in mano e fai clic destro")));
        meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createArmor(String material, String upgrade, String name) {
        ItemStack item = createItem(material);
        ItemMeta meta = item.getItemMeta();
        double reduction = getConfig().getDouble("armor." + upgrade + "-damage-reduction", 0.0);
        meta.setDisplayName(color(name));
        meta.setLore(List.of(color("&7Riduzione danni: &a" + Math.round(reduction * 100) + "%")));
        meta.getPersistentDataContainer().set(armorKey, PersistentDataType.STRING, upgrade);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onArmorDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack chestplate = player.getInventory().getChestplate();
        String upgrade = getString(chestplate, armorKey);
        if (upgrade == null) {
            return;
        }
        double reduction = getConfig().getDouble("armor." + upgrade + "-damage-reduction", 0.0);
        reduction = Math.max(0.0, Math.min(1.0, reduction));
        event.setDamage(event.getDamage() * (1.0 - reduction));
    }

    private String getString(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private ItemStack createItem(String material) {
        return XMaterial.matchXMaterial(material)
                .map(XMaterial::parseItem)
                .orElseThrow(() -> new IllegalStateException("Materiale non supportato: " + material));
    }

    private void giveItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private record Weapon(String name, String material, int magazineSize, int customModelData) {
    }
}