package org.bedepay.loggerpunishment.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bedepay.loggerpunishment.LoggerPunishment;
import org.bedepay.loggerpunishment.config.ConfigManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * API для работы с AuthBot для получения Discord ID игроков
 */
public class AuthBotAPI {
    
    private final LoggerPunishment plugin;
    private final ConfigManager configManager;
    private final Logger logger;
    private final Gson gson;
    
    // Кэш для Discord ID игроков
    private final ConcurrentHashMap<UUID, Long> discordIdCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> cacheTimestamps = new ConcurrentHashMap<>();
    
    private boolean apiAvailable = false;
    private long lastApiCheck = 0;
    private static final long API_CHECK_INTERVAL = TimeUnit.MINUTES.toMillis(5); // Проверка каждые 5 минут
    private static final long CACHE_TTL = TimeUnit.HOURS.toMillis(1); // Кэш на 1 час
    
    public AuthBotAPI(LoggerPunishment plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.logger = plugin.getLogger();
        this.gson = new Gson();
        
        // Проверка доступности API при инициализации
        checkApiAvailability();
    }
    
    /**
     * Получить Discord ID игрока по UUID
     */
    public CompletableFuture<Long> getDiscordId(UUID playerUuid) {
        if (playerUuid == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Проверяем кэш
        Long cachedId = getCachedDiscordId(playerUuid);
        if (cachedId != null) {
            return CompletableFuture.completedFuture(cachedId);
        }
        
        // Проверяем доступность API
        if (!isApiAvailable()) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return fetchDiscordIdFromApi(playerUuid);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Ошибка при получении Discord ID для " + playerUuid + ": " + e.getMessage(), e);
                return null;
            }
        });
    }
    
    /**
     * Получить Discord ID из кэша
     */
    private Long getCachedDiscordId(UUID playerUuid) {
        Long timestamp = cacheTimestamps.get(playerUuid);
        if (timestamp == null) {
            return null;
        }
        
        // Проверяем актуальность кэша
        if (System.currentTimeMillis() - timestamp > CACHE_TTL) {
            discordIdCache.remove(playerUuid);
            cacheTimestamps.remove(playerUuid);
            return null;
        }
        
        return discordIdCache.get(playerUuid);
    }
    
    /**
     * Получить Discord ID из API
     */
    private Long fetchDiscordIdFromApi(UUID playerUuid) throws IOException {
        ConfigManager.AuthBotConfig authConfig = configManager.getAuthBotConfig();
        
        String apiUrl = authConfig.apiUrl + "?uuid=" + playerUuid.toString();
        
        for (int attempt = 1; attempt <= authConfig.retryAttempts; attempt++) {
            try {
                URL url = new URL(apiUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                
                // Настройка подключения
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(authConfig.timeout);
                connection.setReadTimeout(authConfig.timeout);
                connection.setRequestProperty("User-Agent", "LoggerPunishment/1.0");
                connection.setRequestProperty("Accept", "application/json");
                
                int responseCode = connection.getResponseCode();
                
                if (responseCode == 200) {
                    // Успешный ответ
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream()))) {
                        
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        
                        Long discordId = parseDiscordIdFromResponse(response.toString());
                        
                        if (discordId != null) {
                            // Кэшируем результат
                            cacheDiscordId(playerUuid, discordId);
                            logger.fine("Discord ID получен для " + playerUuid + ": " + discordId);
                        }
                        
                        return discordId;
                    }
                    
                } else if (responseCode == 404) {
                    // Игрок не привязал Discord
                    logger.fine("Игрок " + playerUuid + " не привязал Discord аккаунт");
                    return null;
                    
                } else {
                    // Ошибка сервера
                    logger.warning("AuthBot API вернул код " + responseCode + " для " + playerUuid);
                    
                    if (attempt < authConfig.retryAttempts) {
                        Thread.sleep(authConfig.retryDelay);
                        continue;
                    }
                }
                
            } catch (Exception e) {
                logger.warning("Попытка " + attempt + " получения Discord ID не удалась: " + e.getMessage());
                
                if (attempt < authConfig.retryAttempts) {
                    try {
                        Thread.sleep(authConfig.retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    throw new IOException("Не удалось получить Discord ID после " + authConfig.retryAttempts + " попыток", e);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Парсинг Discord ID из ответа API
     */
    private Long parseDiscordIdFromResponse(String jsonResponse) {
        try {
            JsonObject jsonObject = JsonParser.parseString(jsonResponse).getAsJsonObject();
            
            // Проверяем различные возможные форматы ответа
            if (jsonObject.has("discord_id")) {
                return jsonObject.get("discord_id").getAsLong();
            } else if (jsonObject.has("discordId")) {
                return jsonObject.get("discordId").getAsLong();
            } else if (jsonObject.has("id")) {
                return jsonObject.get("id").getAsLong();
            } else if (jsonObject.has("discord")) {
                JsonObject discord = jsonObject.getAsJsonObject("discord");
                if (discord.has("id")) {
                    return discord.get("id").getAsLong();
                }
            }
            
            logger.warning("Неожиданный формат ответа AuthBot API: " + jsonResponse);
            return null;
            
        } catch (Exception e) {
            logger.warning("Ошибка при парсинге ответа AuthBot API: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Кэшировать Discord ID
     */
    private void cacheDiscordId(UUID playerUuid, Long discordId) {
        discordIdCache.put(playerUuid, discordId);
        cacheTimestamps.put(playerUuid, System.currentTimeMillis());
        
        // Также кэшируем в Redis если доступен
        if (plugin.getRedisManager() != null && plugin.getRedisManager().isEnabled()) {
            plugin.getRedisManager().setPlayerDiscordId(playerUuid.toString(), discordId);
        }
    }
    
    /**
     * Проверить доступность API
     */
    public boolean isApiAvailable() {
        long currentTime = System.currentTimeMillis();
        
        // Проверяем не чаще чем раз в 5 минут
        if (currentTime - lastApiCheck < API_CHECK_INTERVAL) {
            return apiAvailable;
        }
        
        checkApiAvailability();
        return apiAvailable;
    }
    
    /**
     * Проверка доступности API
     */
    private void checkApiAvailability() {
        CompletableFuture.runAsync(() -> {
            try {
                ConfigManager.AuthBotConfig authConfig = configManager.getAuthBotConfig();
                
                URL url = new URL(authConfig.apiUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent", "LoggerPunishment/1.0");
                
                int responseCode = connection.getResponseCode();
                
                apiAvailable = (responseCode >= 200 && responseCode < 500);
                lastApiCheck = System.currentTimeMillis();
                
                if (apiAvailable) {
                    logger.fine("AuthBot API доступен");
                } else {
                    logger.warning("AuthBot API недоступен (код: " + responseCode + ")");
                }
                
            } catch (Exception e) {
                apiAvailable = false;
                lastApiCheck = System.currentTimeMillis();
                logger.warning("AuthBot API недоступен: " + e.getMessage());
            }
        });
    }
    
    /**
     * Получить Discord ID игрока с проверкой в Redis
     */
    public CompletableFuture<Long> getDiscordIdWithRedis(UUID playerUuid) {
        if (playerUuid == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            // Сначала проверяем Redis
            if (plugin.getRedisManager() != null && plugin.getRedisManager().isEnabled()) {
                Long redisId = plugin.getRedisManager().getPlayerDiscordId(playerUuid.toString());
                if (redisId != null) {
                    return redisId;
                }
            }
            
            // Затем проверяем локальный кэш и API
            try {
                return getDiscordId(playerUuid).get();
            } catch (Exception e) {
                logger.warning("Ошибка при получении Discord ID: " + e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Предварительная загрузка Discord ID для списка игроков
     */
    public CompletableFuture<Void> preloadDiscordIds(java.util.Collection<UUID> playerUuids) {
        if (!isApiAvailable() || playerUuids.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(() -> {
            logger.info("Предварительная загрузка Discord ID для " + playerUuids.size() + " игроков");
            
            for (UUID playerUuid : playerUuids) {
                try {
                    getDiscordId(playerUuid).get();
                    Thread.sleep(100); // Небольшая задержка между запросами
                } catch (Exception e) {
                    logger.fine("Не удалось загрузить Discord ID для " + playerUuid + ": " + e.getMessage());
                }
            }
            
            logger.info("Предварительная загрузка Discord ID завершена");
        });
    }
    
    /**
     * Очистить кэш Discord ID
     */
    public void clearCache() {
        discordIdCache.clear();
        cacheTimestamps.clear();
        logger.info("Кэш Discord ID очищен");
    }
    
    /**
     * Получить статистику API
     */
    public String getApiStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("🔗 AuthBot API статистика:\n");
        stats.append("📡 Доступность: ").append(apiAvailable ? "✅ Доступен" : "❌ Недоступен").append("\n");
        stats.append("💾 Кэшированных ID: ").append(discordIdCache.size()).append("\n");
        stats.append("⏰ Последняя проверка: ");
        
        if (lastApiCheck > 0) {
            long minutesAgo = (System.currentTimeMillis() - lastApiCheck) / 60000;
            stats.append(minutesAgo).append(" мин. назад");
        } else {
            stats.append("Не проверялось");
        }
        
        return stats.toString();
    }
    
    /**
     * Тестирование API с конкретным UUID
     */
    public CompletableFuture<String> testApi(UUID testUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Тестирование AuthBot API с UUID: " + testUuid);
                
                Long discordId = fetchDiscordIdFromApi(testUuid);
                
                if (discordId != null) {
                    return "✅ API работает. Discord ID: " + discordId;
                } else {
                    return "⚠️ API работает, но Discord ID не найден";
                }
                
            } catch (Exception e) {
                return "❌ Ошибка API: " + e.getMessage();
            }
        });
    }
    
    /**
     * Безопасное закрытие API
     */
    public void shutdown() {
        try {
            logger.info("Закрытие AuthBot API...");
            clearCache();
            logger.info("AuthBot API закрыт");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при закрытии AuthBot API: " + e.getMessage(), e);
        }
    }
} 