package ru.devdem.autoServerControl.utils;

import java.util.Set;

/**
 * Небольшие вспомогательные методы, которые не привязаны к Velocity API.
 */
public class Utils {

    /**
     * Возвращает список алиасов в человекочитаемом виде для логов.
     *
     * @param aliases алиасы команды сервера
     * @return строка вида "alias1, alias2"
     */
    public static String getAliasesFromSet(Set<String> aliases) {
        return String.join(", ", aliases);
    }
}
