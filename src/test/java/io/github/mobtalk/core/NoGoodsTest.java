package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Житель без товаров (без ремесла или ещё без предложений) не обещает скидку, которую игра не даст; отказ — можно. */
class NoGoodsTest {
    @Test void aPromisedDiscountIsCaughtButARefusalIsNot() {
        assertTrue(AnswerValidator.promisesDiscount("Для тебя сделаю скидку, заходи завтра."));
        assertTrue(AnswerValidator.promisesDiscount("Отдам дешевле, раз ты мне помог."));
        assertTrue(AnswerValidator.promisesDiscount("Продам тебе со скидкой."));
        assertFalse(AnswerValidator.promisesDiscount("Скидку не дам, мне и торговать-то нечем."));
        assertFalse(AnswerValidator.promisesDiscount("Не дам скидку, и не проси."));
        assertFalse(AnswerValidator.promisesDiscount("Привет! Как урожай?"));
    }
}
