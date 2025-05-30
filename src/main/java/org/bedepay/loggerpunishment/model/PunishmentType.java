package org.bedepay.loggerpunishment.model;

/**
 * Типы наказаний, поддерживаемые плагином
 */
public enum PunishmentType {
    BAN("ban", "Блокировка аккаунта", "🚫"),
    MUTE("mute", "Ограничение чата", "🔇"),
    KICK("kick", "Исключение с сервера", "👢"),
    JAIL("jail", "Заключение в тюрьму", "🏢");
    
    private final String code;
    private final String displayName;
    private final String emoji;
    
    PunishmentType(String code, String displayName, String emoji) {
        this.code = code;
        this.displayName = displayName;
        this.emoji = emoji;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getEmoji() {
        return emoji;
    }
    
    /**
     * Получить тип наказания по коду
     */
    public static PunishmentType fromCode(String code) {
        for (PunishmentType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Неизвестный тип наказания: " + code);
    }
    
    /**
     * Проверить, является ли наказание временным
     */
    public boolean canBeTemporary() {
        return this == BAN || this == MUTE || this == JAIL;
    }
    
    /**
     * Проверить, может ли наказание быть снято
     */
    public boolean canBeRevoked() {
        return this == BAN || this == MUTE || this == JAIL;
    }
} 