package de.hoarder.sorting;

import de.hoarder.config.HoarderConfig;
import org.bukkit.Material;

import java.util.*;

/**
 * Manages the item categorization hierarchy
 */
public class ItemHierarchy {

    private final HoarderConfig config;

    public ItemHierarchy(HoarderConfig config) {
        this.config = config;
    }

    /**
     * Get the category path for an item
     */
    public String getCategory(Material material) {
        return config.getCategoryForMaterial(material);
    }

    /**
     * Get display name for a category
     */
    public String getDisplayName(String category) {
        return config.getDisplayName(category);
    }

    /**
     * Get all categories sorted (misc last)
     */
    public List<String> getSortedCategories() {
        return config.getSortedCategories();
    }

    /**
     * Get the depth of a category (number of levels)
     */
    public int getCategoryDepth(String category) {
        return config.getCategoryDepth(category);
    }

    /**
     * Get the parent category
     */
    public String getParentCategory(String category) {
        return config.getParentCategory(category);
    }

    /**
     * Check if one category is a parent of another
     */
    public boolean isParent(String parent, String child) {
        return config.isParentCategory(parent, child);
    }

    /**
     * Get the root category (first level)
     */
    public String getRootCategory(String category) {
        if (category == null || category.isEmpty()) return null;
        int slash = category.indexOf('/');
        return slash > 0 ? category.substring(0, slash) : category;
    }

    /**
     * Get subcategory at specified depth
     * e.g., getCategoryAtDepth("food/meat/beef/cooked", 2) returns "food/meat"
     */
    public String getCategoryAtDepth(String category, int depth) {
        if (category == null || depth <= 0) return null;

        String[] parts = category.split("/");
        if (depth >= parts.length) return category;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (i > 0) sb.append("/");
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /**
     * Find the common ancestor of two categories
     */
    public String getCommonAncestor(String cat1, String cat2) {
        if (cat1 == null || cat2 == null) return null;

        String[] parts1 = cat1.split("/");
        String[] parts2 = cat2.split("/");

        StringBuilder common = new StringBuilder();
        int minLen = Math.min(parts1.length, parts2.length);

        for (int i = 0; i < minLen; i++) {
            if (parts1[i].equals(parts2[i])) {
                if (i > 0) common.append("/");
                common.append(parts1[i]);
            } else {
                break;
            }
        }

        return common.length() > 0 ? common.toString() : null;
    }

    /**
     * Group items by category
     */
    public Map<String, List<Material>> groupByCategory(Collection<Material> materials) {
        Map<String, List<Material>> grouped = new HashMap<>();

        for (Material material : materials) {
            String category = getCategory(material);
            grouped.computeIfAbsent(category, k -> new ArrayList<>()).add(material);
        }

        return grouped;
    }

    /**
     * Get all direct children of a category
     */
    public List<String> getChildCategories(String parent) {
        List<String> children = new ArrayList<>();
        String prefix = parent + "/";

        for (String category : getSortedCategories()) {
            if (category.startsWith(prefix)) {
                // Get the direct child only
                String remainder = category.substring(prefix.length());
                int nextSlash = remainder.indexOf('/');
                String directChild = nextSlash > 0 ? prefix + remainder.substring(0, nextSlash) : category;

                if (!children.contains(directChild)) {
                    children.add(directChild);
                }
            }
        }

        return children;
    }

    /**
     * Check if a category should be split into subcategories
     * based on the items it contains
     */
    public Map<String, List<Material>> suggestSplit(String category, Collection<Material> items) {
        Map<String, List<Material>> split = new HashMap<>();

        for (Material item : items) {
            String itemCategory = getCategory(item);

            // If item's category is more specific than current category
            if (itemCategory.startsWith(category + "/") || itemCategory.equals(category)) {
                // Get the next level down
                String nextLevel;
                if (itemCategory.equals(category)) {
                    nextLevel = category;
                } else {
                    String remainder = itemCategory.substring(category.length() + 1);
                    int nextSlash = remainder.indexOf('/');
                    nextLevel = category + "/" + (nextSlash > 0 ? remainder.substring(0, nextSlash) : remainder);
                }

                split.computeIfAbsent(nextLevel, k -> new ArrayList<>()).add(item);
            }
        }

        return split;
    }
}
