package org.bedepay.loggerpunishment.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Класс для хранения информации о наказании
 */
public class PunishmentData {
    private Long id;
    private PunishmentType type;
    private UUID playerUuid;
    private String playerName;
    private UUID moderatorUuid;
    private String moderatorName;
    private String punishmentId; // ID из LiteBans/CMI
    private String reason;
    private Long duration; // В секундах, null = permanent
    private Instant expiresAt;
    private String jailName; // Для jail наказаний
    
    // Discord message IDs
    private Long playerThreadId;
    private Long moderatorThreadId;
    private Long playerMessageId;
    private Long moderatorMessageId;
    private Long logMessageId;
    
    // Статус наказания
    private boolean active = true;
    
    // Информация о снятии наказания
    private Instant unbannedAt;
    private String unbanReason;
    private UUID unbanModeratorUuid;
    private String unbanModeratorName;
    private UnbanType unbanType;
    
    // Системные поля
    private Instant createdAt;
    private Instant updatedAt;
    
    // Конструкторы
    public PunishmentData() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
    
    public PunishmentData(PunishmentType type, UUID playerUuid, String playerName, 
                         UUID moderatorUuid, String moderatorName, String reason) {
        this();
        this.type = type;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.moderatorUuid = moderatorUuid;
        this.moderatorName = moderatorName;
        this.reason = reason;
    }
    
    // Методы для работы с длительностью
    public boolean isPermanent() {
        return duration == null || duration <= 0;
    }
    
    public boolean isExpired() {
        if (isPermanent()) {
            return false;
        }
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
    
    public boolean isTemporary() {
        return !isPermanent();
    }
    
    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public PunishmentType getType() { return type; }
    public void setType(PunishmentType type) { this.type = type; }
    
    public UUID getPlayerUuid() { return playerUuid; }
    public void setPlayerUuid(UUID playerUuid) { this.playerUuid = playerUuid; }
    
    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }
    
    public UUID getModeratorUuid() { return moderatorUuid; }
    public void setModeratorUuid(UUID moderatorUuid) { this.moderatorUuid = moderatorUuid; }
    
    public String getModeratorName() { return moderatorName; }
    public void setModeratorName(String moderatorName) { this.moderatorName = moderatorName; }
    
    public String getPunishmentId() { return punishmentId; }
    public void setPunishmentId(String punishmentId) { this.punishmentId = punishmentId; }
    
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    
    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { 
        this.duration = duration;
        if (duration != null && duration > 0) {
            this.expiresAt = Instant.now().plusSeconds(duration);
        }
    }
    
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    
    public String getJailName() { return jailName; }
    public void setJailName(String jailName) { this.jailName = jailName; }
    
    public Long getPlayerThreadId() { return playerThreadId; }
    public void setPlayerThreadId(Long playerThreadId) { this.playerThreadId = playerThreadId; }
    
    public Long getModeratorThreadId() { return moderatorThreadId; }
    public void setModeratorThreadId(Long moderatorThreadId) { this.moderatorThreadId = moderatorThreadId; }
    
    public Long getPlayerMessageId() { return playerMessageId; }
    public void setPlayerMessageId(Long playerMessageId) { this.playerMessageId = playerMessageId; }
    
    public Long getModeratorMessageId() { return moderatorMessageId; }
    public void setModeratorMessageId(Long moderatorMessageId) { this.moderatorMessageId = moderatorMessageId; }
    
    public Long getLogMessageId() { return logMessageId; }
    public void setLogMessageId(Long logMessageId) { this.logMessageId = logMessageId; }
    
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    
    public Instant getUnbannedAt() { return unbannedAt; }
    public void setUnbannedAt(Instant unbannedAt) { this.unbannedAt = unbannedAt; }
    
    public String getUnbanReason() { return unbanReason; }
    public void setUnbanReason(String unbanReason) { this.unbanReason = unbanReason; }
    
    public UUID getUnbanModeratorUuid() { return unbanModeratorUuid; }
    public void setUnbanModeratorUuid(UUID unbanModeratorUuid) { this.unbanModeratorUuid = unbanModeratorUuid; }
    
    public String getUnbanModeratorName() { return unbanModeratorName; }
    public void setUnbanModeratorName(String unbanModeratorName) { this.unbanModeratorName = unbanModeratorName; }
    
    public UnbanType getUnbanType() { return unbanType; }
    public void setUnbanType(UnbanType unbanType) { this.unbanType = unbanType; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    
    @Override
    public String toString() {
        return String.format("PunishmentData{type=%s, player=%s, moderator=%s, reason='%s', active=%s}", 
                           type, playerName, moderatorName, reason, active);
    }
} 