package massim.scenario.city.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Container for items.
 */
public class ItemBox {

    private Map<Item, Integer> items = new HashMap<>();

    /**
     * Stores a number of items in this box.
     * @param item an item type
     * @param amount amount to store
     * @return whether the items could be stored
     */
    public boolean store(Item item, int amount){
        int stored = getItemCount(item);
        items.put(item, stored + amount);
        return true;
    }

    /**
     * Gets stored number of an item.
     * @param item an item type
     * @return the number of items stored in this box
     */
    public int getItemCount(Item item){
        Integer stored = items.get(item);
        return stored == null? 0: stored;
    }

    /**
     * Removes as many items as possible up to a given amount.
     * @param item an item type
     * @param amount maximum amount to remove
     * @return how many items could be removed
     */
    public int remove(Item item, int amount){
        int stored = getItemCount(item);
        int remove = Math.min(amount, stored);
        items.put(item, stored - remove);
        return remove;
    }

    /**
     * Removes a number of items if enough items are available (or none).
     * @param item an item type
     * @param amount how many items to remove
     * @return whether the items were removed or not
     */
    public boolean removeIfPossible(Item item, int amount){
        int stored = getItemCount(item);
        if (amount > stored) return false;
        items.put(item, stored - amount);
        return true;
    }

    /**
     * Adds (copies) all items from another box to this box. Items in the source box are not deleted.
     * @param box the box to take items from
     */
    public void addAll(ItemBox box) {
        box.getStoredTypes().forEach(item -> store(item, box.getItemCount(item)));
    }

    /**
     * @return a new set containing all item types currently stored in this box
     */
    public Set<Item> getStoredTypes(){
        return new HashSet<>(items.keySet());
    }
}