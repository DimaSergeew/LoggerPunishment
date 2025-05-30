package org.bedepay.loggerpunishment.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * Утилитный класс для форматирования времени
 */
public class TimeFormatter {
    
    private static final DateTimeFormatter DATE_TIME_FORMATTER = 
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.of("Europe/Moscow"));
    
    /**
     * Форматировать длительность наказания в читаемый вид
     */
    public static String formatDuration(Long durationSeconds) {
        if (durationSeconds == null || durationSeconds <= 0) {
            return "Навсегда";
        }
        
        Duration duration = Duration.ofSeconds(durationSeconds);
        
        long days = duration.toDays();
        long hours = duration.toHours() % 24;
        long minutes = duration.toMinutes() % 60;
        long seconds = duration.getSeconds() % 60;
        
        StringBuilder result = new StringBuilder();
        
        if (days > 0) {
            result.append(days).append(" д. ");
        }
        if (hours > 0) {
            result.append(hours).append(" ч. ");
        }
        if (minutes > 0) {
            result.append(minutes).append(" мин. ");
        }
        if (seconds > 0 && days == 0) { // Показываем секунды только для коротких наказаний
            result.append(seconds).append(" сек.");
        }
        
        return result.toString().trim();
    }
    
    /**
     * Форматировать оставшееся время до окончания наказания
     */
    public static String formatTimeLeft(Instant expiresAt) {
        if (expiresAt == null) {
            return "Навсегда";
        }
        
        Instant now = Instant.now();
        if (now.isAfter(expiresAt)) {
            return "Истекло";
        }
        
        Duration timeLeft = Duration.between(now, expiresAt);
        return formatDuration(timeLeft.getSeconds());
    }
    
    /**
     * Форматировать дату и время в читаемый вид
     */
    public static String formatDateTime(Instant instant) {
        if (instant == null) {
            return "Неизвестно";
        }
        return DATE_TIME_FORMATTER.format(instant);
    }
    
    /**
     * Форматировать относительное время (например, "2 часа назад")
     */
    public static String formatRelativeTime(Instant instant) {
        if (instant == null) {
            return "Неизвестно";
        }
        
        Duration duration = Duration.between(instant, Instant.now());
        long seconds = duration.getSeconds();
        
        if (seconds < 60) {
            return "только что";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            return minutes + " мин. назад";
        } else if (seconds < 86400) {
            long hours = seconds / 3600;
            return hours + " ч. назад";
        } else {
            long days = seconds / 86400;
            if (days == 1) {
                return "вчера";
            } else if (days < 7) {
                return days + " д. назад";
            } else {
                return formatDateTime(instant);
            }
        }
    }
    
    /**
     * Конвертировать миллисекунды в секунды (для LiteBans)
     */
    public static Long millisecondsToSeconds(Long milliseconds) {
        if (milliseconds == null || milliseconds <= 0) {
            return null;
        }
        return TimeUnit.MILLISECONDS.toSeconds(milliseconds);
    }
    
    /**
     * Конвертировать секунды в миллисекунды
     */
    public static Long secondsToMilliseconds(Long seconds) {
        if (seconds == null || seconds <= 0) {
            return null;
        }
        return TimeUnit.SECONDS.toMillis(seconds);
    }
    
    /**
     * Проверить, истекло ли время наказания
     */
    public static boolean isExpired(Instant expiresAt) {
        if (expiresAt == null) {
            return false; // Постоянное наказание не истекает
        }
        return Instant.now().isAfter(expiresAt);
    }
    
    /**
     * Создать Instant из времени истечения (текущее время + длительность)
     */
    public static Instant createExpiryTime(Long durationSeconds) {
        if (durationSeconds == null || durationSeconds <= 0) {
            return null; // Постоянное наказание
        }
        return Instant.now().plusSeconds(durationSeconds);
    }
    
    /**
     * Получить timestamp для базы данных
     */
    public static long getCurrentTimestamp() {
        return Instant.now().getEpochSecond();
    }
    
    /**
     * Форматировать длительность для Discord (краткий формат)
     */
    public static String formatDurationShort(Long durationSeconds) {
        if (durationSeconds == null || durationSeconds <= 0) {
            return "∞";
        }
        
        Duration duration = Duration.ofSeconds(durationSeconds);
        
        long days = duration.toDays();
        if (days > 0) {
            return days + "д";
        }
        
        long hours = duration.toHours();
        if (hours > 0) {
            return hours + "ч";
        }
        
        long minutes = duration.toMinutes();
        if (minutes > 0) {
            return minutes + "м";
        }
        
        return duration.getSeconds() + "с";
    }
} 