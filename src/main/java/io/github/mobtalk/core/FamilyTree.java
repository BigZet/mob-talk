package io.github.mobtalk.core;

import java.util.*;

/** One family tree per world (docs/mca-social-spec.md §5.1), in the world memory: villagers and the player as nodes with sex, parents,
 * children, spouse and whether they died. Only real links are kept — a villager met grown has no recorded parents, so strangers are
 * never relatives. Blood relation means a common ancestor, counting the two themselves (so a parent and a child are related too).
 * Pure Java; every mutation runs under the store lock. */
public final class FamilyTree {
    /** How many generations up the search for a common ancestor goes. */
    public static final int DEPTH = 5;

    static final class Node {
        String id, name = "", gender = "";
        boolean player, dead, widowed;
        String father = "", mother = "", spouse = "";
        List<String> children = new ArrayList<>();
        Node copy() {
            Node c = new Node(); c.id = id; c.name = name; c.gender = gender; c.player = player; c.dead = dead; c.widowed = widowed;
            c.father = father; c.mother = mother; c.spouse = spouse; c.children = new ArrayList<>(children); return c;
        }
    }
    /** One relative as the prompt reads it: who, how related (the word already in the relative's sex), alive or not. */
    public record Kin(UUID id, String name, String word, boolean player, boolean dead) { }
    public enum Link { SPOUSE, PARENT, CHILD, SIBLING }

    Map<String, Node> nodes = new LinkedHashMap<>();

    /** The node of this creature or player, created on first use; a known name or sex is refreshed. */
    Node node(UUID id, String name, Gender gender, boolean player) {
        Node node = nodes.computeIfAbsent(id.toString(), key -> { Node n = new Node(); n.id = key; return n; });
        if (name != null && !name.isBlank()) node.name = TextBounds.characters(name, 60);
        if (gender != null) node.gender = gender.name();
        node.player |= player;
        return node;
    }
    private Node get(UUID id) { return id == null ? null : nodes.get(id.toString()); }
    public boolean known(UUID id) { return get(id) != null; }

    /** The living spouse, or null. */
    public UUID spouse(UUID id) {
        Node node = get(id);
        if (node == null || node.spouse.isBlank()) return null;
        Node other = nodes.get(node.spouse);
        return other == null || other.dead ? null : UUID.fromString(node.spouse);
    }
    public boolean married(UUID id) { return spouse(id) != null; }
    public boolean widowed(UUID id) { Node node = get(id); return node != null && node.widowed; }
    public boolean dead(UUID id) { Node node = get(id); return node != null && node.dead; }

    /** Two become spouses; whoever they were married to before is left (a divorce for a living one). */
    void marry(UUID a, String nameA, Gender genderA, boolean playerA, UUID b, String nameB, Gender genderB, boolean playerB) {
        divorce(a); divorce(b);
        Node x = node(a, nameA, genderA, playerA), y = node(b, nameB, genderB, playerB);
        x.spouse = y.id; y.spouse = x.id; x.widowed = false; y.widowed = false;
    }
    /** Ends the marriage of {@code id} on both sides; returns the former spouse or null. */
    UUID divorce(UUID id) {
        Node node = get(id);
        if (node == null || node.spouse.isBlank()) return null;
        Node other = nodes.get(node.spouse);
        String former = node.spouse;
        node.spouse = "";
        if (other != null && other.spouse.equals(node.id)) other.spouse = "";
        return UUID.fromString(former);
    }
    /** A child of {@code mother} and {@code father} (either may be the player); both parents must already be nodes or are created bare. */
    void addChild(UUID child, String name, Gender gender, UUID mother, UUID father) {
        Node node = node(child, name, gender, false);
        if (mother != null) { Node m = nodes.computeIfAbsent(mother.toString(), key -> { Node n = new Node(); n.id = key; return n; }); node.mother = m.id; if (!m.children.contains(node.id)) m.children.add(node.id); }
        if (father != null) { Node f = nodes.computeIfAbsent(father.toString(), key -> { Node n = new Node(); n.id = key; return n; }); node.father = f.id; if (!f.children.contains(node.id)) f.children.add(node.id); }
    }
    /** {@code id} died: its spouse becomes a widow(er). Returns the relatives to grieve, with how they were linked. */
    Map<UUID, Link> died(UUID id) {
        Node node = get(id);
        if (node == null || node.dead) return Map.of();
        node.dead = true;
        Map<UUID, Link> grieving = new LinkedHashMap<>();
        for (Map.Entry<UUID, Link> entry : links(id).entrySet()) if (!dead(entry.getKey())) grieving.put(entry.getKey(), entry.getValue());
        if (!node.spouse.isBlank()) { Node spouse = nodes.get(node.spouse); if (spouse != null && spouse.spouse.equals(node.id)) spouse.widowed = true; }
        return grieving;
    }

    /** Parents, children, siblings (one common parent is enough) and the spouse of {@code id}, alive or dead. */
    public Map<UUID, Link> links(UUID id) {
        Node node = get(id);
        Map<UUID, Link> links = new LinkedHashMap<>();
        if (node == null) return links;
        if (!node.spouse.isBlank()) links.put(UUID.fromString(node.spouse), Link.SPOUSE);
        for (String parent : List.of(node.mother, node.father)) if (!parent.isBlank()) links.put(UUID.fromString(parent), Link.PARENT);
        for (String child : node.children) links.putIfAbsent(UUID.fromString(child), Link.CHILD);
        for (String parent : List.of(node.mother, node.father)) {
            Node p = nodes.get(parent);
            if (p != null) for (String sibling : p.children) if (!sibling.equals(node.id)) links.putIfAbsent(UUID.fromString(sibling), Link.SIBLING);
        }
        return links;
    }
    public List<UUID> children(UUID id) { Node node = get(id); return node == null ? List.of() : node.children.stream().map(UUID::fromString).toList(); }
    public List<UUID> parents(UUID id) {
        Node node = get(id); if (node == null) return List.of();
        List<UUID> parents = new ArrayList<>();
        for (String parent : List.of(node.mother, node.father)) if (!parent.isBlank()) parents.add(UUID.fromString(parent));
        return parents;
    }

    /** Blood relatives: a common ancestor within {@link #DEPTH} generations, counting the two themselves. Spouses are not blood. */
    public boolean related(UUID a, UUID b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        Set<String> up = ancestors(a.toString());
        for (String ancestor : ancestors(b.toString())) if (up.contains(ancestor)) return true;
        return false;
    }
    private Set<String> ancestors(String id) {
        Set<String> found = new HashSet<>(); found.add(id);
        List<String> layer = List.of(id);
        for (int depth = 0; depth < DEPTH && !layer.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (String member : layer) {
                Node node = nodes.get(member);
                if (node == null) continue;
                for (String parent : List.of(node.mother, node.father)) if (!parent.isBlank() && found.add(parent)) next.add(parent);
            }
            layer = next;
        }
        return found;
    }

    /** The relatives of {@code id} as the prompt reads them, spouse first. */
    public List<Kin> kin(UUID id) {
        List<Kin> list = new ArrayList<>();
        for (Map.Entry<UUID, Link> entry : links(id).entrySet()) {
            Node other = get(entry.getKey());
            if (other == null) continue;
            Gender gender = Gender.of(other.gender);
            Gender g = gender == null ? Gender.MALE : gender;
            String word = switch (entry.getValue()) {
                case SPOUSE -> gender == null ? "супруг" : g.spouse;
                case PARENT -> g.parent; case CHILD -> g.offspring; case SIBLING -> g.sibling;
            };
            list.add(new Kin(entry.getKey(), other.name, word, other.player, other.dead));
        }
        return list;
    }

    void sanitize() {
        if (nodes == null) nodes = new LinkedHashMap<>();
        nodes.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || !valid(e.getKey()));
        for (Map.Entry<String, Node> entry : nodes.entrySet()) {
            Node node = entry.getValue();
            node.id = entry.getKey();
            if (node.name == null) node.name = ""; if (node.gender == null) node.gender = "";
            if (node.father == null || !node.father.isBlank() && !valid(node.father)) node.father = "";
            if (node.mother == null || !node.mother.isBlank() && !valid(node.mother)) node.mother = "";
            if (node.spouse == null || !node.spouse.isBlank() && !valid(node.spouse)) node.spouse = "";
            if (node.children == null) node.children = new ArrayList<>();
            node.children.removeIf(child -> child == null || !valid(child));
        }
    }
    private static boolean valid(String id) { try { UUID.fromString(id); return true; } catch (IllegalArgumentException invalid) { return false; } }
    FamilyTree copy() { FamilyTree c = new FamilyTree(); nodes.forEach((key, node) -> c.nodes.put(key, node.copy())); return c; }
}
