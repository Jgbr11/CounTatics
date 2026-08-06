import { createLogger, format, transports } from "winston";

/**
 * Logger centralizado do Steam Bot.
 * 
 * Usa Winston para logging estruturado com timestamps,
 * colorização no console e formatação legível.
 */
const logger = createLogger({
  level: process.env.LOG_LEVEL || "info",
  format: format.combine(
    format.timestamp({ format: "YYYY-MM-DD HH:mm:ss" }),
    format.errors({ stack: true }),
    format.splat(),
    format.printf(({ timestamp, level, message, ...meta }) => {
      const metaStr = Object.keys(meta).length ? ` ${JSON.stringify(meta)}` : "";
      return `[${timestamp}] ${level.toUpperCase().padEnd(5)} ${message}${metaStr}`;
    })
  ),
  transports: [
    new transports.Console({
      format: format.combine(
        format.colorize({ all: true }),
        format.timestamp({ format: "HH:mm:ss" }),
        format.printf(({ timestamp, level, message }) => {
          return `[${timestamp}] ${level} ${message}`;
        })
      ),
    }),
    new transports.File({
      filename: "steam-bot.log",
      maxsize: 5 * 1024 * 1024, // 5MB
      maxFiles: 3,
    }),
  ],
});

export default logger;
