/*
 * Mob Talk — human villagers (track M). Part of the MCA Reborn port (GPL-3.0), see third_party/mca-reborn/NOTICE.md.
 */
package io.github.mobtalk.client.human;

import io.github.mobtalk.human.HumanGenes;

/** Everything the human layers need to draw one villager: its genes, sex ("female" or "male", the texture folder name),
 * vanilla profession path ("farmer", "none", "nitwit"…) and age band. */
public record HumanLook(HumanGenes genes, String gender, String profession, Age age) {
    public enum Age { TODDLER, CHILD, ADULT }
    public boolean female() { return gender.equals("female"); }
}
