package org.bedepay.loggerpunishment.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.bedepay.loggerpunishment.LoggerPunishment;
import org.bedepay.loggerpunishment.config.ConfigManager;
import org.bedepay.loggerpunishment.model.PunishmentData;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Менеджер для управления Discord ботом
 */
public class DiscordManager {
    
    private final LoggerPunishment plugin;
    private final ConfigManager config;
    private final MessageFormatter messageFormatter;
    private final Logger logger;
    
    private JDA jda;
    private TextChannel playerChannel;
    private TextChannel moderatorChannel;
    private TextChannel logChannel;
    private Guild guild;
    
    private boolean enabled = false;
    private boolean ready = false;
    
    public DiscordManager(LoggerPunishment plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.messageFormatter = new MessageFormatter(plugin);
        this.logger = plugin.getLogger();
    }
    
    /**
     * Инициализация Discord бота
     */
    public void initialize() {
        try {
            String token = config.getDiscordToken();
            
            if (token == null || token.isEmpty() || token.equals("YOUR_DISCORD_BOT_TOKEN")) {
                logger.warning("Discord токен не настроен, Discord функции отключены");
                return;
            }
            
            logger.info("Инициализация Discord бота...");
            
            // Создание JDA
            JDABuilder builder = JDABuilder.createDefault(token)
                    .setActivity(Activity.watching("наказания на сервере"))
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .enableIntents(
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT,
                            GatewayIntent.GUILD_MEMBERS
                    );
            
            // Асинхронная инициализация
            CompletableFuture.supplyAsync(() -> {
                try {
                    return builder.build();
                } catch (Exception e) {
                    throw new RuntimeException("Ошибка при создании JDA", e);
                }
            }).thenCompose(jdaInstance -> {
                this.jda = jdaInstance;
                
                // Ожидание готовности
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        jdaInstance.awaitReady();
                        return jdaInstance;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Прервано ожидание готовности JDA", e);
                    }
                });
            }).thenAccept(readyJda -> {
                // Проверка гильдии
                long guildId = config.getDiscordConfig().guildId;
                if (guildId > 0) {
                    this.guild = readyJda.getGuildById(guildId);
                    if (this.guild == null) {
                        logger.warning("Гильдия с ID " + guildId + " не найдена!");
                    } else {
                        logger.info("Подключен к гильдии: " + this.guild.getName());
                    }
                }
                
                this.enabled = true;
                this.ready = true;
                
                logger.info("Discord бот успешно инициализирован и готов к работе");
                
                // Запуск периодических задач
                startPeriodicTasks();
                
            }).exceptionally(throwable -> {
                logger.log(Level.SEVERE, "Ошибка при инициализации Discord бота: " + throwable.getMessage(), throwable);
                this.enabled = false;
                this.ready = false;
                return null;
            });
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Критическая ошибка при инициализации Discord: " + e.getMessage(), e);
            this.enabled = false;
            this.ready = false;
        }
    }
    
    /**
     * Запуск периодических задач
     */
    private void startPeriodicTasks() {
        if (!isReady()) {
            return;
        }
        
        // Задача проверки подключения каждые 5 минут
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (jda != null) {
                JDA.Status status = jda.getStatus();
                if (status != JDA.Status.CONNECTED) {
                    logger.warning("Discord бот не подключен. Статус: " + status);
                    
                    // Попытка переподключения
                    if (status == JDA.Status.DISCONNECTED || status == JDA.Status.FAILED_TO_LOGIN) {
                        logger.info("Попытка переподключения к Discord...");
                        attemptReconnect();
                    }
                }
            }
        }, 20L * 60L * 5L, 20L * 60L * 5L); // Каждые 5 минут
        
        // Обновление активности каждые 10 минут
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (isReady()) {
                updateActivity();
            }
        }, 20L * 60L * 10L, 20L * 60L * 10L); // Каждые 10 минут
    }
    
    /**
     * Попытка переподключения
     */
    private void attemptReconnect() {
        try {
            if (jda != null && !jda.getStatus().equals(JDA.Status.SHUTDOWN)) {
                // Попытка переподключения через JDA
                jda.getRestPing().queue(
                    ping -> logger.info("Discord подключение восстановлено. Пинг: " + ping + "мс"),
                    error -> {
                        logger.warning("Не удалось переподключиться к Discord: " + error.getMessage());
                        
                        // Если не удалось, пытаемся пересоздать JDA
                        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                            logger.info("Попытка пересоздания Discord подключения...");
                            shutdown();
                            initialize();
                        }, 20L * 30L); // Через 30 секунд
                    }
                );
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при попытке переподключения: " + e.getMessage(), e);
        }
    }
    
    /**
     * Обновление активности бота
     */
    private void updateActivity() {
        try {
            if (jda != null && isReady()) {
                // Получаем статистику для активности
                int onlinePlayers = plugin.getServer().getOnlinePlayers().size();
                String activityText = "за " + onlinePlayers + " игроками";
                
                jda.getPresence().setActivity(Activity.watching(activityText));
            }
        } catch (Exception e) {
            logger.warning("Ошибка при обновлении активности Discord бота: " + e.getMessage());
        }
    }
    
    /**
     * Инициализация каналов Discord
     */
    private void initializeChannels() {
        long playerChannelId = config.getPlayerChannelId();
        long moderatorChannelId = config.getModeratorChannelId();
        long logChannelId = config.getLogChannelId();
        
        if (playerChannelId > 0) {
            playerChannel = jda.getTextChannelById(playerChannelId);
            if (playerChannel == null) {
                logger.warning("Канал для игроков не найден: " + playerChannelId);
            }
        }
        
        if (moderatorChannelId > 0) {
            moderatorChannel = jda.getTextChannelById(moderatorChannelId);
            if (moderatorChannel == null) {
                logger.warning("Канал для модераторов не найден: " + moderatorChannelId);
            }
        }
        
        if (logChannelId > 0) {
            logChannel = jda.getTextChannelById(logChannelId);
            if (logChannel == null) {
                plugin.getLogger().warning("Канал для логов не найден: " + logChannelId);
            }
        }
    }
    
    /**
     * Отправка уведомления о наказании
     */
    public CompletableFuture<Void> sendPunishmentNotification(PunishmentData punishment) {
        if (!enabled) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                // Отправка в канал игроков
                if (playerChannel != null && config.isPlayerNotificationsEnabled()) {
                    playerChannel.sendMessageEmbeds(messageFormatter.createPlayerNotification(punishment))
                            .queue(message -> {
                                punishment.setPlayerMessageId(message.getIdLong());
                                plugin.getDatabaseManager().updatePunishment(punishment);
                            });
                }
                
                // Отправка в канал модераторов
                if (moderatorChannel != null && config.isModeratorNotificationsEnabled()) {
                    moderatorChannel.sendMessageEmbeds(messageFormatter.createModeratorNotification(punishment))
                            .queue(message -> {
                                punishment.setModeratorMessageId(message.getIdLong());
                                plugin.getDatabaseManager().updatePunishment(punishment);
                            });
                }
                
                // Отправка в канал логов
                if (logChannel != null && config.isLogNotificationsEnabled()) {
                    logChannel.sendMessageEmbeds(messageFormatter.createLogNotification(punishment))
                            .queue(message -> {
                                punishment.setLogMessageId(message.getIdLong());
                                plugin.getDatabaseManager().updatePunishment(punishment);
                            });
                }
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Ошибка при отправке Discord уведомления: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Отправка уведомления о снятии наказания
     */
    public CompletableFuture<Void> sendUnbanNotification(PunishmentData punishment) {
        if (!enabled) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            try {
                // Отправка в канал игроков
                if (playerChannel != null && config.isPlayerNotificationsEnabled()) {
                    playerChannel.sendMessageEmbeds(messageFormatter.createUnbanPlayerNotification(punishment)).queue();
                }
                
                // Отправка в канал модераторов
                if (moderatorChannel != null && config.isModeratorNotificationsEnabled()) {
                    moderatorChannel.sendMessageEmbeds(messageFormatter.createUnbanModeratorNotification(punishment)).queue();
                }
                
                // Отправка в канал логов
                if (logChannel != null && config.isLogNotificationsEnabled()) {
                    logChannel.sendMessageEmbeds(messageFormatter.createUnbanLogNotification(punishment)).queue();
                }
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Ошибка при отправке Discord уведомления о разбане: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Обновление существующего сообщения
     */
    public void updateMessage(long channelId, long messageId, PunishmentData punishment) {
        if (!enabled || jda == null) {
            return;
        }
        
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel != null) {
            channel.retrieveMessageById(messageId).queue(message -> {
                message.editMessageEmbeds(messageFormatter.createUpdatedNotification(punishment)).queue();
            }, throwable -> {
                plugin.getLogger().warning("Не удалось обновить сообщение " + messageId + " в канале " + channelId);
            });
        }
    }
    
    /**
     * Проверка доступности Discord
     */
    public boolean isEnabled() {
        return enabled && jda != null;
    }
    
    /**
     * Проверка готовности Discord бота
     */
    public boolean isReady() {
        return enabled && jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }
    
    /**
     * Получить JDA инстанс
     */
    public JDA getJDA() {
        return jda;
    }
    
    /**
     * Остановка Discord бота
     */
    public void shutdown() {
        if (jda != null) {
            plugin.getLogger().info("Отключение Discord бота...");
            jda.shutdown();
            
            try {
                if (!jda.awaitShutdown(10, TimeUnit.SECONDS)) {
                    jda.shutdownNow();
                    if (!jda.awaitShutdown(5, TimeUnit.SECONDS)) {
                        plugin.getLogger().warning("Discord бот не ответил на shutdown");
                    }
                }
            } catch (InterruptedException e) {
                jda.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            enabled = false;
            plugin.getLogger().info("Discord бот отключен");
        }
    }
} 