package mc506lw.openShulker;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class OpenShulker extends JavaPlugin implements Listener {

    private final NamespacedKey SHULKER_UUID_KEY = new NamespacedKey(this, "open_shulker_id");
    private final Map<UUID, ShulkerSession> openShulkers = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        ShulkerSession[] sessions = openShulkers.values().toArray(new ShulkerSession[0]);
        for (ShulkerSession session : sessions) {
            Player player = getServer().getPlayer(session.playerId);
            if (player != null) {
                player.closeInventory();
            }
        }
        openShulkers.clear();
    }

    private static class ShulkerSession {
        final UUID playerId;
        final int slot;
        final UUID itemUuid;
        Location deathLocation;

        ShulkerSession(UUID playerId, int slot, UUID itemUuid) {
            this.playerId = playerId;
            this.slot = slot;
            this.itemUuid = itemUuid;
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (item == null || !isShulkerBox(item.getType())) {
            return;
        }

        // Sneaking allows normal placement or block interaction
        if (player.isSneaking()) {
            return;
        }

        // Refuse to open stacked shulker boxes
        if (item.getAmount() > 1) {
            return;
        }

        event.setCancelled(true);

        if (openShulkers.containsKey(player.getUniqueId())) {
            return;
        }

        if (!(item.getItemMeta() instanceof BlockStateMeta blockStateMeta)) {
            return;
        }

        if (!(blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return;
        }

        // Stamp a unique UUID into the item's PDC for exact tracking
        UUID itemUuid = UUID.randomUUID();
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(SHULKER_UUID_KEY, PersistentDataType.STRING, itemUuid.toString());
        item.setItemMeta(meta);

        // Use the item's display name (custom or default localized name) as the GUI title
        Inventory inventory = Bukkit.createInventory(player, InventoryType.SHULKER_BOX, item.displayName());

        // Copy items from the shulker box into the GUI
        inventory.setContents(shulkerBox.getInventory().getContents());

        // Determine the slot: main hand or offhand
        int slot;
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            slot = 40; // Offhand slot index
        } else {
            slot = player.getInventory().getHeldItemSlot(); // 0-8 (hotbar)
        }

        openShulkers.put(player.getUniqueId(), new ShulkerSession(player.getUniqueId(), slot, itemUuid));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        ShulkerSession session = openShulkers.remove(player.getUniqueId());
        if (session == null) {
            return;
        }

        // Find the shulker by PDC UUID - immune to slot changes or same-type duplicates
        ItemStack shulkerItem = findShulkerByUuid(player, session.itemUuid);

        if (shulkerItem == null) {
            // Not in inventory - check nearby dropped items (e.g. player died)
            shulkerItem = findDroppedShulkerByUuid(
                    session.deathLocation != null ? session.deathLocation : player.getLocation(),
                    session.itemUuid);
        }

        if (shulkerItem == null) {
            // Shulker box truly missing: silently discard GUI contents to prevent dupes.
            return;
        }

        if (!(shulkerItem.getItemMeta() instanceof BlockStateMeta blockStateMeta)) {
            return;
        }

        if (!(blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return;
        }

        // Save items back into the shulker box
        shulkerBox.getInventory().setContents(event.getInventory().getContents());
        blockStateMeta.setBlockState(shulkerBox);

        // Remove the temporary UUID before saving
        PersistentDataContainer pdc = blockStateMeta.getPersistentDataContainer();
        pdc.remove(SHULKER_UUID_KEY);
        shulkerItem.setItemMeta(blockStateMeta);

        player.updateInventory();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ShulkerSession session = openShulkers.get(player.getUniqueId());
        if (session == null) return;

        // Prevent moving the shulker box from its slot in the player's inventory
        if (event.getClickedInventory() == event.getView().getBottomInventory()) {
            if (event.getSlot() == session.slot) {
                event.setCancelled(true);
                return;
            }
        }

        // Prevent hotbar number key from swapping the shulker slot
        // When clicking in the top inventory, hotbarButton targets the hotbar slot
        // where the shulker currently sits; when clicking in the bottom inventory,
        // slot comparison catches it as well.
        if (event.getClick() == ClickType.NUMBER_KEY) {
            // hotbarButton always refers to a player hotbar slot (0-8)
            if (event.getHotbarButton() == session.slot) {
                event.setCancelled(true);
                return;
            }
            // When clicking in the bottom inventory, the slot could match
            if (event.getClickedInventory() == event.getView().getBottomInventory()
                    && event.getSlot() == session.slot) {
                event.setCancelled(true);
                return;
            }
        }

        // Prevent moving a shulker box via cursor into the player's inventory slots
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir() && isShulkerBox(cursor.getType())) {
            if (event.getClickedInventory() == event.getView().getBottomInventory()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ShulkerSession session = openShulkers.get(player.getUniqueId());
        if (session == null) return;

        // Calculate the raw slot of the shulker in the full inventory view
        int topSize = event.getView().getTopInventory().getSize();
        int rawShulkerSlot = topSize + session.slot;

        // Prevent drag operations that target the shulker slot
        if (event.getRawSlots().contains(rawShulkerSlot)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ShulkerSession session = openShulkers.get(player.getUniqueId());
        if (session == null) return;

        ItemStack dropped = event.getItemDrop().getItemStack();
        if (isShulkerBox(dropped.getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (openShulkers.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        ShulkerSession session = openShulkers.get(player.getUniqueId());
        if (session == null) return;

        // Record death location so we can find the dropped shulker in InventoryCloseEvent
        session.deathLocation = player.getLocation();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (openShulkers.containsKey(player.getUniqueId())) {
            player.closeInventory(); // Triggers InventoryCloseEvent synchronously to save items
        }
    }

    /**
     * Scan the player's full inventory for an ItemStack whose PDC has the given UUID.
     */
    private ItemStack findShulkerByUuid(Player player, UUID targetUuid) {
        String targetStr = targetUuid.toString();
        for (int i = 0; i < 41; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null) continue;
            if (!isShulkerBox(item.getType())) continue;
            if (!(item.getItemMeta() instanceof BlockStateMeta meta)) continue;
            String stored = meta.getPersistentDataContainer()
                    .get(SHULKER_UUID_KEY, PersistentDataType.STRING);
            if (targetStr.equals(stored)) {
                return item;
            }
        }
        return null;
    }

    /**
     * Scan nearby dropped Item entities for a shulker box whose PDC has the given UUID.
     */
    private ItemStack findDroppedShulkerByUuid(Location location, UUID targetUuid) {
        if (location.getWorld() == null) return null;
        String targetStr = targetUuid.toString();
        // Scan within 10 blocks of the given location
        for (var entity : location.getWorld().getNearbyEntities(location, 10, 10, 10)) {
            if (!(entity instanceof Item itemEntity)) continue;
            ItemStack stack = itemEntity.getItemStack();
            if (!isShulkerBox(stack.getType())) continue;
            if (!(stack.getItemMeta() instanceof BlockStateMeta meta)) continue;
            String stored = meta.getPersistentDataContainer()
                    .get(SHULKER_UUID_KEY, PersistentDataType.STRING);
            if (targetStr.equals(stored)) {
                // Modify the entity's ItemStack directly
                return itemEntity.getItemStack();
            }
        }
        return null;
    }

    private boolean isShulkerBox(Material material) {
        return material.name().endsWith("SHULKER_BOX");
    }
}
