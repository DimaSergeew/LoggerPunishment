package org.bedepay.loggerpunishment.redis;

import org.bedepay.loggerpunishment.LoggerPunishment;
import org.bedepay.loggerpunishment.config.ConfigManager;
import org.redisson.Redisson;
import org.redisson.api.*;
import org.redisson.config.Config;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Менеджер Redis для кэширования и синхронизации
 */
public class RedisManager {
    
    private final LoggerPunishment plugin;
    private final Logger logger;
    private final ConfigManager configManager;
    private RedissonClient redisson;
    private boolean enabled = false;
    
    // Кэши
    private RMap<String, Long> playerThreadCache;
    private RMap<String, Long> moderatorThreadCache;
    private RMap<String, Long> playerDiscordCache;
    private RMap<String, Boolean> writePermissionsCache;
    private RQueue<String> pendingDiscordActions;
    
    // Блокировки
    private static final String LOCK_PREFIX = "punishment_lock:";
    private static final String MESSAGE_DELETE_LOCK = LOCK_PREFIX + "message_delete:";
    private static final String THREAD_CREATE_LOCK = LOCK_PREFIX + "thread_create:";
    private static final String STATS_UPDATE_LOCK = LOCK_PREFIX + "stats_update:";
    
    public RedisManager(LoggerPunishment plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configManager = plugin.getConfigManager();
    }
    
    /**
     * Инициализация подключения к Redis
     */
    public void initialize() {
        try {
            ConfigManager.RedisConfig redisConfig = configManager.getRedisConfig();
            
            if (redisConfig.host == null || redisConfig.host.isEmpty()) {
                logger.info("Redis не настроен, работаем без кэширования");
                return;
            }
            
            logger.info("Инициализация подключения к Redis...");
            
            // Создание конфигурации Redisson
            Config config = new Config();
            
            String redisUrl = String.format("redis://%s:%d", redisConfig.host, redisConfig.port);
            
            config.useSingleServer()
                    .setAddress(redisUrl)
                    .setDatabase(redisConfig.database)
                    .setTimeout(redisConfig.timeout)
                    .setRetryAttempts(3)
                    .setRetryInterval(1500)
                    .setKeepAlive(true)
                    .setTcpNoDelay(true);
            
            // Установка пароля если указан
            if (redisConfig.password != null && !redisConfig.password.isEmpty()) {
                config.useSingleServer().setPassword(redisConfig.password);
            }
            
            // Настройки пула соединений
            config.useSingleServer()
                    .setConnectionPoolSize(redisConfig.maxActive)
                    .setConnectionMinimumIdleSize(redisConfig.minIdle);
            
            // Создание клиента
            redisson = Redisson.create(config);
            
            // Проверка подключения
            testConnection();
            
            // Инициализация кэшей
            initializeCaches();
            
            enabled = true;
            logger.info("Redis успешно инициализирован: " + redisConfig.host + ":" + redisConfig.port);
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Не удалось подключиться к Redis: " + e.getMessage());
            logger.info("Продолжаем работу без Redis кэширования");
            enabled = false;
        }
    }
    
    /**
     * Проверка подключения к Redis
     */
    private void testConnection() {
        try {
            RBucket<String> testBucket = redisson.getBucket("test_connection");
            testBucket.set("test", 1, TimeUnit.SECONDS);
            String result = testBucket.get();
            
            if (!"test".equals(result)) {
                throw new RuntimeException("Не удалось записать/прочитать тестовые данные");
            }
            
            testBucket.delete();
            logger.info("Подключение к Redis проверено успешно");
            
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при тестировании Redis: " + e.getMessage(), e);
        }
    }
    
    /**
     * Инициализация кэшей
     */
    private void initializeCaches() {
        ConfigManager.CacheSettings cacheSettings = configManager.getCacheSettings();
        
        // Кэш ID веток игроков
        playerThreadCache = redisson.getMap("player_threads");
        playerThreadCache.expire(cacheSettings.threadCacheTtl, TimeUnit.MINUTES);
        
        // Кэш ID веток модераторов
        moderatorThreadCache = redisson.getMap("moderator_threads");
        moderatorThreadCache.expire(cacheSettings.threadCacheTtl, TimeUnit.MINUTES);
        
        // Кэш Discord ID игроков
        playerDiscordCache = redisson.getMap("player_discord_ids");
        playerDiscordCache.expire(cacheSettings.playerDiscordCacheTtl, TimeUnit.MINUTES);
        
        // Кэш разрешений на отправку сообщений
        writePermissionsCache = redisson.getMap("write_permissions");
        writePermissionsCache.expire(cacheSettings.permissionsCacheTtl, TimeUnit.MINUTES);
        
        // Очередь действий Discord
        pendingDiscordActions = redisson.getQueue("pending_discord_actions");
        
        logger.info("Кэши Redis инициализированы");
    }
    
    /**
     * Проверка доступности Redis
     */
    public boolean isEnabled() {
        return enabled && redisson != null && !redisson.isShutdown();
    }
    
    // ==================== МЕТОДЫ КЭШИРОВАНИЯ ====================
    
    /**
     * Получить ID ветки игрока
     */
    public Long getPlayerThreadId(String playerUuid) {
        if (!isEnabled()) return null;
        
        try {
            return playerThreadCache.get(playerUuid);
        } catch (Exception e) {
            logger.warning("Ошибка при получении ID ветки игрока из кэша: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Сохранить ID ветки игрока
     */
    public void setPlayerThreadId(String playerUuid, Long threadId) {
        if (!isEnabled()) return;
        
        try {
            playerThreadCache.put(playerUuid, threadId);
        } catch (Exception e) {
            logger.warning("Ошибка при сохранении ID ветки игрока в кэш: " + e.getMessage());
        }
    }
    
    /**
     * Получить ID ветки модератора
     */
    public Long getModeratorThreadId(String moderatorUuid) {
        if (!isEnabled()) return null;
        
        try {
            return moderatorThreadCache.get(moderatorUuid);
        } catch (Exception e) {
            logger.warning("Ошибка при получении ID ветки модератора из кэша: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Сохранить ID ветки модератора
     */
    public void setModeratorThreadId(String moderatorUuid, Long threadId) {
        if (!isEnabled()) return;
        
        try {
            moderatorThreadCache.put(moderatorUuid, threadId);
        } catch (Exception e) {
            logger.warning("Ошибка при сохранении ID ветки модератора в кэш: " + e.getMessage());
        }
    }
    
    /**
     * Получить Discord ID игрока
     */
    public Long getPlayerDiscordId(String playerUuid) {
        if (!isEnabled()) return null;
        
        try {
            return playerDiscordCache.get(playerUuid);
        } catch (Exception e) {
            logger.warning("Ошибка при получении Discord ID из кэша: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Сохранить Discord ID игрока
     */
    public void setPlayerDiscordId(String playerUuid, Long discordId) {
        if (!isEnabled()) return;
        
        try {
            playerDiscordCache.put(playerUuid, discordId);
        } catch (Exception e) {
            logger.warning("Ошибка при сохранении Discord ID в кэш: " + e.getMessage());
        }
    }
    
    /**
     * Проверить разрешение на отправку сообщения
     */
    public Boolean hasWritePermission(String key) {
        if (!isEnabled()) return null;
        
        try {
            return writePermissionsCache.get(key);
        } catch (Exception e) {
            logger.warning("Ошибка при проверке разрешений из кэша: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Сохранить разрешение на отправку сообщения
     */
    public void setWritePermission(String key, boolean hasPermission) {
        if (!isEnabled()) return;
        
        try {
            writePermissionsCache.put(key, hasPermission);
        } catch (Exception e) {
            logger.warning("Ошибка при сохранении разрешений в кэш: " + e.getMessage());
        }
    }
    
    /**
     * Кэшировать ID ветки игрока
     */
    public void cachePlayerThreadId(String playerUuid, Long threadId) {
        setPlayerThreadId(playerUuid, threadId);
    }
    
    /**
     * Кэшировать ID ветки модератора
     */
    public void cacheModeratorThreadId(String moderatorUuid, Long threadId) {
        setModeratorThreadId(moderatorUuid, threadId);
    }
    
    // ==================== МЕТОДЫ БЛОКИРОВОК ====================
    
    /**
     * Получить блокировку по ключу
     */
    public RLock getLock(String key) {
        if (!isEnabled()) return null;
        
        try {
            return redisson.getLock(LOCK_PREFIX + key);
        } catch (Exception e) {
            logger.warning("Ошибка при получении блокировки: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Получить блокировку для удаления сообщения
     */
    public RLock getMessageDeletionLock(long messageId) {
        if (!isEnabled()) return null;
        
        try {
            return redisson.getLock(MESSAGE_DELETE_LOCK + messageId);
        } catch (Exception e) {
            logger.warning("Ошибка при получении блокировки удаления сообщения: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Получить блокировку для создания ветки
     */
    public RLock getThreadCreationLock(String uuid) {
        if (!isEnabled()) return null;
        
        try {
            return redisson.getLock(THREAD_CREATE_LOCK + uuid);
        } catch (Exception e) {
            logger.warning("Ошибка при получении блокировки создания ветки: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Получить блокировку для обновления статистики
     */
    public RLock getStatsUpdateLock(String identifier) {
        if (!isEnabled()) return null;
        
        try {
            return redisson.getLock(STATS_UPDATE_LOCK + identifier);
        } catch (Exception e) {
            logger.warning("Ошибка при получении блокировки обновления статистики: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Выполнить действие с блокировкой
     */
    public boolean executeWithLock(RLock lock, int timeoutSeconds, Runnable action) {
        if (lock == null) {
            // Если Redis недоступен, выполняем без блокировки
            action.run();
            return true;
        }
        
        try {
            if (lock.tryLock(timeoutSeconds, TimeUnit.SECONDS)) {
                try {
                    action.run();
                    return true;
                } finally {
                    lock.unlock();
                }
            } else {
                logger.warning("Не удалось получить блокировку в течение " + timeoutSeconds + " секунд");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("Прервано ожидание блокировки: " + e.getMessage());
            return false;
        } catch (Exception e) {
            logger.warning("Ошибка при выполнении действия с блокировкой: " + e.getMessage());
            return false;
        }
    }
    
    // ==================== ОЧЕРЕДЬ ДЕЙСТВИЙ ====================
    
    /**
     * Добавить действие в очередь
     */
    public void queueAction(String actionData) {
        if (!isEnabled()) return;
        
        try {
            pendingDiscordActions.offer(actionData);
        } catch (Exception e) {
            logger.warning("Ошибка при добавлении действия в очередь: " + e.getMessage());
        }
    }
    
    /**
     * Получить действие из очереди
     */
    public String pollAction() {
        if (!isEnabled()) return null;
        
        try {
            return pendingDiscordActions.poll();
        } catch (Exception e) {
            logger.warning("Ошибка при получении действия из очереди: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Получить размер очереди
     */
    public int getQueueSize() {
        if (!isEnabled()) return 0;
        
        try {
            return pendingDiscordActions.size();
        } catch (Exception e) {
            logger.warning("Ошибка при получении размера очереди: " + e.getMessage());
            return 0;
        }
    }
    
    // ==================== АТОМАРНЫЕ ОПЕРАЦИИ ====================
    
    /**
     * Получить атомарный счетчик
     */
    public RAtomicLong getAtomicLong(String key) {
        if (!isEnabled()) return null;
        
        try {
            return redisson.getAtomicLong(key);
        } catch (Exception e) {
            logger.warning("Ошибка при получении атомарного счетчика: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Проверить и обновить время последнего обновления статистики
     */
    public boolean shouldUpdateStats(String key, long intervalMillis) {
        if (!isEnabled()) return true; // Без Redis обновляем всегда
        
        try {
            RAtomicLong lastUpdate = getAtomicLong("last_stats_update:" + key);
            if (lastUpdate == null) return true;
            
            long currentTime = System.currentTimeMillis();
            long lastUpdateTime = lastUpdate.get();
            
            if (currentTime - lastUpdateTime > intervalMillis) {
                return lastUpdate.compareAndSet(lastUpdateTime, currentTime);
            }
            
            return false;
        } catch (Exception e) {
            logger.warning("Ошибка при проверке времени обновления статистики: " + e.getMessage());
            return true;
        }
    }
    
    // ==================== СТАТИСТИКА И МОНИТОРИНГ ====================
    
    /**
     * Получить статистику Redis
     */
    public String getRedisStats() {
        if (!isEnabled()) {
            return "Redis отключен";
        }
        
        try {
            StringBuilder stats = new StringBuilder();
            stats.append("Redis статистика:\n");
            stats.append("- Ветки игроков в кэше: ").append(playerThreadCache.size()).append("\n");
            stats.append("- Ветки модераторов в кэше: ").append(moderatorThreadCache.size()).append("\n");
            stats.append("- Discord ID в кэше: ").append(playerDiscordCache.size()).append("\n");
            stats.append("- Разрешения в кэше: ").append(writePermissionsCache.size()).append("\n");
            stats.append("- Действий в очереди: ").append(pendingDiscordActions.size());
            
            return stats.toString();
        } catch (Exception e) {
            return "Ошибка при получении статистики Redis: " + e.getMessage();
        }
    }
    
    /**
     * Очистить все кэши
     */
    public void clearCaches() {
        if (!isEnabled()) return;
        
        try {
            playerThreadCache.clear();
            moderatorThreadCache.clear();
            playerDiscordCache.clear();
            writePermissionsCache.clear();
            logger.info("Все кэши Redis очищены");
        } catch (Exception e) {
            logger.warning("Ошибка при очистке кэшей: " + e.getMessage());
        }
    }
    
    /**
     * Безопасное закрытие подключения к Redis
     */
    public void shutdown() {
        try {
            if (redisson != null && !redisson.isShutdown()) {
                logger.info("Закрытие подключения к Redis...");
                redisson.shutdown();
                logger.info("Подключение к Redis закрыто");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при закрытии Redis: " + e.getMessage(), e);
        } finally {
            enabled = false;
        }
    }
} 