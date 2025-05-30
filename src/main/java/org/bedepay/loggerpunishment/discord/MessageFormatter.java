package org.bedepay.loggerpunishment.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bedepay.loggerpunishment.LoggerPunishment;
import org.bedepay.loggerpunishment.config.ConfigManager;
import org.bedepay.loggerpunishment.model.ModeratorData;
import org.bedepay.loggerpunishment.model.PlayerData;
import org.bedepay.loggerpunishment.model.PunishmentData;
import org.bedepay.loggerpunishment.model.PunishmentType;
import org.bedepay.loggerpunishment.model.UnbanType;
import org.bedepay.loggerpunishment.util.TimeFormatter;

import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Класс для форматирования Discord embed сообщений
 */
public class MessageFormatter {
    
    private final LoggerPunishment plugin;
    private final ConfigManager configManager;
    
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    
    // Константы для embed'ов
    private static final String SERVER_ICON_URL = "https://mc-heads.net/avatar/steve/64";
    private static final String PLAYER_AVATAR_BASE_URL = "https://mc-heads.net/avatar/";
    
    public MessageFormatter(LoggerPunishment plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
    }
    
    /**
     * Создать embed для наказания
     */
    public MessageEmbed createPunishmentEmbed(PunishmentData punishment) {
        EmbedBuilder builder = new EmbedBuilder();
        ConfigManager.DiscordConfig discord = configManager.getDiscordConfig();
        
        // Заголовок и цвет
        String typeKey = punishment.getType().name().toLowerCase();
        String title = discord.punishmentTitles.getOrDefault(typeKey, punishment.getType().getDisplayName());
        Color color = discord.colors.getOrDefault(typeKey, Color.RED);
        String emoji = discord.emojis.getOrDefault(typeKey, punishment.getType().getEmoji());
        
        builder.setTitle(emoji + " " + title);
        builder.setColor(color);
        
        // Основная информация
        builder.addField(
            discord.emojis.getOrDefault("player", "👤") + " Игрок",
            punishment.getPlayerName() + "\n`" + punishment.getPlayerUuid() + "`",
            true
        );
        
        builder.addField(
            discord.emojis.getOrDefault("moderator", "👮") + " Модератор", 
            punishment.getModeratorName() != null ? punishment.getModeratorName() : "Система",
            true
        );
        
        // Длительность
        String durationText;
        if (punishment.isPermanent()) {
            durationText = "Навсегда";
        } else {
            durationText = TimeFormatter.formatDuration(punishment.getDuration());
            if (punishment.getExpiresAt() != null) {
                durationText += "\nИстекает: " + TimeFormatter.formatDateTime(punishment.getExpiresAt());
            }
        }
        
        builder.addField(
            discord.emojis.getOrDefault("duration", "⏰") + " Длительность",
            durationText,
            true
        );
        
        // Причина
        builder.addField(
            discord.emojis.getOrDefault("reason", "📝") + " Причина",
            punishment.getReason(),
            false
        );
        
        // ID наказания
        if (punishment.getPunishmentId() != null) {
            builder.addField(
                discord.emojis.getOrDefault("id", "🆔") + " ID",
                "`" + punishment.getPunishmentId() + "`",
                true
            );
        }
        
        // Дополнительная информация для jail
        if (punishment.getType() == PunishmentType.JAIL && punishment.getJailName() != null) {
            builder.addField("🏢 Тюрьма", punishment.getJailName(), true);
        }
        
        // Статус
        String status = punishment.isActive() ? 
            discord.emojis.getOrDefault("active", "🔴") + " Активно" :
            discord.emojis.getOrDefault("expired", "⚪") + " Неактивно";
        builder.addField("Статус", status, true);
        
        // Время создания
        builder.setTimestamp(punishment.getCreatedAt());
        builder.setFooter("Создано");
        
        return builder.build();
    }
    
    /**
     * Создать embed для разбана
     */
    public MessageEmbed createUnbanEmbed(PunishmentData punishment) {
        EmbedBuilder builder = new EmbedBuilder();
        ConfigManager.DiscordConfig discord = configManager.getDiscordConfig();
        
        // Определяем тип разбана
        String unbanKey = "unban";
        if (punishment.getType() == PunishmentType.MUTE) {
            unbanKey = "unmute";
        } else if (punishment.getType() == PunishmentType.JAIL) {
            unbanKey = "unjail";
        }
        
        String title = discord.unbanTitles.getOrDefault(unbanKey, "Снятие наказания");
        Color color = discord.colors.getOrDefault("unban", Color.GREEN);
        String emoji = discord.emojis.getOrDefault("unban", "✅");
        
        builder.setTitle(emoji + " " + title);
        builder.setColor(color);
        
        // Основная информация
        builder.addField(
            discord.emojis.getOrDefault("player", "👤") + " Игрок",
            punishment.getPlayerName(),
            true
        );
        
        builder.addField(
            discord.emojis.getOrDefault("moderator", "👮") + " Снял",
            punishment.getUnbanModeratorName() != null ? punishment.getUnbanModeratorName() : "Система",
            true
        );
        
        // Тип снятия
        if (punishment.getUnbanType() != null) {
            builder.addField(
                "Тип снятия",
                punishment.getUnbanType().getEmoji() + " " + punishment.getUnbanType().getDisplayName(),
                true
            );
        }
        
        // Причина снятия
        if (punishment.getUnbanReason() != null && !punishment.getUnbanReason().isEmpty()) {
            builder.addField(
                discord.emojis.getOrDefault("reason", "📝") + " Причина снятия",
                punishment.getUnbanReason(),
                false
            );
        }
        
        // Время снятия
        if (punishment.getUnbannedAt() != null) {
            builder.setTimestamp(punishment.getUnbannedAt());
            builder.setFooter("Снято");
        }
        
        return builder.build();
    }
    
    /**
     * Создать embed для лог канала
     */
    public MessageEmbed createLogEmbed(PunishmentData punishment) {
        EmbedBuilder builder = new EmbedBuilder();
        ConfigManager.DiscordConfig discord = configManager.getDiscordConfig();
        
        String typeKey = punishment.getType().name().toLowerCase();
        String title = discord.punishmentTitles.getOrDefault(typeKey, punishment.getType().getDisplayName());
        Color color = discord.colors.getOrDefault(typeKey, Color.ORANGE);
        String emoji = discord.emojis.getOrDefault(typeKey, punishment.getType().getEmoji());
        
        builder.setTitle(emoji + " " + title);
        builder.setColor(color);
        
        // Краткая информация в одном поле
        StringBuilder info = new StringBuilder();
        info.append("**Игрок:** ").append(punishment.getPlayerName()).append("\n");
        info.append("**Модератор:** ").append(punishment.getModeratorName() != null ? punishment.getModeratorName() : "Система").append("\n");
        
        if (!punishment.isPermanent()) {
            info.append("**Длительность:** ").append(TimeFormatter.formatDuration(punishment.getDuration())).append("\n");
        } else {
            info.append("**Длительность:** Навсегда\n");
        }
        
        info.append("**Причина:** ").append(punishment.getReason());
        
        if (punishment.getPunishmentId() != null) {
            info.append("\n**ID:** `").append(punishment.getPunishmentId()).append("`");
        }
        
        builder.setDescription(info.toString());
        builder.setTimestamp(punishment.getCreatedAt());
        
        return builder.build();
    }
    
    /**
     * Создать embed статистики игрока
     */
    public MessageEmbed createPlayerStatsEmbed(String playerName, String playerUuid, 
                                             Map<PunishmentType, Integer> totalCounts,
                                             Map<PunishmentType, Integer> activeCounts,
                                             List<PunishmentData> activePunishments) {
        EmbedBuilder builder = new EmbedBuilder();
        ConfigManager.DiscordConfig discord = configManager.getDiscordConfig();
        
        builder.setTitle(discord.emojis.getOrDefault("stats", "📊") + " Статистика игрока: " + playerName);
        builder.setColor(discord.colors.getOrDefault("info", Color.BLUE));
        
        // Общая статистика
        StringBuilder totalStats = new StringBuilder();
        int totalPunishments = totalCounts.values().stream().mapToInt(Integer::intValue).sum();
        totalStats.append("**Всего наказаний:** ").append(totalPunishments).append("\n");
        
        for (PunishmentType type : PunishmentType.values()) {
            int count = totalCounts.getOrDefault(type, 0);
            if (count > 0) {
                totalStats.append(type.getEmoji()).append(" ").append(type.getDisplayName()).append(": ").append(count).append("\n");
            }
        }
        
        builder.addField("Общая статистика", totalStats.toString(), true);
        
        // Активные наказания
        StringBuilder activeStats = new StringBuilder();
        int totalActive = activeCounts.values().stream().mapToInt(Integer::intValue).sum();
        activeStats.append("**Активных:** ").append(totalActive).append("\n");
        
        for (PunishmentType type : PunishmentType.values()) {
            int count = activeCounts.getOrDefault(type, 0);
            if (count > 0) {
                activeStats.append(type.getEmoji()).append(" ").append(type.getDisplayName()).append(": ").append(count).append("\n");
            }
        }
        
        builder.addField("Активные наказания", activeStats.toString(), true);
        
        // Детали активных наказаний
        if (!activePunishments.isEmpty()) {
            StringBuilder details = new StringBuilder();
            for (PunishmentData punishment : activePunishments) {
                details.append(punishment.getType().getEmoji()).append(" ");
                if (punishment.isPermanent()) {
                    details.append("Навсегда");
                } else {
                    details.append(TimeFormatter.formatTimeLeft(punishment.getExpiresAt()));
                }
                details.append(" - ").append(punishment.getReason()).append("\n");
            }
            
            if (details.length() > 1024) {
                details.setLength(1021);
                details.append("...");
            }
            
            builder.addField("Детали активных наказаний", details.toString(), false);
        }
        
        builder.addField("UUID", "`" + playerUuid + "`", false);
        builder.setTimestamp(Instant.now());
        builder.setFooter("Обновлено");
        
        return builder.build();
    }
    
    /**
     * Создать embed статистики модератора
     */
    public MessageEmbed createModeratorStatsEmbed(String moderatorName, String moderatorUuid,
                                                Map<PunishmentType, Integer> issuedCounts,
                                                int totalIssued, int activeIssued) {
        EmbedBuilder builder = new EmbedBuilder();
        ConfigManager.DiscordConfig discord = configManager.getDiscordConfig();
        
        builder.setTitle(discord.emojis.getOrDefault("stats", "📊") + " Статистика модератора: " + moderatorName);
        builder.setColor(discord.colors.getOrDefault("info", Color.BLUE));
        
        // Общая статистика
        StringBuilder totalStats = new StringBuilder();
        totalStats.append("**Всего выдано:** ").append(totalIssued).append("\n");
        totalStats.append("**Активных:** ").append(activeIssued).append("\n");
        
        builder.addField("Общая статистика", totalStats.toString(), true);
        
        // Статистика по типам
        StringBuilder typeStats = new StringBuilder();
        for (PunishmentType type : PunishmentType.values()) {
            int count = issuedCounts.getOrDefault(type, 0);
            if (count > 0) {
                typeStats.append(type.getEmoji()).append(" ").append(type.getDisplayName()).append(": ").append(count).append("\n");
            }
        }
        
        if (typeStats.length() == 0) {
            typeStats.append("Нет данных");
        }
        
        builder.addField("По типам наказаний", typeStats.toString(), true);
        
        builder.addField("UUID", "`" + moderatorUuid + "`", false);
        builder.setTimestamp(Instant.now());
        builder.setFooter("Обновлено");
        
        return builder.build();
    }
    
    /**
     * Создать embed уведомления о непривязанном аккаунте
     */
    public MessageEmbed createNoDiscordLinkEmbed(String playerName, String playerUuid, 
                                               PunishmentType punishmentType, String moderatorName) {
        EmbedBuilder builder = new EmbedBuilder();
        ConfigManager.DiscordConfig discord = configManager.getDiscordConfig();
        
        builder.setTitle("🔗 Игрок не привязал Discord аккаунт");
        builder.setColor(discord.colors.getOrDefault("info", Color.ORANGE));
        
        String template = discord.noDiscordLinkTemplate;
        if (template != null && !template.isEmpty()) {
            String description = template
                .replace("{player_name}", playerName)
                .replace("{player_uuid}", playerUuid)
                .replace("{punishment_type}", punishmentType.getDisplayName())
                .replace("{moderator_name}", moderatorName != null ? moderatorName : "Система");
            
            builder.setDescription(description);
        } else {
            // Fallback если шаблон не настроен
            builder.addField("👤 Игрок", playerName + "\n`" + playerUuid + "`", true);
            builder.addField("🚫 Тип наказания", punishmentType.getDisplayName(), true);
            builder.addField("👮 Модератор", moderatorName != null ? moderatorName : "Система", true);
            builder.setDescription("Для привязки Discord аккаунта используйте команду на сервере");
        }
        
        builder.setTimestamp(Instant.now());
        
        return builder.build();
    }
    
    /**
     * Создать уведомление для канала игроков
     */
    public MessageEmbed createPlayerNotification(PunishmentData punishment) {
        return createPunishmentEmbed(punishment);
    }
    
    /**
     * Создать уведомление для канала модераторов
     */
    public MessageEmbed createModeratorNotification(PunishmentData punishment) {
        return createPunishmentEmbed(punishment);
    }
    
    /**
     * Создать уведомление для канала логов
     */
    public MessageEmbed createLogNotification(PunishmentData punishment) {
        return createLogEmbed(punishment);
    }
    
    /**
     * Создать уведомление о снятии наказания для игроков
     */
    public MessageEmbed createUnbanPlayerNotification(PunishmentData punishment) {
        return createUnbanEmbed(punishment);
    }
    
    /**
     * Создать уведомление о снятии наказания для модераторов
     */
    public MessageEmbed createUnbanModeratorNotification(PunishmentData punishment) {
        return createUnbanEmbed(punishment);
    }
    
    /**
     * Создать уведомление о снятии наказания для логов
     */
    public MessageEmbed createUnbanLogNotification(PunishmentData punishment) {
        return createLogEmbed(punishment);
    }
    
    /**
     * Создать обновленное уведомление
     */
    public MessageEmbed createUpdatedNotification(PunishmentData punishment) {
        if (punishment.isActive()) {
            return createPunishmentEmbed(punishment);
        } else {
            return createUnbanEmbed(punishment);
        }
    }
    
    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================
    
    /**
     * Получить URL аватара игрока
     */
    private String getPlayerAvatarUrl(String playerUuid) {
        if (playerUuid == null || playerUuid.isEmpty()) {
            return PLAYER_AVATAR_BASE_URL + "steve/64";
        }
        return PLAYER_AVATAR_BASE_URL + playerUuid.replace("-", "") + "/64";
    }
} 