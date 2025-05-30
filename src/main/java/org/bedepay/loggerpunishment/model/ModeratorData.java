package org.bedepay.loggerpunishment.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Класс для хранения информации о модераторе
 */
public class ModeratorData {
    private Long id;
    private UUID moderatorUuid;
    private String moderatorName;
    private Long discordId;
    private Long discordThreadId;
    private int totalIssued = 0;
    private int activeIssued = 0;
    private Instant lastActionAt;
    private Instant createdAt;
    private Instant updatedAt;
    
    // Конструкторы
    public ModeratorData() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
    
    public ModeratorData(UUID moderatorUuid, String moderatorName) {
        this();
        this.moderatorUuid = moderatorUuid;
        this.moderatorName = moderatorName;
    }
    
    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public UUID getModeratorUuid() { return moderatorUuid; }
    public void setModeratorUuid(UUID moderatorUuid) { this.moderatorUuid = moderatorUuid; }
    
    public String getModeratorName() { return moderatorName; }
    public void setModeratorName(String moderatorName) { this.moderatorName = moderatorName; }
    
    public Long getDiscordId() { return discordId; }
    public void setDiscordId(Long discordId) { this.discordId = discordId; }
    
    public Long getDiscordThreadId() { return discordThreadId; }
    public void setDiscordThreadId(Long discordThreadId) { this.discordThreadId = discordThreadId; }
    
    public int getTotalIssued() { return totalIssued; }
    public void setTotalIssued(int totalIssued) { this.totalIssued = totalIssued; }
    
    public int getActiveIssued() { return activeIssued; }
    public void setActiveIssued(int activeIssued) { this.activeIssued = activeIssued; }
    
    public Instant getLastActionAt() { return lastActionAt; }
    public void setLastActionAt(Instant lastActionAt) { this.lastActionAt = lastActionAt; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    
    @Override
    public String toString() {
        return String.format("ModeratorData{uuid=%s, name='%s', totalIssued=%d, activeIssued=%d}", 
                           moderatorUuid, moderatorName, totalIssued, activeIssued);
    }
} 