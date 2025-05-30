package org.bedepay.loggerpunishment.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.restaction.ForumPostAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.entities.MessageHistory;
import org.bedepay.loggerpunishment.LoggerPunishment;
import org.bedepay.loggerpunishment.model.ModeratorData;
import org.bedepay.loggerpunishment.model.PlayerData;
import org.bedepay.loggerpunishment.model.PunishmentData;
import org.bedepay.loggerpunishment.model.PunishmentType;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;

/**
 * Менеджер для работы с Discord форумами
 */
public class ForumManager extends ListenerAdapter {
    
    private final LoggerPunishment plugin;
    private final JDA jda;
    private final MessageFormatter messageFormatter;
    private final Logger logger;
    
    private ForumChannel playerForum;
    private ForumChannel moderatorForum;
    private TextChannel logChannel;
    
    public ForumManager(LoggerPunishment plugin, JDA jda) {
        this.plugin = plugin;
        this.jda = jda;
        this.messageFormatter = new MessageFormatter(plugin);
        this.logger = plugin.getLogger();
        
        // Инициализация форумов
        initializeForums();
        
        // Регистрация слушателя для удаления сообщений не от бота
        jda.addEventListener(this);
    }
    
    /**
     * Инициализация форумов
     */
    private void initializeForums() {
        long playerForumId = plugin.getConfigManager().getPlayerChannelId();
        long moderatorForumId = plugin.getConfigManager().getModeratorChannelId();
        long logChannelId = plugin.getConfigManager().getLogChannelId();
        
        if (playerForumId > 0) {
            playerForum = jda.getForumChannelById(playerForumId);
            if (playerForum == null) {
                plugin.getLogger().warning("Форум для игроков не найден: " + playerForumId);
            }
        }
        
        if (moderatorForumId > 0) {
            moderatorForum = jda.getForumChannelById(moderatorForumId);
            if (moderatorForum == null) {
                plugin.getLogger().warning("Форум для модераторов не найден: " + moderatorForumId);
            }
        }

        if (logChannelId > 0) {
            logChannel = jda.getTextChannelById(logChannelId);
            if (logChannel == null) {
                plugin.getLogger().warning("Лог канал не найден: " + logChannelId);
            }
        }
    }
    
    /**
     * Обработка наказания - создание/обновление веток
     */
    public CompletableFuture<Void> processPunishment(PunishmentData punishment) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Обработка ветки игрока
                processPlayerThread(punishment);
                
                // Обработка ветки модератора
                processModeratorThread(punishment);
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Ошибка при обработке форумов: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Обработка ветки игрока
     */
    private void processPlayerThread(PunishmentData punishment) {
        if (playerForum == null) return;
        
        String lockKey = "player_thread_" + punishment.getPlayerUuid();
        RLock lock = plugin.getRedisManager().getLock(lockKey);
        
        try {
            if (lock.tryLock(10, TimeUnit.SECONDS)) {
                // Получение или создание ветки игрока
                ThreadChannel thread = getOrCreatePlayerThread(punishment);
                
                if (thread != null) {
                    punishment.setPlayerThreadId(thread.getIdLong());
                    
                    // Отправка сообщения о наказании
                    MessageEmbed embed = messageFormatter.createPunishmentEmbed(punishment);
                    thread.sendMessageEmbeds(embed).queue(message -> {
                        punishment.setPlayerMessageId(message.getIdLong());
                        plugin.getDatabaseManager().updatePunishment(punishment);
                    });
                    
                    // Обновление статистики в главном сообщении
                    updatePlayerThreadStats(thread, punishment.getPlayerUuid());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Ошибка при обработке ветки игрока: " + e.getMessage(), e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
    
    /**
     * Обработка ветки модератора
     */
    private void processModeratorThread(PunishmentData punishment) {
        if (moderatorForum == null || punishment.getModeratorUuid() == null) return;
        
        String lockKey = "moderator_thread_" + punishment.getModeratorUuid();
        RLock lock = plugin.getRedisManager().getLock(lockKey);
        
        try {
            if (lock.tryLock(10, TimeUnit.SECONDS)) {
                // Получение или создание ветки модератора
                ThreadChannel thread = getOrCreateModeratorThread(punishment);
                
                if (thread != null) {
                    punishment.setModeratorThreadId(thread.getIdLong());
                    
                    // Отправка сообщения о наказании
                    MessageEmbed embed = messageFormatter.createPunishmentEmbed(punishment);
                    thread.sendMessageEmbeds(embed).queue(message -> {
                        punishment.setModeratorMessageId(message.getIdLong());
                        plugin.getDatabaseManager().updatePunishment(punishment);
                    });
                    
                    // Обновление статистики в главном сообщении
                    updateModeratorThreadStats(thread, punishment.getModeratorUuid());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Ошибка при обработке ветки модератора: " + e.getMessage(), e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
    
    /**
     * Получить или создать ветку игрока
     */
    private ThreadChannel getOrCreatePlayerThread(PunishmentData punishment) {
        // Поиск существующей ветки в БД
        PlayerData player = plugin.getDatabaseManager().getPlayerByUuid(punishment.getPlayerUuid());
        
        if (player != null && player.getDiscordThreadId() != null) {
            ThreadChannel existingThread = jda.getThreadChannelById(player.getDiscordThreadId());
            if (existingThread != null && !existingThread.isArchived()) {
                return existingThread;
            }
        }
        
        // Создание новой ветки
        String threadName = "🎮 " + punishment.getPlayerName();
        MessageEmbed statsEmbed = messageFormatter.createPunishmentEmbed(new PunishmentData());
        
        playerForum.createForumPost(threadName, MessageCreateData.fromEmbeds(statsEmbed)).queue(forumPost -> {
            ThreadChannel newThread = forumPost.getThreadChannel();
            
            // Сохранение ID ветки в БД
            PlayerData playerData = player;
            if (playerData == null) {
                playerData = new PlayerData(punishment.getPlayerUuid(), punishment.getPlayerName());
            }
            playerData.setDiscordThreadId(newThread.getIdLong());
            plugin.getDatabaseManager().saveOrUpdatePlayer(playerData);
            
            // Кэширование в Redis
            plugin.getRedisManager().cachePlayerThreadId(punishment.getPlayerUuid().toString(), newThread.getIdLong());
        });
        
        return null; // Возвращаем null, т.к. создание асинхронное
    }
    
    /**
     * Получить или создать ветку модератора
     */
    private ThreadChannel getOrCreateModeratorThread(PunishmentData punishment) {
        // Поиск существующей ветки в БД
        ModeratorData moderator = plugin.getDatabaseManager().getModeratorByUuid(punishment.getModeratorUuid());
        
        if (moderator != null && moderator.getDiscordThreadId() != null) {
            ThreadChannel existingThread = jda.getThreadChannelById(moderator.getDiscordThreadId());
            if (existingThread != null && !existingThread.isArchived()) {
                return existingThread;
            }
        }
        
        // Создание новой ветки
        String threadName = "👮 " + punishment.getModeratorName();
        MessageEmbed statsEmbed = messageFormatter.createPunishmentEmbed(new PunishmentData());
        
        moderatorForum.createForumPost(threadName, MessageCreateData.fromEmbeds(statsEmbed)).queue(forumPost -> {
            ThreadChannel newThread = forumPost.getThreadChannel();
            
            // Сохранение ID ветки в БД
            ModeratorData moderatorData = moderator;
            if (moderatorData == null) {
                moderatorData = new ModeratorData(punishment.getModeratorUuid(), punishment.getModeratorName());
            }
            moderatorData.setDiscordThreadId(newThread.getIdLong());
            plugin.getDatabaseManager().saveOrUpdateModerator(moderatorData);
            
            // Кэширование в Redis
            plugin.getRedisManager().cacheModeratorThreadId(punishment.getModeratorUuid().toString(), newThread.getIdLong());
        });
        
        return null; // Возвращаем null, т.к. создание асинхронное
    }
    
    /**
     * Обновление статистики в ветке игрока
     */
    private void updatePlayerThreadStats(ThreadChannel thread, java.util.UUID playerUuid) {
        String atomicKey = "player_stats_update_" + playerUuid;
        RAtomicLong lastUpdate = plugin.getRedisManager().getAtomicLong(atomicKey);
        
        long now = System.currentTimeMillis();
        long lastUpdateTime = lastUpdate.get();
        
        // Обновляем статистику не чаще раза в минуту
        if (now - lastUpdateTime > 60000) {
            if (lastUpdate.compareAndSet(lastUpdateTime, now)) {
                PlayerData player = plugin.getDatabaseManager().getPlayerByUuid(playerUuid);
                if (player != null) {
                    MessageEmbed statsEmbed = messageFormatter.createPunishmentEmbed(new PunishmentData());
                    
                    // Обновляем первое сообщение в ветке
                    thread.getHistoryFromBeginning(1).queue(history -> {
                        if (!history.getRetrievedHistory().isEmpty()) {
                            Message firstMessage = history.getRetrievedHistory().get(0);
                            if (firstMessage.getAuthor().equals(jda.getSelfUser())) {
                                firstMessage.editMessageEmbeds(statsEmbed).queue();
                            }
                        }
                    });
                }
            }
        }
    }
    
    /**
     * Обновление статистики в ветке модератора
     */
    private void updateModeratorThreadStats(ThreadChannel thread, java.util.UUID moderatorUuid) {
        String atomicKey = "moderator_stats_update_" + moderatorUuid;
        RAtomicLong lastUpdate = plugin.getRedisManager().getAtomicLong(atomicKey);
        
        long now = System.currentTimeMillis();
        long lastUpdateTime = lastUpdate.get();
        
        // Обновляем статистику не чаще раза в минуту
        if (now - lastUpdateTime > 60000) {
            if (lastUpdate.compareAndSet(lastUpdateTime, now)) {
                ModeratorData moderator = plugin.getDatabaseManager().getModeratorByUuid(moderatorUuid);
                if (moderator != null) {
                    MessageEmbed statsEmbed = messageFormatter.createPunishmentEmbed(new PunishmentData());
                    
                    // Обновляем первое сообщение в ветке
                    thread.getHistoryFromBeginning(1).queue(history -> {
                        if (!history.getRetrievedHistory().isEmpty()) {
                            Message firstMessage = history.getRetrievedHistory().get(0);
                            if (firstMessage.getAuthor().equals(jda.getSelfUser())) {
                                firstMessage.editMessageEmbeds(statsEmbed).queue();
                            }
                        }
                    });
                }
            }
        }
    }
    
    /**
     * Обработка снятия наказания - редактирование сообщений
     */
    public void processUnban(PunishmentData punishment) {
        // Редактирование сообщения в ветке игрока
        if (punishment.getPlayerThreadId() != null && punishment.getPlayerMessageId() != null) {
            ThreadChannel playerThread = jda.getThreadChannelById(punishment.getPlayerThreadId());
            if (playerThread != null) {
                playerThread.retrieveMessageById(punishment.getPlayerMessageId()).queue(message -> {
                    MessageEmbed unbanEmbed = messageFormatter.createUnbanEmbed(punishment);
                    message.editMessageEmbeds(message.getEmbeds().get(0), unbanEmbed).queue();
                });
            }
        }
        
        // Редактирование сообщения в ветке модератора
        if (punishment.getModeratorThreadId() != null && punishment.getModeratorMessageId() != null) {
            ThreadChannel moderatorThread = jda.getThreadChannelById(punishment.getModeratorThreadId());
            if (moderatorThread != null) {
                moderatorThread.retrieveMessageById(punishment.getModeratorMessageId()).queue(message -> {
                    MessageEmbed unbanEmbed = messageFormatter.createUnbanEmbed(punishment);
                    message.editMessageEmbeds(message.getEmbeds().get(0), unbanEmbed).queue();
                });
            }
        }
    }
    
    /**
     * Удаление сообщений не от бота в форумах
     */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().equals(jda.getSelfUser())) {
            return; // Пропускаем сообщения от бота
        }
        
        if (event.getChannel() instanceof ThreadChannel) {
            ThreadChannel thread = (ThreadChannel) event.getChannel();
            
            // Проверяем, что это ветка в наших форумах
            if (isPlayerForum(thread) || isModeratorForum(thread)) {
                
                // Для ветки модератора - оставляем сообщения от самого модератора
                if (isModeratorForum(thread)) {
                    ModeratorData moderator = getModeratorByThread(thread.getIdLong());
                    if (moderator != null && moderator.getDiscordId() != null && 
                        event.getAuthor().getIdLong() == moderator.getDiscordId()) {
                        return; // Не удаляем сообщения от самого модератора
                    }
                }
                
                // Удаляем сообщение с задержкой
                event.getMessage().delete().queueAfter(1, TimeUnit.SECONDS);
            }
        }
    }
    
    private boolean isPlayerForum(ThreadChannel thread) {
        return playerForum != null && thread.getParentChannel().getIdLong() == playerForum.getIdLong();
    }
    
    private boolean isModeratorForum(ThreadChannel thread) {
        return moderatorForum != null && thread.getParentChannel().getIdLong() == moderatorForum.getIdLong();
    }
    
    private ModeratorData getModeratorByThread(long threadId) {
        return plugin.getDatabaseManager().getModeratorByThreadId(threadId);
    }
    
    // ==================== СОЗДАНИЕ ВЕТОК ====================
    
    /**
     * Создать ветку для игрока
     */
    public ThreadChannel createPlayerThread(PlayerData player) {
        if (playerForum == null) {
            logger.warning("Форум для игроков не найден!");
            return null;
        }
        
        try {
            String threadName = "👤 " + player.getPlayerName();
            
            // Создаем основное сообщение (временно используем простой embed)
            MessageEmbed mainEmbed = messageFormatter.createPunishmentEmbed(new PunishmentData());
            
            // Создаем ветку
            ForumPostAction action = playerForum.createForumPost(threadName, 
                    MessageCreateData.fromEmbeds(mainEmbed));
            
            ThreadChannel thread = action.complete().getThreadChannel();
            
            logger.info("Создана ветка для игрока " + player.getPlayerName() + " (ID: " + thread.getIdLong() + ")");
            
            return thread;
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Ошибка при создании ветки для игрока " + player.getPlayerName(), e);
            return null;
        }
    }
    
    /**
     * Создать ветку для модератора
     */
    public ThreadChannel createModeratorThread(ModeratorData moderator) {
        if (moderatorForum == null) {
            logger.warning("Форум для модераторов не найден!");
            return null;
        }
        
        try {
            String threadName = "👮 " + moderator.getModeratorName();
            
            // Создаем основное сообщение (временно используем простой embed)
            MessageEmbed mainEmbed = messageFormatter.createPunishmentEmbed(new PunishmentData());
            
            // Создаем ветку
            ForumPostAction action = moderatorForum.createForumPost(threadName, 
                    MessageCreateData.fromEmbeds(mainEmbed));
            
            ThreadChannel thread = action.complete().getThreadChannel();
            
            logger.info("Создана ветка для модератора " + moderator.getModeratorName() + " (ID: " + thread.getIdLong() + ")");
            
            return thread;
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Ошибка при создании ветки для модератора " + moderator.getModeratorName(), e);
            return null;
        }
    }
    
    // ==================== ПОЛУЧЕНИЕ ВЕТОК ====================
    
    /**
     * Получить ветку игрока по ID
     */
    public ThreadChannel getPlayerThread(long threadId) {
        try {
            if (playerForum == null) {
                return null;
            }
            
            return playerForum.getThreadChannels().stream()
                    .filter(thread -> thread.getIdLong() == threadId)
                    .findFirst()
                    .orElse(null);
                    
        } catch (Exception e) {
            logger.warning("Ошибка при получении ветки игрока (ID: " + threadId + "): " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Получить ветку модератора по ID
     */
    public ThreadChannel getModeratorThread(long threadId) {
        try {
            if (moderatorForum == null) {
                return null;
            }
            
            return moderatorForum.getThreadChannels().stream()
                    .filter(thread -> thread.getIdLong() == threadId)
                    .findFirst()
                    .orElse(null);
                    
        } catch (Exception e) {
            logger.warning("Ошибка при получении ветки модератора (ID: " + threadId + "): " + e.getMessage());
            return null;
        }
    }
    
    // ==================== ОТПРАВКА СООБЩЕНИЙ ====================
    
    /**
     * Отправить сообщение о наказании в ветку игрока
     */
    public Message sendPlayerPunishmentMessage(ThreadChannel playerThread, PunishmentData punishment) {
        if (playerThread == null) {
            return null;
        }
        
        try {
            MessageEmbed embed = messageFormatter.createPunishmentEmbed(punishment);
            return playerThread.sendMessageEmbeds(embed).complete();
            
        } catch (Exception e) {
            logger.warning("Ошибка при отправке сообщения в ветку игрока: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Отправить сообщение о наказании в ветку модератора
     */
    public Message sendModeratorPunishmentMessage(ThreadChannel moderatorThread, PunishmentData punishment) {
        if (moderatorThread == null) {
            return null;
        }
        
        try {
            MessageEmbed embed = messageFormatter.createPunishmentEmbed(punishment);
            return moderatorThread.sendMessageEmbeds(embed).complete();
            
        } catch (Exception e) {
            logger.warning("Ошибка при отправке сообщения в ветку модератора: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Отправить сообщение в лог канал
     */
    public Message sendLogMessage(PunishmentData punishment) {
        try {
            if (logChannel == null) {
                logger.warning("Лог канал не найден!");
                return null;
            }
            
            MessageEmbed embed;
            if (punishment.isActive()) {
                embed = messageFormatter.createLogEmbed(punishment);
            } else {
                embed = messageFormatter.createUnbanEmbed(punishment);
            }
            
            return logChannel.sendMessageEmbeds(embed).complete();
            
        } catch (Exception e) {
            logger.warning("Ошибка при отправке сообщения в лог канал: " + e.getMessage());
            return null;
        }
    }
    
    // ==================== ОБНОВЛЕНИЕ СООБЩЕНИЙ ====================
    
    /**
     * Обновить основное сообщение в ветке игрока
     */
    public void updatePlayerThreadMainMessage(ThreadChannel playerThread, PlayerData player) {
        if (playerThread == null || player == null) {
            return;
        }
        
        try {
            // Получаем статистику из базы данных
            Map<PunishmentType, Integer> totalCounts = plugin.getDatabaseManager().getPlayerPunishmentCounts(player.getPlayerUuid());
            Map<PunishmentType, Integer> activeCounts = plugin.getDatabaseManager().getPlayerActivePunishmentCounts(player.getPlayerUuid());
            List<PunishmentData> activePunishments = plugin.getDatabaseManager().getPlayerActivePunishments(player.getPlayerUuid());
            
            // Создаем embed статистики
            MessageEmbed statsEmbed = messageFormatter.createPlayerStatsEmbed(
                player.getPlayerName(), 
                player.getPlayerUuid().toString(),
                totalCounts,
                activeCounts,
                activePunishments
            );
            
            // Обновляем первое сообщение в ветке
            playerThread.getHistoryFromBeginning(1).queue(history -> {
                if (!history.getRetrievedHistory().isEmpty()) {
                    Message firstMessage = history.getRetrievedHistory().get(0);
                    if (firstMessage.getAuthor().equals(jda.getSelfUser())) {
                        firstMessage.editMessageEmbeds(statsEmbed).queue();
                        logger.info("Обновлена статистика игрока: " + player.getPlayerName());
                    }
                }
            });
            
        } catch (Exception e) {
            logger.warning("Ошибка при обновлении статистики игрока " + player.getPlayerName() + ": " + e.getMessage());
        }
    }
    
    /**
     * Обновить основное сообщение в ветке модератора
     */
    public void updateModeratorThreadMainMessage(ThreadChannel moderatorThread, ModeratorData moderator) {
        if (moderatorThread == null || moderator == null) {
            return;
        }
        
        try {
            // Получаем статистику из базы данных
            Map<PunishmentType, Integer> issuedCounts = plugin.getDatabaseManager().getModeratorIssuedCounts(moderator.getModeratorUuid());
            
            // Создаем embed статистики
            MessageEmbed statsEmbed = messageFormatter.createModeratorStatsEmbed(
                moderator.getModeratorName(),
                moderator.getModeratorUuid().toString(),
                issuedCounts,
                moderator.getTotalIssued(),
                moderator.getActiveIssued()
            );
            
            // Обновляем первое сообщение в ветке
            moderatorThread.getHistoryFromBeginning(1).queue(history -> {
                if (!history.getRetrievedHistory().isEmpty()) {
                    Message firstMessage = history.getRetrievedHistory().get(0);
                    if (firstMessage.getAuthor().equals(jda.getSelfUser())) {
                        firstMessage.editMessageEmbeds(statsEmbed).queue();
                        logger.info("Обновлена статистика модератора: " + moderator.getModeratorName());
                    }
                }
            });
            
        } catch (Exception e) {
            logger.warning("Ошибка при обновлении статистики модератора " + moderator.getModeratorName() + ": " + e.getMessage());
        }
    }
    
    /**
     * Обновить сообщение о наказании в ветке игрока
     */
    public void updatePlayerPunishmentMessage(long messageId, ThreadChannel playerThread, PunishmentData punishment) {
        if (playerThread == null) {
            return;
        }
        
        try {
            playerThread.retrieveMessageById(messageId).queue(
                message -> {
                    MessageEmbed updatedEmbed = messageFormatter.createPunishmentEmbed(punishment);
                    message.editMessageEmbeds(updatedEmbed).queue();
                },
                throwable -> logger.warning("Не удалось найти сообщение для обновления: " + messageId)
            );
            
        } catch (Exception e) {
            logger.warning("Ошибка при обновлении сообщения о наказании в ветке игрока: " + e.getMessage());
        }
    }
    
    /**
     * Обновить сообщение о наказании в ветке модератора
     */
    public void updateModeratorPunishmentMessage(long messageId, ThreadChannel moderatorThread, PunishmentData punishment) {
        if (moderatorThread == null) {
            return;
        }
        
        try {
            moderatorThread.retrieveMessageById(messageId).queue(
                message -> {
                    MessageEmbed updatedEmbed = messageFormatter.createPunishmentEmbed(punishment);
                    message.editMessageEmbeds(updatedEmbed).queue();
                },
                throwable -> logger.warning("Не удалось найти сообщение для обновления: " + messageId)
            );
            
        } catch (Exception e) {
            logger.warning("Ошибка при обновлении сообщения о наказании в ветке модератора: " + e.getMessage());
        }
    }
    
    // ==================== ОЧИСТКА КАНАЛОВ ====================
    
    /**
     * Удалить сообщения не от ботов из канала
     */
    public void cleanupNonBotMessages(GuildChannel channel) {
        if (channel == null || !plugin.getDiscordManager().isReady()) {
            return;
        }
        
        try {
            if (channel instanceof TextChannel textChannel) {
                cleanupTextChannel(textChannel);
            } else if (channel instanceof ThreadChannel threadChannel) {
                cleanupThreadChannel(threadChannel);
            }
            
        } catch (Exception e) {
            logger.warning("Ошибка при очистке канала " + channel.getName() + ": " + e.getMessage());
        }
    }
    
    /**
     * Очистить текстовый канал от сообщений не от ботов
     */
    private void cleanupTextChannel(TextChannel channel) {
        try {
            MessageHistory history = channel.getHistory();
            List<Message> messages = history.retrievePast(100).complete();
            
            List<Message> toDelete = messages.stream()
                    .filter(msg -> !msg.getAuthor().isBot())
                    .collect(Collectors.toList());
            
            if (!toDelete.isEmpty()) {
                if (toDelete.size() == 1) {
                    toDelete.get(0).delete().queue();
                } else {
                    channel.deleteMessages(toDelete).queue();
                }
                
                logger.info("Удалено " + toDelete.size() + " сообщений не от ботов из канала " + channel.getName());
            }
            
        } catch (Exception e) {
            logger.warning("Ошибка при очистке текстового канала: " + e.getMessage());
        }
    }
    
    /**
     * Очистить ветку от сообщений не от ботов
     */
    private void cleanupThreadChannel(ThreadChannel channel) {
        try {
            MessageHistory history = channel.getHistory();
            List<Message> messages = history.retrievePast(100).complete();
            
            List<Message> toDelete = messages.stream()
                    .filter(msg -> !msg.getAuthor().isBot())
                    .collect(Collectors.toList());
            
            for (Message message : toDelete) {
                message.delete().queue();
            }
            
            if (!toDelete.isEmpty()) {
                logger.info("Удалено " + toDelete.size() + " сообщений не от ботов из ветки " + channel.getName());
            }
            
        } catch (Exception e) {
            logger.warning("Ошибка при очистке ветки: " + e.getMessage());
        }
    }
    
    // ==================== СТАТИСТИКА ФОРУМОВ ====================
    
    /**
     * Получить статистику форумов
     */
    public String getForumStats() {
        if (!plugin.getDiscordManager().isReady()) {
            return "Discord бот не готов";
        }
        
        try {
            StringBuilder stats = new StringBuilder();
            stats.append("📊 Статистика форумов:\n");
            
            ForumChannel playerForum = getPlayerForum();
            if (playerForum != null) {
                int playerThreads = playerForum.getThreadChannels().size();
                stats.append("👥 Ветки игроков: ").append(playerThreads).append("\n");
            }
            
            ForumChannel moderatorForum = getModeratorForum();
            if (moderatorForum != null) {
                int moderatorThreads = moderatorForum.getThreadChannels().size();
                stats.append("👮 Ветки модераторов: ").append(moderatorThreads).append("\n");
            }
            
            TextChannel logChannel = getLogChannel();
            if (logChannel != null) {
                stats.append("📝 Лог канал: активен");
            } else {
                stats.append("📝 Лог канал: не найден");
            }
            
            return stats.toString();
            
        } catch (Exception e) {
            return "Ошибка при получении статистики: " + e.getMessage();
        }
    }

    // ==================== ГЕТТЕРЫ ====================

    /**
     * Получить форум игроков
     */
    public ForumChannel getPlayerForum() {
        return playerForum;
    }

    /**
     * Получить форум модераторов
     */
    public ForumChannel getModeratorForum() {
        return moderatorForum;
    }

    /**
     * Получить лог канал
     */
    public TextChannel getLogChannel() {
        return logChannel;
    }
} 