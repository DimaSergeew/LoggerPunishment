package org.bedepay.loggerpunishment.service;

import org.bedepay.loggerpunishment.LoggerPunishment;
import org.bedepay.loggerpunishment.database.DatabaseManager;
import org.bedepay.loggerpunishment.discord.DiscordManager;
import org.bedepay.loggerpunishment.discord.ForumManager;
import org.bedepay.loggerpunishment.model.ModeratorData;
import org.bedepay.loggerpunishment.model.PlayerData;
import org.bedepay.loggerpunishment.model.PunishmentData;
import org.bedepay.loggerpunishment.model.PunishmentType;
import org.bedepay.loggerpunishment.model.UnbanType;
import org.bedepay.loggerpunishment.redis.RedisManager;
import org.redisson.api.RLock;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Сервис для обработки наказаний
 */
public class PunishmentService {
    
    private final LoggerPunishment plugin;
    private final DatabaseManager databaseManager;
    private final DiscordManager discordManager;
    private final ForumManager forumManager;
    private final RedisManager redisManager;
    private final Logger logger;
    
    public PunishmentService(LoggerPunishment plugin) {
        this.plugin = plugin;
        this.databaseManager = plugin.getDatabaseManager();
        this.discordManager = plugin.getDiscordManager();
        this.forumManager = plugin.getForumManager();
        this.redisManager = plugin.getRedisManager();
        this.logger = plugin.getLogger();
    }
    
    // ==================== ОБРАБОТКА НАКАЗАНИЙ ====================
    
    /**
     * Обработать новое наказание (для интеграции с PunishmentListener)
     */
    public CompletableFuture<Void> processPunishment(PunishmentType type, UUID playerUuid, String playerName,
                                                   UUID moderatorUuid, String moderatorName, String reason,
                                                   Long duration, String punishmentId) {
        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("Обработка наказания: " + type + " для " + playerName);
                
                // Создание объекта наказания
                PunishmentData punishment = new PunishmentData(type, playerUuid, playerName, moderatorUuid, moderatorName, reason);
                punishment.setDuration(duration);
                punishment.setPunishmentId(punishmentId);
                
                // Обработка через основной метод
                processPunishment(punishment).join();
                
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Ошибка при обработке наказания: " + type + " для " + playerName, e);
            }
        });
    }
    
    /**
     * Обработать новое наказание
     */
    public CompletableFuture<Void> processPunishment(PunishmentData punishment) {
        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("Обработка наказания: " + punishment.getType() + " для " + punishment.getPlayerName());
                
                // 1. Сохранить в базу данных
                databaseManager.savePunishment(punishment);
                
                // 2. Получить или создать ветки для игрока и модератора
                ThreadChannel playerThread = getOrCreatePlayerThread(punishment);
                ThreadChannel moderatorThread = getOrCreateModeratorThread(punishment);
                
                // 3. Отправить сообщения в ветки
                Message playerMessage = null;
                Message moderatorMessage = null;
                Message logMessage = null;
                
                if (playerThread != null) {
                    playerMessage = forumManager.sendPlayerPunishmentMessage(playerThread, punishment);
                    if (playerMessage != null) {
                        punishment.setPlayerMessageId(playerMessage.getIdLong());
                    }
                }
                
                if (moderatorThread != null) {
                    moderatorMessage = forumManager.sendModeratorPunishmentMessage(moderatorThread, punishment);
                    if (moderatorMessage != null) {
                        punishment.setModeratorMessageId(moderatorMessage.getIdLong());
                    }
                }
                
                // 4. Отправить в лог канал
                logMessage = forumManager.sendLogMessage(punishment);
                if (logMessage != null) {
                    punishment.setLogMessageId(logMessage.getIdLong());
                }
                
                // 5. Обновить наказание с ID сообщений
                databaseManager.updatePunishment(punishment);
                
                // 6. Обновить статистику
                updatePlayerStats(punishment.getPlayerUuid(), punishment.getPlayerName(), playerThread);
                if (punishment.getModeratorUuid() != null) {
                    updateModeratorStats(punishment.getModeratorUuid(), punishment.getModeratorName(), moderatorThread);
                }
                
                logger.info("Наказание успешно обработано: " + punishment.getPunishmentId());
                
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Ошибка при обработке наказания: " + punishment.getPunishmentId(), e);
            }
        });
    }
    
    /**
     * Обработать разбан/размут (для интеграции с PunishmentListener)
     */
    public CompletableFuture<Void> processUnban(String punishmentId, UUID unbanModeratorUuid, 
                                               String unbanModeratorName, String unbanReason, UnbanType unbanType) {
        return processUnban(punishmentId, unbanType, unbanReason, unbanModeratorUuid, unbanModeratorName);
    }
    
    /**
     * Обработать разбан/размут
     */
    public CompletableFuture<Void> processUnban(String punishmentId, UnbanType unbanType, 
                                               String unbanReason, UUID unbanModeratorUuid, 
                                               String unbanModeratorName) {
        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("Обработка разбана: " + punishmentId);
                
                // 1. Найти наказание в базе данных
                PunishmentData punishment = databaseManager.getPunishmentByPunishmentId(punishmentId);
                if (punishment == null) {
                    logger.warning("Наказание не найдено: " + punishmentId);
                    return;
                }
                
                // 2. Обновить данные о разбане
                punishment.setActive(false);
                punishment.setUnbannedAt(Instant.now());
                punishment.setUnbanType(unbanType);
                punishment.setUnbanReason(unbanReason);
                punishment.setUnbanModeratorUuid(unbanModeratorUuid);
                punishment.setUnbanModeratorName(unbanModeratorName);
                
                // 3. Сохранить изменения в базу
                databaseManager.updatePunishment(punishment);
                
                // 4. Обновить сообщения в ветках
                updatePunishmentMessages(punishment);
                
                // 5. Отправить сообщение в лог
                Message logMessage = forumManager.sendLogMessage(punishment);
                if (logMessage != null) {
                    punishment.setLogMessageId(logMessage.getIdLong());
                    databaseManager.updatePunishment(punishment);
                }
                
                // 6. Обновить статистику
                updatePlayerStats(punishment.getPlayerUuid(), punishment.getPlayerName(), null);
                if (punishment.getModeratorUuid() != null) {
                    updateModeratorStats(punishment.getModeratorUuid(), punishment.getModeratorName(), null);
                }
                
                logger.info("Разбан успешно обработан: " + punishmentId);
                
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Ошибка при обработке разбана: " + punishmentId, e);
            }
        });
    }
    
    // ==================== РАБОТА С ВЕТКАМИ ====================
    
    /**
     * Получить или создать ветку для игрока
     */
    private ThreadChannel getOrCreatePlayerThread(PunishmentData punishment) {
        try {
            // Проверяем в кэше Redis
            Long threadId = redisManager.getPlayerThreadId(punishment.getPlayerUuid().toString());
            
            if (threadId != null && threadId > 0) {
                ThreadChannel thread = forumManager.getPlayerThread(threadId);
                if (thread != null) {
                    punishment.setPlayerThreadId(threadId);
                    return thread;
                }
            }
            
            // Проверяем в базе данных
            PlayerData player = databaseManager.getPlayerByUuid(punishment.getPlayerUuid());
            if (player != null && player.getDiscordThreadId() != null) {
                ThreadChannel thread = forumManager.getPlayerThread(player.getDiscordThreadId());
                if (thread != null) {
                    punishment.setPlayerThreadId(player.getDiscordThreadId());
                    redisManager.cachePlayerThreadId(punishment.getPlayerUuid().toString(), player.getDiscordThreadId());
                    return thread;
                }
            }
            
            // Создаем новую ветку с блокировкой
            RLock lock = redisManager.getThreadCreationLock(punishment.getPlayerUuid().toString());
            if (lock != null && lock.tryLock(10, TimeUnit.SECONDS)) {
                try {
                    // Повторно проверяем после получения блокировки
                    threadId = redisManager.getPlayerThreadId(punishment.getPlayerUuid().toString());
                    if (threadId != null && threadId > 0) {
                        ThreadChannel thread = forumManager.getPlayerThread(threadId);
                        if (thread != null) {
                            punishment.setPlayerThreadId(threadId);
                            return thread;
                        }
                    }
                    
                    // Создаем новую ветку
                    if (player == null) {
                        player = createPlayerData(punishment);
                    }
                    
                    ThreadChannel newThread = forumManager.createPlayerThread(player);
                    if (newThread != null) {
                        long newThreadId = newThread.getIdLong();
                        player.setDiscordThreadId(newThreadId);
                        punishment.setPlayerThreadId(newThreadId);
                        
                        databaseManager.saveOrUpdatePlayer(player);
                        redisManager.cachePlayerThreadId(punishment.getPlayerUuid().toString(), newThreadId);
                        
                        return newThread;
                    }
                    
                } finally {
                    lock.unlock();
                }
            }
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при создании ветки для игрока: " + punishment.getPlayerName(), e);
        }
        
        return null;
    }
    
    /**
     * Получить или создать ветку для модератора
     */
    private ThreadChannel getOrCreateModeratorThread(PunishmentData punishment) {
        if (punishment.getModeratorUuid() == null) {
            return null; // Системное наказание
        }
        
        try {
            // Проверяем в кэше Redis
            Long threadId = redisManager.getModeratorThreadId(punishment.getModeratorUuid().toString());
            
            if (threadId != null && threadId > 0) {
                ThreadChannel thread = forumManager.getModeratorThread(threadId);
                if (thread != null) {
                    punishment.setModeratorThreadId(threadId);
                    return thread;
                }
            }
            
            // Проверяем в базе данных
            ModeratorData moderator = databaseManager.getModeratorByUuid(punishment.getModeratorUuid());
            if (moderator != null && moderator.getDiscordThreadId() != null) {
                ThreadChannel thread = forumManager.getModeratorThread(moderator.getDiscordThreadId());
                if (thread != null) {
                    punishment.setModeratorThreadId(moderator.getDiscordThreadId());
                    redisManager.cacheModeratorThreadId(punishment.getModeratorUuid().toString(), moderator.getDiscordThreadId());
                    return thread;
                }
            }
            
            // Создаем новую ветку с блокировкой
            RLock lock = redisManager.getThreadCreationLock(punishment.getModeratorUuid().toString());
            if (lock != null && lock.tryLock(10, TimeUnit.SECONDS)) {
                try {
                    // Повторно проверяем после получения блокировки
                    threadId = redisManager.getModeratorThreadId(punishment.getModeratorUuid().toString());
                    if (threadId != null && threadId > 0) {
                        ThreadChannel thread = forumManager.getModeratorThread(threadId);
                        if (thread != null) {
                            punishment.setModeratorThreadId(threadId);
                            return thread;
                        }
                    }
                    
                    // Создаем новую ветку
                    if (moderator == null) {
                        moderator = createModeratorData(punishment);
                    }
                    
                    ThreadChannel newThread = forumManager.createModeratorThread(moderator);
                    if (newThread != null) {
                        long newThreadId = newThread.getIdLong();
                        moderator.setDiscordThreadId(newThreadId);
                        punishment.setModeratorThreadId(newThreadId);
                        
                        databaseManager.saveOrUpdateModerator(moderator);
                        redisManager.cacheModeratorThreadId(punishment.getModeratorUuid().toString(), newThreadId);
                        
                        return newThread;
                    }
                    
                } finally {
                    lock.unlock();
                }
            }
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при создании ветки для модератора: " + punishment.getModeratorName(), e);
        }
        
        return null;
    }
    
    // ==================== ОБНОВЛЕНИЕ СООБЩЕНИЙ ====================
    
    /**
     * Обновить сообщения о наказании во всех ветках
     */
    private void updatePunishmentMessages(PunishmentData punishment) {
        try {
            // Обновить сообщение в ветке игрока
            if (punishment.getPlayerThreadId() != null && punishment.getPlayerMessageId() != null) {
                ThreadChannel playerThread = forumManager.getPlayerThread(punishment.getPlayerThreadId());
                if (playerThread != null) {
                    forumManager.updatePlayerPunishmentMessage(punishment.getPlayerMessageId(), 
                            playerThread, punishment);
                }
            }
            
            // Обновить сообщение в ветке модератора
            if (punishment.getModeratorThreadId() != null && punishment.getModeratorMessageId() != null) {
                ThreadChannel moderatorThread = forumManager.getModeratorThread(punishment.getModeratorThreadId());
                if (moderatorThread != null) {
                    forumManager.updateModeratorPunishmentMessage(punishment.getModeratorMessageId(), 
                            moderatorThread, punishment);
                }
            }
            
        } catch (Exception e) {
            logger.warning("Ошибка при обновлении сообщений о наказании: " + e.getMessage());
        }
    }
    
    // ==================== ОБНОВЛЕНИЕ СТАТИСТИКИ ====================
    
    /**
     * Обновить статистику игрока
     */
    private void updatePlayerStats(UUID playerUuid, String playerName, ThreadChannel playerThread) {
        try {
            RLock lock = redisManager.getStatsUpdateLock("player:" + playerUuid);
            if (lock != null && lock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    PlayerData player = databaseManager.getPlayerByUuid(playerUuid);
                    if (player == null) {
                        player = new PlayerData();
                        player.setPlayerUuid(playerUuid);
                        player.setPlayerName(playerName);
                    }
                    
                    // Подсчет статистики из базы данных
                    int totalPunishments = databaseManager.getPlayerTotalPunishments(playerUuid);
                    int activePunishments = databaseManager.getPlayerActivePunishmentsCount(playerUuid);
                    
                    player.setTotalPunishments(totalPunishments);
                    player.setActivePunishments(activePunishments);
                    player.setLastPunishmentAt(Instant.now());
                    
                    databaseManager.saveOrUpdatePlayer(player);
                    
                    // Обновить основное сообщение в ветке
                    if (playerThread != null) {
                        forumManager.updatePlayerThreadMainMessage(playerThread, player);
                    }
                    
                } finally {
                    lock.unlock();
                }
            }
            
        } catch (Exception e) {
            logger.warning("Ошибка при обновлении статистики игрока: " + e.getMessage());
        }
    }
    
    /**
     * Обновить статистику модератора
     */
    private void updateModeratorStats(UUID moderatorUuid, String moderatorName, ThreadChannel moderatorThread) {
        try {
            RLock lock = redisManager.getStatsUpdateLock("moderator:" + moderatorUuid);
            if (lock != null && lock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    ModeratorData moderator = databaseManager.getModeratorByUuid(moderatorUuid);
                    if (moderator == null) {
                        moderator = new ModeratorData();
                        moderator.setModeratorUuid(moderatorUuid);
                        moderator.setModeratorName(moderatorName);
                    }
                    
                    // Подсчет статистики из базы данных
                    int totalIssued = databaseManager.getModeratorTotalIssued(moderatorUuid);
                    int activeIssued = databaseManager.getModeratorActiveIssued(moderatorUuid);
                    
                    moderator.setTotalIssued(totalIssued);
                    moderator.setActiveIssued(activeIssued);
                    moderator.setLastActionAt(Instant.now());
                    
                    databaseManager.saveOrUpdateModerator(moderator);
                    
                    // Обновить основное сообщение в ветке
                    if (moderatorThread != null) {
                        forumManager.updateModeratorThreadMainMessage(moderatorThread, moderator);
                    }
                    
                } finally {
                    lock.unlock();
                }
            }
            
        } catch (Exception e) {
            logger.warning("Ошибка при обновлении статистики модератора: " + e.getMessage());
        }
    }
    
    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================
    
    /**
     * Создать данные игрока из наказания
     */
    private PlayerData createPlayerData(PunishmentData punishment) {
        PlayerData player = new PlayerData();
        player.setPlayerUuid(punishment.getPlayerUuid());
        player.setPlayerName(punishment.getPlayerName());
        player.setTotalPunishments(0);
        player.setActivePunishments(0);
        player.setCreatedAt(Instant.now());
        player.setUpdatedAt(Instant.now());
        return player;
    }
    
    /**
     * Создать данные модератора из наказания
     */
    private ModeratorData createModeratorData(PunishmentData punishment) {
        ModeratorData moderator = new ModeratorData();
        moderator.setModeratorUuid(punishment.getModeratorUuid());
        moderator.setModeratorName(punishment.getModeratorName());
        moderator.setTotalIssued(0);
        moderator.setActiveIssued(0);
        moderator.setCreatedAt(Instant.now());
        moderator.setUpdatedAt(Instant.now());
        return moderator;
    }
    
    /**
     * Получить статистику сервиса
     */
    public String getServiceStats() {
        try {
            StringBuilder stats = new StringBuilder();
            stats.append("📊 Статистика PunishmentService:\n");
            stats.append("🔄 Состояние: активен\n");
            
            // Добавить статистику из базы данных и Redis
            if (redisManager.isEnabled()) {
                stats.append("📦 Redis: подключен\n");
            } else {
                stats.append("📦 Redis: отключен\n");
            }
            
            if (databaseManager.isAvailable()) {
                stats.append("💾 База данных: доступна\n");
            } else {
                stats.append("💾 База данных: недоступна\n");
            }
            
            if (discordManager.isReady()) {
                stats.append("💬 Discord: подключен\n");
            } else {
                stats.append("💬 Discord: отключен\n");
            }
            
            return stats.toString();
            
        } catch (Exception e) {
            return "Ошибка при получении статистики: " + e.getMessage();
        }
    }
} 