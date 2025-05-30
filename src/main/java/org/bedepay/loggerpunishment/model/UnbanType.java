package org.bedepay.loggerpunishment.model;

/**
 * Типы снятия наказаний
 */
public enum UnbanType {
    MANUAL("manual", "Ручное снятие", "👮"),
    AUTOMATIC("automatic", "Автоматическое истечение", "⏰"),
    EXPIRED("expired", "Истекло время", "⌛");
    
    private final String code;
    private final String displayName;
    private final String emoji;
    
    UnbanType(String code, String displayName, String emoji) {
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
     * Получить тип снятия наказания по коду
     */
    public static UnbanType fromCode(String code) {
        for (UnbanType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return MANUAL; // По умолчанию считаем ручным
    }
    
    /**
     * Определить тип снятия наказания на основе причины
     */
    public static UnbanType determineFromReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return EXPIRED;
        }
        
        String lowerReason = reason.toLowerCase();
        if (lowerReason.contains("expired") || lowerReason.contains("истек")) {
            return EXPIRED;
        }
        
        if (lowerReason.contains("automatic") || lowerReason.contains("автоматически")) {
            return AUTOMATIC;
        }
        
        return MANUAL;
    }
} 