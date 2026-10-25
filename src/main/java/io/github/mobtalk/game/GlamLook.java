package io.github.mobtalk.game;

import net.minecraft.entity.passive.VillagerEntity;

/** Меркантильная горожанка одета в наряд {@code clothing/glam}: флаг жителя в отслеживаемых данных (VillagerGlamMixin), сервер ставит его,
 * клиент по нему выбирает одежду — так наряд виден и на выделенном сервере. Не сохраняется: сервер ставит заново раз в пять секунд. */
public interface GlamLook {
    boolean mobtalk$glam();
    void mobtalk$setGlam(boolean glam);

    static boolean of(VillagerEntity villager) { return villager instanceof GlamLook look && look.mobtalk$glam(); }
}
