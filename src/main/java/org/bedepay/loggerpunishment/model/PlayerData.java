package org.bedepay.loggerpunishment.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Класс для хранения информации об игроке
 */
public class PlayerData {
    private Long id;
    private UUID playerUuid;
    private String playerName;
    private Long discordThreadId;
    private int totalPunishments = 0;
    private int activePunishments = 0;
    private Instant lastPunishmentAt;
    private Instant createdAt;
    private Instant updatedAt;
    
    // Конструкторы
    public PlayerData() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
    
    public PlayerData(UUID playerUuid, String playerName) {
        this();
        this.playerUuid = playerUuid;
        this.playerName = playerName;
    }
    
    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public UUID getPlayerUuid() { return playerUuid; }
    public void setPlayerUuid(UUID playerUuid) { this.playerUuid = playerUuid; }
    
    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }
    
    public Long getDiscordThreadId() { return discordThreadId; }
    public void setDiscordThreadId(Long discordThreadId) { this.discordThreadId = discordThreadId; }
    
    public int getTotalPunishments() { return totalPunishments; }
    public void setTotalPunishments(int totalPunishments) { this.totalPunishments = totalPunishments; }
    
    public int getActivePunishments() { return activePunishments; }
    public void setActivePunishments(int activePunishments) { this.activePunishments = activePunishments; }
    
    public Instant getLastPunishmentAt() { return lastPunishmentAt; }
    public void setLastPunishmentAt(Instant lastPunishmentAt) { this.lastPunishmentAt = lastPunishmentAt; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    
    @Override
    public String toString() {
        return String.format("PlayerData{uuid=%s, name='%s', totalPunishments=%d, activePunishments=%d}", 
                           playerUuid, playerName, totalPunishments, activePunishments);
    }
} 